package com.codefox.paperlite.core.channel

import java.io.File
import java.io.FileReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 本机 adbd 端口发现。
 *
 * ## 为什么需要它
 *
 * 原版 JC Code V3 有个「本地 ADB」通道：读 `/proc/net/tcp` 找出本机
 * adbd 正在监听的端口，然后用**配对时保存的密钥**直连 adbd，
 * 从而拿到一个 uid=2000 的 shell 会话。
 *
 * 这条通道的价值在于：**不需要开无线调试、不需要连外网**，
 * 只要系统里的 adbd 还在跑就能用。而且 uid=2000 恰好满足
 * `app_process` 安装代理的要求（能 attach system_server）。
 *
 * ## 移植说明
 *
 * 原版用 adbeesh 库 + 反射调用 `AdbClient.connect(host, port)`。
 * 本项目自己实现了 ADB 协议（见 [AdbConnection]），所以这里只需要
 * 把「找端口」这部分照搬过来，连接交给 [AdbConnection]。
 */
object LocalAdb {

    /**
     * 从 `/proc/net/tcp` 与 `/proc/net/tcp6` 里找出所有处于 LISTEN 状态的端口。
     *
     * 原版 `mjFkc.aqHPIZ()` 的做法：
     * 1. 读 `/proc/net/tcp` 和 `/proc/net/tcp6`
     * 2. 每行按空白切分，第 4 列是状态，`0A` 即 LISTEN
     * 3. 第 2 列是 `本地地址:端口`，端口是十六进制
     *
     * @return 监听端口列表（去重）
     */
    fun listeningPorts(): List<Int> {
        val out = LinkedHashSet<Int>()
        for (path in arrayOf("/proc/net/tcp", "/proc/net/tcp6")) {
            collectListening(File(path), out)
        }
        return out.toList()
    }

    private fun collectListening(file: File, out: MutableSet<Int>) {
        var reader: java.io.BufferedReader? = null
        try {
            reader = java.io.BufferedReader(FileReader(file))
            var line = reader.readLine()
            // 第一行是表头，跳过
            while (line != null) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size > 3) {
                    val state = parts[3]
                    if (state == "0A") {
                        // parts[1] 形如 "0100007F:1F90"
                        val local = parts[1]
                        val colon = local.lastIndexOf(':')
                        if (colon > 0) {
                            val hexPort = local.substring(colon + 1)
                            hexPort.toIntOrNull(16)?.let { out += it }
                        }
                    }
                }
                line = reader.readLine()
            }
        } catch (e: Throwable) {
            // /proc 在某些 ROM 上读不到，忽略即可
        } finally {
            runCatching { reader?.close() }
        }
    }

    /**
     * 本机所有可能的回环地址。
     *
     * 原版 `mjFkc.VocHEe()`：先放 `127.0.0.1`，再遍历网卡取所有非回环地址。
     * 之所以要非回环地址，是因为某些设备的 adbd 监听在局域网地址上。
     */
    fun localAddresses(): List<String> {
        val out = ArrayList<String>()
        out += "127.0.0.1"
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val addresses = nif.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    val host = addr.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && !out.contains(host)) {
                        out += host
                    }
                }
            }
        }
        return out
    }

    /**
     * 端口是否可连。
     *
     * 原版 `mjFkc.xROA()`：用 Socket 连一下，超时设 1 秒左右。
     */
    fun canConnect(host: String, port: Int, timeoutMs: Int = 1200): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /**
     * 找出所有可用的 adbd 端口。
     *
     * 依次尝试：
     * 1. `/proc/net/tcp` 里 LISTEN 的端口（这是 adbd 最常见的位置）
     * 2. `getprop service.adb.tcp.port` / `service.adb.tls.port` 的值
     *
     * @return (host, port) 列表，第一个是最可能可用���
     */
    fun discoverAdbEndpoints(): List<Pair<String, Int>> {
        val hosts = localAddresses()
        val out = ArrayList<Pair<String, Int>>()

        // 1. /proc/net/tcp 里的 LISTEN 端口
        for (port in listeningPorts()) {
            // adbd 的默认端口是 5555（tcp）与 5037，但无线调试是随机端口，
            // 这里不做端口白名单过滤——只要能连上且握手成功就是它。
            for (host in hosts) {
                if (canConnect(host, port)) {
                    out += host to port
                    break
                }
            }
        }

        // 2. getprop 里的显式端口
        for (prop in arrayOf("service.adb.tcp.port", "service.adb.tls.port")) {
            val port = readIntProp(prop)
            if (port > 0 && out.none { it.second == port }) {
                for (host in hosts) {
                    if (canConnect(host, port)) {
                        out += host to port
                        break
                    }
                }
            }
        }
        return out
    }

    /** 读一个整数类型的 system property。 */
    private fun readIntProp(key: String): Int = runCatching {
        val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val v = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        v.toIntOrNull() ?: 0
    }.getOrDefault(0)

    /**
     * 试着用给定密钥连上某个 adbd 端点。
     *
     * @return 连上的连接；失败返回 null
     */
    fun tryConnect(
        host: String,
        port: Int,
        keyPair: java.security.KeyPair,
        timeoutMs: Int = 8000,
    ): AdbConnection? = runCatching {
        val conn = AdbConnection.open(host, port, keyPair, timeoutMs)
        if (conn.connect()) conn else null
    }.getOrNull()

    /** 判断一个地址是不是回环地址（调试用）。 */
    fun isLoopback(host: String): Boolean = runCatching {
        InetAddress.getByName(host).isLoopbackAddress
    }.getOrDefault(false)
}
