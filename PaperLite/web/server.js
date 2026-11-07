'use strict';

/**
 * PaperLite 电脑网页版 · 本地 ADB 桥接
 *
 * 浏览器跑不了 ADB，所以电脑侧起这个 Node 服务：
 *   - 托管 public/ 下的单文件网页控制台
 *   - 把网页的 /api/* 请求翻译成 adb 命令，连上手机（无线调试）并把真实输出回传
 *
 * 仅用 Node 内置模块，零第三方依赖。adb 调用一律走 spawn + 参数数组，
 * 不拼 shell 字符串，天然防命令注入。
 *
 * 环境变量：
 *   ADB_BIN   adb 可执行文件路径（默认自动探测 E:\pl_tools\platform-tools，再退回 PATH 里的 adb）
 *   PORT      监听端口（默认 8787）
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PUBLIC_DIR = path.join(__dirname, 'public');
const PORT = parseInt(process.env.PORT || '8787', 10);

function resolveAdb() {
  if (process.env.ADB_BIN) return process.env.ADB_BIN;
  const candidates = [
    'E:/pl_tools/platform-tools/adb.exe',
    'E:/pl_tools/sdk/platform-tools/adb.exe',
    'C:/Users/Administrator/AppData/Local/Android/Sdk/platform-tools/adb.exe',
    '/usr/bin/adb',
    '/opt/android-sdk/platform-tools/adb',
  ];
  for (const c of candidates) {
    if (fs.existsSync(c)) return c;
  }
  return 'adb';
}

const ADB = resolveAdb();

// 当前选中设备（无线调试 connect 后写入；之后自动复用，掉线再回退到首台在线设备）
let currentDevice = null;

// ---------------------------------------------------------------------------
// adb 封装
// ---------------------------------------------------------------------------

function runAdb(args, timeout = 20000) {
  return new Promise((resolve) => {
    let proc;
    try {
      proc = spawn(ADB, args, { windowsHide: true });
    } catch (e) {
      resolve({ ok: false, out: '', err: e.message });
      return;
    }
    let out = '';
    let err = '';
    const timer = setTimeout(() => {
      try { proc.kill('SIGKILL'); } catch (_) { /* noop */ }
      resolve({ ok: false, out, err: 'timeout', timedOut: true });
    }, timeout);
    proc.stdout.on('data', (d) => { out += d.toString(); });
    proc.stderr.on('data', (d) => { err += d.toString(); });
    proc.on('close', (code) => {
      clearTimeout(timer);
      resolve({ ok: code === 0, out, err });
    });
    proc.on('error', (e) => {
      clearTimeout(timer);
      resolve({ ok: false, out, err: e.message });
    });
  });
}

function withDevice(device, ...rest) {
  return device ? ['-s', device, ...rest] : [...rest];
}

async function listDevices() {
  const r = await runAdb(['devices']);
  if (!r.ok) return { ok: false, err: r.err };
  const lines = r.out.split('\n').slice(1).map((l) => l.trim()).filter(Boolean);
  const devices = lines
    .filter((l) => l.endsWith('\tdevice'))
    .map((l) => l.split('\t')[0].trim());
  return { ok: true, devices };
}

async function ensureDevice() {
  if (currentDevice) {
    const d = await listDevices();
    if (d.ok && d.devices.includes(currentDevice)) return currentDevice;
  }
  const d = await listDevices();
  if (d.ok && d.devices.length) {
    currentDevice = d.devices[0];
    return currentDevice;
  }
  return null;
}

// ---------------------------------------------------------------------------
// 数据收集
// ---------------------------------------------------------------------------

async function getProp(device, key) {
  const r = await runAdb(withDevice(device, 'shell', `getprop ${key}`), 8000);
  return r.ok ? r.out.trim() : '';
}

async function getOverview(device) {
  const keys = [
    'ro.product.model',
    'ro.product.brand',
    'ro.product.manufacturer',
    'ro.build.version.release',
    'ro.build.version.sdk',
    'ro.build.version.security_patch',
    'ro.bootloader',
    'ro.build.fingerprint',
  ];
  const [model, brand, manufacturer, release, sdk, patch, bootloader, fingerprint] =
    await Promise.all(keys.map((k) => getProp(device, k)));

  const kernel = await runAdb(withDevice(device, 'shell', 'uname -a'), 8000)
    .then((r) => (r.ok ? r.out.trim() : ''));

  // root 检测：su -c id 含 uid=0 即已 root
  const su = await runAdb(withDevice(device, 'shell', 'su -c id'), 8000);
  const rooted = su.ok && /uid=0/.test(su.out);

  // SELinux
  const se = await runAdb(withDevice(device, 'shell', 'getenforce'), 5000)
    .then((r) => (r.ok ? r.out.trim() : 'unknown'));

  // 管理器探测
  const managers = await detectManagers(device);

  return {
    model: model || '未知',
    brand: brand || manufacturer || '未知',
    release: release || '未知',
    sdk: sdk || '未知',
    patch: patch || '未知',
    bootloader: bootloader || '未知',
    fingerprint: fingerprint || '未知',
    kernel: kernel || '未知',
    rooted,
    selinux: se,
    managers,
    serial: device,
  };
}

async function detectManagers(device) {
  const checks = [
    ['KernelSU', '/data/adb/ksu'],
    ['SukiSU', '/data/adb/ksu'],
    ['Magisk', '/data/adb/magisk'],
    ['APatch', '/data/adb/apatch'],
  ];
  const present = [];
  for (const [name, p] of checks) {
    const r = await runAdb(withDevice(device, 'shell', `ls -d ${p} 2>/dev/null`), 6000);
    if (r.ok && r.out.trim().length) present.push(name);
  }
  return present;
}

async function getApps(device, type, query) {
  const flag = type === 'third' ? '-3' : type === 'system' ? '-s' : '';
  const r = await runAdb(withDevice(device, 'shell', `pm list packages ${flag} -f`), 30000);
  if (!r.ok) return { ok: false, err: r.err };
  let pkgs = r.out
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.startsWith('package:'))
    .map((l) => {
      // 形如 package:/data/app/xxx/yyy/base.apk=com.pkg.name
      const eq = l.indexOf('=');
      const path = eq > 8 ? l.slice(8, eq) : '';
      const pkg = eq > 8 ? l.slice(eq + 1) : l.slice(8);
      return { pkg, path };
    });
  if (query) {
    const q = query.toLowerCase();
    pkgs = pkgs.filter((p) => p.pkg.toLowerCase().includes(q));
  }
  pkgs.sort((a, b) => a.pkg.localeCompare(b.pkg));
  return { ok: true, total: pkgs.length, apps: pkgs.slice(0, 800) };
}

async function getDeviceProps(device) {
  const r = await runAdb(withDevice(device, 'shell', 'getprop'), 15000);
  if (!r.ok) return { ok: false, err: r.err };
  const props = [];
  const re = /\[([^\]]+)\]:\s*\[([^\]]*)\]/g;
  let m;
  while ((m = re.exec(r.out)) !== null) {
    props.push({ key: m[1], value: m[2] });
  }
  return { ok: true, props };
}

async function runShell(device, cmd) {
  const r = await runAdb(withDevice(device, 'shell', cmd), 30000);
  return { ok: r.ok, out: r.out, err: r.err };
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
};

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => { data += c; if (data.length > 1e6) req.destroy(); });
    req.on('end', () => {
      try { resolve(data ? JSON.parse(data) : {}); }
      catch (_) { resolve({}); }
    });
  });
}

async function handleApi(req, res, url) {
  const pathname = url.pathname;
  const method = req.method;

  if (pathname === '/api/state' && method === 'GET') {
    const dev = await ensureDevice();
    const dl = await listDevices();
    return sendJson(res, 200, {
      adb: ADB,
      adbOk: dl.ok,
      adbErr: dl.ok ? null : dl.err,
      device: dev,
      devices: dl.ok ? dl.devices : [],
    });
  }

  if (pathname === '/api/connect' && method === 'POST') {
    const body = await readBody(req);
    const host = String(body.host || '').trim();
    const port = String(body.port || '').trim();
    if (!/^[\w.\-]+$/.test(host) || !/^\d{1,5}$/.test(port)) {
      return sendJson(res, 400, { ok: false, err: 'IP/端口格式不合法' });
    }
    const target = `${host}:${port}`;
    const r = await runAdb(['connect', target], 20000);
    const dl = await listDevices();
    if (dl.ok && dl.devices.includes(target)) currentDevice = target;
    return sendJson(res, 200, {
      ok: dl.ok && dl.devices.includes(target),
      out: r.out,
      err: r.err,
      device: currentDevice,
      devices: dl.ok ? dl.devices : [],
    });
  }

  if (pathname === '/api/pair' && method === 'POST') {
    const body = await readBody(req);
    const host = String(body.host || '').trim();
    const port = String(body.port || '').trim();
    const code = String(body.code || '').trim();
    if (!/^[\w.\-]+$/.test(host) || !/^\d{1,5}$/.test(port) || !/^\d{6}$/.test(code)) {
      return sendJson(res, 400, { ok: false, err: '配对需 主机 + 端口 + 6 位配对码' });
    }
    const r = await runAdb(['pair', `${host}:${port}`, code], 20000);
    return sendJson(res, 200, { ok: r.ok, out: r.out, err: r.err });
  }

  if (pathname === '/api/overview' && method === 'GET') {
    const dev = await ensureDevice();
    if (!dev) return sendJson(res, 200, { ok: false, err: '未连接设备，请先在「连接」面板用无线调试连上手机' });
    const ov = await getOverview(dev);
    return sendJson(res, 200, { ok: true, ...ov });
  }

  if (pathname === '/api/apps' && method === 'GET') {
    const dev = await ensureDevice();
    if (!dev) return sendJson(res, 200, { ok: false, err: '未连接设备' });
    const type = url.searchParams.get('type') || 'all';
    const q = url.searchParams.get('q') || '';
    const r = await getApps(dev, type, q);
    return sendJson(res, 200, r);
  }

  if (pathname === '/api/root' && method === 'GET') {
    const dev = await ensureDevice();
    if (!dev) return sendJson(res, 200, { ok: false, err: '未连接设备' });
    const ov = await getOverview(dev);
    return sendJson(res, 200, {
      ok: true,
      rooted: ov.rooted,
      selinux: ov.selinux,
      managers: ov.managers,
      kernel: ov.kernel,
      serial: dev,
    });
  }

  if (pathname === '/api/device' && method === 'GET') {
    const dev = await ensureDevice();
    if (!dev) return sendJson(res, 200, { ok: false, err: '未连接设备' });
    const r = await getDeviceProps(dev);
    return sendJson(res, 200, r);
  }

  if (pathname === '/api/shell' && method === 'POST') {
    const dev = await ensureDevice();
    if (!dev) return sendJson(res, 200, { ok: false, err: '未连接设备' });
    const body = await readBody(req);
    const cmd = String(body.cmd || '').trim();
    if (!cmd) return sendJson(res, 400, { ok: false, err: '命令为空' });
    const r = await runShell(dev, cmd);
    return sendJson(res, 200, r);
  }

  return sendJson(res, 404, { ok: false, err: 'unknown api' });
}

function serveStatic(req, res, url) {
  let urlPath = decodeURIComponent(url.pathname);
  if (urlPath === '/') urlPath = '/index.html';
  const filePath = path.normalize(path.join(PUBLIC_DIR, urlPath));
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403); res.end('forbidden'); return;
  }
  fs.readFile(filePath, (e, data) => {
    if (e) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(filePath)] || 'application/octet-stream' });
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (url.pathname.startsWith('/api/')) {
    handleApi(req, res, url).catch((e) => sendJson(res, 500, { ok: false, err: e.message }));
    return;
  }
  serveStatic(req, res, url);
});

server.listen(PORT, () => {
  console.log(`PaperLite 电脑网页版已启动: http://localhost:${PORT}`);
  console.log(`ADB 路径: ${ADB}`);
  if (ADB === 'adb') console.log('提示: 未在常见路径找到 adb，将依赖系统 PATH 里的 adb。');
});
