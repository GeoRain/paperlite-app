#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PaperLite PC —— 电脑端辅助软件（Windows / macOS / Linux）。

它只做一件事：把 adb 包一层能点的界面。手机上的 PaperLite 负责在设备本地
提权、装应用、执行命令；电脑端负责「坐在电脑前更顺手」的那部分：
看设备、无线配对、拖个包进来装、开终端、看截图、拉日志。

设计上的三条硬约束：
1. **只用标准库**。不装 pip 包、不用 Electron、不要编译产物，
   拷过去有 Python 3.9+ 就能跑。
2. **只监听 127.0.0.1**。这是本机工具，不对外开端口，不联网上报。
3. **不猜 adb 的位置猜到底**。按 环境变量 → 同目录 platform-tools →
   PATH → 常见 SDK 安装路径 依次找，找不到就在界面里明确提示怎么装，
   而不是静默失败。

启动后会自动用 Edge / Chrome 的「应用窗口」打开界面（没有就退回默认浏览器），
关掉那个窗口就是关掉工具；也可以在界面里点「退出」。
"""

from __future__ import annotations

import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

APP_NAME = "PaperLite PC"
APP_VERSION = "1.0"
ROOT = Path(__file__).resolve().parent
UI_DIR = ROOT / "ui"
UPLOAD_DIR = Path(tempfile.gettempdir()) / "paperlite_pc_uploads"

DEFAULT_PORT = 17321
PORT_TRIES = 12

# adb 单条命令的默认超时。安装一个大包可能很慢，单独放宽。
TIMEOUT_FAST = 20
TIMEOUT_NORMAL = 60
TIMEOUT_INSTALL = 600


# --------------------------------------------------------------------------
# adb 定位
# --------------------------------------------------------------------------

def _candidates() -> list[Path]:
    exe = "adb.exe" if os.name == "nt" else "adb"
    out: list[Path] = []

    env = os.environ.get("PAPERLITE_ADB")
    if env:
        out.append(Path(env))

    out.append(ROOT / "platform-tools" / exe)
    out.append(ROOT / "adb" / exe)

    which = shutil.which("adb")
    if which:
        out.append(Path(which))

    home = Path.home()
    for base in (
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        home / "AppData" / "Local" / "Android" / "Sdk",
        home / "Library" / "Android" / "sdk",
        home / "Android" / "Sdk",
    ):
        if base:
            out.append(Path(base) / "platform-tools" / exe)

    # 本机把 SDK 放在非标准盘符时也能兜住（E:/pl_tools/sdk 之类的布局）
    # 注意盘符必须带斜杠：Path("E:")/"x" 得到的是「E 盘当前目录下的 x」，不是绝对路径
    for drive in ("C:/", "D:/", "E:/", "F:/"):
        for sub in ("pl_tools/sdk", "Android/Sdk", "android-sdk"):
            out.append(Path(drive + sub) / "platform-tools" / exe)
    return out


def find_adb() -> Path | None:
    for p in _candidates():
        try:
            if p and p.is_file():
                return p
        except OSError:
            continue
    return None


ADB = find_adb()


def adb(*args: str, timeout: int = TIMEOUT_NORMAL) -> dict:
    """跑一条 adb 命令，永远返回 dict，不抛异常。"""
    if ADB is None:
        return {"ok": False, "out": "", "err": "没有找到 adb，请先安装 platform-tools 或把它放在 desktop/platform-tools 下"}
    cmd = [str(ADB), *args]
    try:
        p = subprocess.run(
            cmd,
            capture_output=True,
            timeout=timeout,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0,
        )
        out = p.stdout.decode("utf-8", "replace").strip()
        err = p.stderr.decode("utf-8", "replace").strip()
        return {"ok": p.returncode == 0, "out": out, "err": err, "code": p.returncode}
    except subprocess.TimeoutExpired:
        return {"ok": False, "out": "", "err": f"命令超时（{timeout}s）：adb {' '.join(args)}"}
    except Exception as exc:  # noqa: BLE001 - 这里必须兜住一切，界面不能白屏
        return {"ok": False, "out": "", "err": f"{type(exc).__name__}: {exc}"}


# --------------------------------------------------------------------------
# 设备
# --------------------------------------------------------------------------

def list_devices() -> list[dict]:
    r = adb("devices", "-l", timeout=TIMEOUT_FAST)
    devices: list[dict] = []
    if not r["ok"] and not r["out"]:
        return devices
    for line in r["out"].splitlines()[1:]:
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        parts = line.split()
        serial = parts[0]
        state = parts[1] if len(parts) > 1 else "unknown"
        extra = {}
        for token in parts[2:]:
            if ":" in token:
                k, v = token.split(":", 1)
                extra[k] = v
        devices.append(
            {
                "serial": serial,
                "state": state,
                "online": state == "device",
                "model": extra.get("model", "").replace("_", " "),
                "product": extra.get("product", ""),
                "device": extra.get("device", ""),
                "transport": extra.get("transport_id", ""),
                "wireless": ":" in serial,
            }
        )
    return devices


def pick_serial(raw: str | None) -> str:
    if raw:
        return raw
    for d in list_devices():
        if d["online"]:
            return d["serial"]
    return ""


def prop(serial: str, key: str) -> str:
    r = adb("-s", serial, "shell", "getprop", key, timeout=TIMEOUT_FAST)
    return r["out"].strip()


def device_detail(serial: str) -> dict:
    if not serial:
        return {}
    return {
        "serial": serial,
        "brand": prop(serial, "ro.product.brand"),
        "model": prop(serial, "ro.product.model"),
        "android": prop(serial, "ro.build.version.release"),
        "sdk": prop(serial, "ro.build.version.sdk"),
        "abi": prop(serial, "ro.product.cpu.abi"),
        "build": prop(serial, "ro.build.display.id"),
        "root": prop(serial, "ro.build.type") == "eng"
        or adb("-s", serial, "shell", "id", timeout=TIMEOUT_FAST)["out"].find("uid=0") >= 0,
    }


# --------------------------------------------------------------------------
# 上传
# --------------------------------------------------------------------------

def save_upload(name: str, data: bytes) -> Path:
    UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
    safe = re.sub(r"[^\w.\-]+", "_", Path(name).name) or "upload.bin"
    dest = UPLOAD_DIR / f"{int(time.time())}_{safe}"
    dest.write_bytes(data)
    return dest


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = f"PaperLitePC/{APP_VERSION}"

    # ---- 基础设施 ----

    def log_message(self, fmt, *args):  # 静音：控制台只留启动信息
        pass

    def _send(self, code: int, body: bytes, ctype: str = "application/json; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _json(self, obj, code: int = 200):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"))

    def _body(self) -> dict:
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:  # noqa: BLE001
            return {}

    def _query(self) -> dict:
        parsed = urllib.parse.urlparse(self.path)
        return {k: v[0] for k, v in urllib.parse.parse_qs(parsed.query).items()}

    def _path(self) -> str:
        return urllib.parse.urlparse(self.path).path

    # ---- GET ----

    def do_GET(self):  # noqa: N802
        path = self._path()

        if path in ("/", "/index.html"):
            return self._serve_ui("index.html")
        if path.startswith("/ui/"):
            return self._serve_ui(path[4:])
        if path == "/api/state":
            return self._json(self.state())
        if path == "/api/screencap":
            return self._screencap(self._query().get("serial"))
        if path == "/api/apps":
            return self._apps(self._query().get("serial"))
        if path == "/api/logcat":
            return self._logcat(self._query().get("serial"), self._query().get("lines", "300"))
        if path == "/api/ping":
            return self._json({"ok": True, "app": APP_NAME, "version": APP_VERSION})
        return self._json({"ok": False, "err": "not found"}, 404)

    def _serve_ui(self, rel: str):
        target = (UI_DIR / rel).resolve()
        try:
            target.relative_to(UI_DIR.resolve())
        except ValueError:
            return self._json({"ok": False, "err": "bad path"}, 403)
        if not target.is_file():
            return self._json({"ok": False, "err": f"missing {rel}"}, 404)
        ctype = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype == "application/javascript":
            ctype += "; charset=utf-8"
        self._send(200, target.read_bytes(), ctype)

    # ---- POST ----

    def do_POST(self):  # noqa: N802
        path = self._path()

        if path == "/api/upload":
            name = urllib.parse.unquote(self.headers.get("X-File-Name") or "upload.bin")
            try:
                n = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                n = 0
            data = self.rfile.read(n) if n > 0 else b""
            dest = save_upload(name, data)
            return self._json({"ok": True, "path": str(dest), "name": dest.name, "size": len(data)})

        body = self._body()
        serial = pick_serial(body.get("serial"))

        if path == "/api/connect":
            host = (body.get("host") or "").strip()
            if not host:
                return self._json({"ok": False, "err": "请填 IP:端口"})
            if ":" not in host:
                host += ":5555"
            r = adb("connect", host, timeout=TIMEOUT_FAST)
            return self._json({"ok": r["ok"], "out": r["out"], "err": r["err"], "host": host})

        if path == "/api/disconnect":
            target = (body.get("host") or serial or "").strip()
            r = adb("disconnect", target, timeout=TIMEOUT_FAST)
            return self._json({"ok": r["ok"], "out": r["out"] or "已断开", "err": r["err"]})

        if path == "/api/pair":
            host = (body.get("host") or "").strip()
            code = (body.get("code") or "").strip()
            if not host or not code:
                return self._json({"ok": False, "err": "配对需要 IP:端口 和 6 位配对码"})
            r = adb("pair", host, code, timeout=TIMEOUT_NORMAL)
            return self._json({"ok": r["ok"], "out": r["out"], "err": r["err"]})

        if path == "/api/mdns":
            r = adb("mdns", "services", timeout=TIMEOUT_FAST)
            return self._json({"ok": r["ok"], "out": r["out"], "err": r["err"]})

        if path == "/api/install":
            apk = (body.get("path") or "").strip()
            if not apk or not Path(apk).is_file():
                return self._json({"ok": False, "err": "还没选安装包"})
            if not serial:
                return self._json({"ok": False, "err": "没有在线的设备"})
            args = ["-s", serial, "install", "-r"]
            if body.get("downgrade"):
                args.append("-d")
            if body.get("grant"):
                args.append("-g")
            r = adb(*args, apk, timeout=TIMEOUT_INSTALL)
            return self._json(
                {
                    "ok": r["ok"] and "Success" in r["out"],
                    "out": r["out"] or r["err"],
                    "err": r["err"] if "Success" not in r["out"] else "",
                }
            )

        if path == "/api/uninstall":
            pkg = (body.get("pkg") or "").strip()
            if not pkg:
                return self._json({"ok": False, "err": "缺少包名"})
            if not serial:
                return self._json({"ok": False, "err": "没有在线的设备"})
            r = adb("-s", serial, "uninstall", pkg, timeout=TIMEOUT_NORMAL)
            return self._json({"ok": r["ok"], "out": r["out"], "err": r["err"]})

        if path == "/api/shell":
            cmd = (body.get("cmd") or "").strip()
            if not cmd:
                return self._json({"ok": False, "err": "命令为空"})
            if not serial:
                return self._json({"ok": False, "err": "没有在线的设备"})
            r = adb("-s", serial, "shell", cmd, timeout=TIMEOUT_NORMAL)
            return self._json({"ok": r["ok"], "out": r["out"], "err": r["err"]})

        if path == "/api/push":
            local = (body.get("path") or "").strip()
            remote = (body.get("remote") or "/sdcard/Download/").strip()
            if not local or not Path(local).is_file():
                return self._json({"ok": False, "err": "还没选文件"})
            if not serial:
                return self._json({"ok": False, "err": "没有在线的设备"})
            r = adb("-s", serial, "push", local, remote, timeout=TIMEOUT_INSTALL)
            return self._json({"ok": r["ok"], "out": r["out"], "err": r["err"]})

        if path == "/api/wake":
            if not serial:
                return self._json({"ok": False, "err": "没有在线的设备"})
            adb("-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP", timeout=TIMEOUT_FAST)
            r = adb("-s", serial, "shell", "wm", "size", timeout=TIMEOUT_FAST)
            return self._json({"ok": True, "out": r["out"]})

        if path == "/api/quit":
            self._json({"ok": True})
            threading.Timer(0.3, lambda: os._exit(0)).start()
            return

        return self._json({"ok": False, "err": "not found"}, 404)

    # ---- 具体数据 ----

    def state(self) -> dict:
        adb_ok = ADB is not None
        devices = list_devices() if adb_ok else []
        version = ""
        if adb_ok:
            v = adb("version", timeout=TIMEOUT_FAST)
            first = (v["out"] or v["err"]).splitlines()
            version = first[0].strip() if first else ""
        return {
            "ok": True,
            "app": APP_NAME,
            "version": APP_VERSION,
            "adb": str(ADB) if ADB else "",
            "adbVersion": version,
            "devices": devices,
            "serial": pick_serial(None),
            "platform": sys.platform,
            "filePicker": True,  # 电脑端可以浏览任意文件，手机上只能走 SAF
        }

    def _screencap(self, serial_raw: str | None):
        serial = pick_serial(serial_raw)
        if not serial:
            return self._json({"ok": False, "err": "没有在线的设备"}, 400)
        if ADB is None:
            return self._json({"ok": False, "err": "没有找到 adb"}, 400)
        try:
            p = subprocess.run(
                [str(ADB), "-s", serial, "exec-out", "screencap", "-p"],
                capture_output=True,
                timeout=TIMEOUT_NORMAL,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0,
            )
        except Exception as exc:  # noqa: BLE001
            return self._json({"ok": False, "err": str(exc)}, 500)
        data = p.stdout
        if not data.startswith(b"\x89PNG"):
            return self._json({"ok": False, "err": p.stderr.decode("utf-8", "replace")[:400]}, 500)
        self._send(200, data, "image/png")

    def _apps(self, serial_raw: str | None):
        serial = pick_serial(serial_raw)
        if not serial:
            return self._json({"ok": False, "err": "没有在线的设备"})
        r = adb("-s", serial, "shell", "pm", "list", "packages", "-3", timeout=TIMEOUT_NORMAL)
        pkgs = [line.split(":", 1)[1] for line in r["out"].splitlines() if line.startswith("package:")]
        return self._json({"ok": r["ok"], "packages": sorted(pkgs), "err": r["err"]})

    def _logcat(self, serial_raw: str | None, lines: str):
        serial = pick_serial(serial_raw)
        if not serial:
            return self._json({"ok": False, "err": "没有在线的设备"})
        try:
            n = max(20, min(2000, int(lines)))
        except ValueError:
            n = 300
        r = adb("-s", serial, "logcat", "-d", "-t", str(n), timeout=TIMEOUT_NORMAL)
        return self._json({"ok": r["ok"], "out": r["out"], "err": r["err"]})


# --------------------------------------------------------------------------
# 启动
# --------------------------------------------------------------------------

def pick_port(start: int) -> int:
    for i in range(PORT_TRIES):
        port = start + i
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(("127.0.0.1", port))
                return port
            except OSError:
                continue
    raise SystemExit("端口都被占用了，请关掉别的实例再试")


def open_window(url: str) -> None:
    """优先用 Chrome / Edge 的应用窗口：没有地址栏，像个桌面软件。"""
    if os.name == "nt":
        candidates = [
            Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Microsoft/Edge/Application/msedge.exe",
            Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Microsoft/Edge/Application/msedge.exe",
            Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Google/Chrome/Application/chrome.exe",
            Path(os.environ.get("LOCALAPPDATA", "")) / "Google/Chrome/Application/chrome.exe",
        ]
        profile = Path(tempfile.gettempdir()) / "paperlite_pc_profile"
        for exe in candidates:
            if exe.is_file():
                try:
                    subprocess.Popen(
                        [
                            str(exe),
                            f"--app={url}",
                            "--window-size=1240,860",
                            f"--user-data-dir={profile}",
                            "--no-first-run",
                            "--no-default-browser-check",
                        ],
                        close_fds=True,
                    )
                    return
                except OSError:
                    continue
    webbrowser.open(url)


def main() -> None:
    port = pick_port(int(os.environ.get("PAPERLITE_PC_PORT", DEFAULT_PORT)))
    url = f"http://127.0.0.1:{port}/"
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.daemon_threads = True

    print(f"{APP_NAME} {APP_VERSION}")
    print(f"界面地址：{url}")
    print(f"adb：{ADB if ADB else '未找到（界面里会给出安装提示）'}")
    print("关闭这个窗口或点界面里的「退出」即可结束。")

    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    if os.environ.get("PAPERLITE_PC_NO_BROWSER") != "1":
        threading.Timer(0.4, lambda: open_window(url)).start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
