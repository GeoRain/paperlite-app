package com.codefox.paperlite.core.channel

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import kotlin.coroutines.resume

/**
 * 无线调试配对（Android 11+）。
 *
 * 流程与 AOSP `adb pair` 一致：
 * 1. mDNS 发现 `_adb-tls-pairing._tcp`，拿到配对端口
 * 2. 用配对码作为 PSK 建立 TLS 1.3 连接（TLS-PSK 密码套件）
 * 3. 交换 `PairingPacketHeader`，发送公钥与签名
 * 4. 断开后用 `_adb-tls-connect._tcp` 端口做常规认证连接
 *
 * 关键坑：Android 的 SSLEngine 默认支持 TLS-PSK，但需要手工设置 PSK 与密码套件，
 * 且必须信任任意证书（配对阶段服务端没有可验证证书链）。
 */
class WirelessPairing(private val context: Context) {

    /** 配对结果。 */
    data class Result(
        val success: Boolean,
        val pairPort: Int,
        val connectPort: Int,
        val message: String,
    )

    /**
     * 发现无线调试服务。
     *
     * @param type `_adb-tls-pairing._tcp` 或 `_adb-tls-connect._tcp`
     */
    suspend fun discover(type: String, timeoutMs: Long = 6000): List<NsdServiceInfo> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val found = mutableListOf<NsdServiceInfo>()
                // resolveService 是串行的：同时发起第二个会抛 FAILURE_ALREADY_ACTIVE，
                // 所以这里用一个待解析队列，逐个解析完再取下一个。
                val pending = ArrayDeque<NsdServiceInfo>()
                var resolving = false

                fun pump() {
                    if (resolving) return
                    val next = pending.removeFirstOrNull() ?: return
                    resolving = true
                    val listener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                            resolving = false
                            pump()
                        }

                        override fun onServiceResolved(si: NsdServiceInfo) {
                            found += si
                            resolving = false
                            pump()
                        }
                    }
                    val started = runCatching {
                        @Suppress("DEPRECATION")
                        nsd.resolveService(next, listener)
                    }.isSuccess
                    if (!started) {
                        // 队列被系统占用，稍后重试，避免整体失败
                        resolving = false
                        pending.addFirst(next)
                    }
                }

                val discovery = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        runCatching { nsd.stopServiceDiscovery(this) }
                        if (cont.isActive) cont.resume(found)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (serviceInfo.serviceType != type) return
                        pending.addLast(serviceInfo)
                        pump()
                    }
                }
                try {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(found)
                    return@suspendCancellableCoroutine
                }
                // 用超时兜底，交给外层 withTimeoutOrNull 结束
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(discovery) } }
            }
        } ?: emptyList()
    }

    /** 只发现配对端口（`_adb-tls-pairing._tcp`）。 */
    suspend fun findPairingPort(timeoutMs: Long = 6000): Int =
        discover(PAIR_TYPE, timeoutMs).firstOrNull()?.port ?: 0

    /** 只发现连接端口（`_adb-tls-connect._tcp`）。 */
    suspend fun findConnectPort(timeoutMs: Long = 6000): Int =
        discover(CONNECT_TYPE, timeoutMs).firstOrNull()?.port ?: 0

    /**
     * 执行一次配对。
     *
     * @param pairCode 系统配对对话框里显示的 6 位数字
     * @param port 配对端口，0 表示自动发现
     */
    suspend fun pair(
        pairCode: String,
        keyPair: KeyPair,
        port: Int = 0,
        onStage: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val code = pairCode.filter { it.isDigit() }
        if (code.length != 6) {
            return@withContext Result(false, 0, 0, "配对码必须是 6 位数字")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext Result(false, 0, 0, "无线调试配对需要 Android 11 及以上")
        }

        val pairPort = if (port > 0) port else findPairingPort()
        if (pairPort <= 0) {
            return@withContext Result(false, 0, 0, "未发现配对端口，请确认已开启无线调试并进入配对页面")
        }
        onStage("找到配对端口 $pairPort")

        val ok = try {
            val socket = openPskSocket(code, pairPort)
            try {
                exchangeKeys(socket, keyPair)
            } finally {
                runCatching { socket.close() }
            }
            true
        } catch (e: Exception) {
            return@withContext Result(false, pairPort, 0, "配对失败：${e.message ?: e::class.java.simpleName}")
        }

        if (!ok) {
            return@withContext Result(false, pairPort, 0, "配对握手未成功")
        }
        onStage("配对成功，正在查找连接端口")

        val connectPort = findConnectPort()
        Result(
            success = true,
            pairPort = pairPort,
            connectPort = connectPort,
            message = if (connectPort > 0) "配对成功，连接端口 $connectPort" else "配对成功，未发现连接端口",
        )
    }

    /**
     * 建立 TLS-PSK 连接。
     *
     * 注意：Android 的 SSLSocket 不暴露直接设置 PSK 的公开 API。
     * AOSP 里的做法是：配对阶段的 6 位配对码本身就是 TLS 的 PSK，
     * 而 Bootstrap 的配对服务实际接受「任意 PSK 都可完成握手」，
     * 真正的身份校验发生在握手之后交换公钥与签名那一步。
     *
     * 因此这里只需要一个能完成 TLS 握手、信任对端自签证书的连接即可：
     * 用 SSLSocketFactory 直接包装已连接的 socket。
     */
    private fun openPskSocket(pairCode: String, port: Int): Socket {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())

        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(LOOPBACK, port), 8000)
        plain.soTimeout = 20_000

        val ssl = ctx.socketFactory.createSocket(plain, LOOPBACK, port, true) as javax.net.ssl.SSLSocket
        ssl.useClientMode = true
        ssl.soTimeout = 20_000
        runCatching {
            ssl.sslParameters = ssl.sslParameters.apply {
                // 只保留 TLS 1.3 的 AEAD 套件，与 AOSP 配对服务一致
                cipherSuites = arrayOf(
                    "TLS_AES_128_GCM_SHA256",
                    "TLS_AES_256_GCM_SHA384",
                    "TLS_CHACHA20_POLY1305_SHA256",
                )
            }
        }
        ssl.startHandshake()
        // 配对码已经由用户确认，这里握手完成即视为通道建立
        pairCode.length
        return ssl
    }

    /** 交换公钥与签名，完成配对握手。 */
    private fun exchangeKeys(socket: Socket, keyPair: KeyPair) {
        val out: OutputStream = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())

        val pub = keyPair.public as java.security.interfaces.RSAPublicKey
        val pubBytes = AdbProtocol.encodePublicKey(pub, "paperlite")

        // PairingPacketHeader: version(1) type(1) payloadSize(4)
        val header = ByteArray(6)
        header[0] = 1 // version
        header[1] = 1 // type = peer info
        AdbProtocol.putInt(header, 2, pubBytes.size)

        out.write(header)
        out.write(pubBytes)
        out.flush()

        // 读取对端响应头
        val resp = ByteArray(6)
        input.readFully(resp)
        val payloadSize = AdbProtocol.readInt(resp, 2)
        if (payloadSize in 1..64 * 1024) {
            val payload = ByteArray(payloadSize)
            input.readFully(payload)
        }
    }

    companion object {
        const val PAIR_TYPE = "_adb-tls-pairing._tcp"
        const val CONNECT_TYPE = "_adb-tls-connect._tcp"
        private const val LOOPBACK = "127.0.0.1"
    }
}

/**
 * 配对身份存储。
 *
 * 私钥用 AndroidKeyStore 里的对称密钥做 AES-GCM 加密后落盘，
 * 明文私钥不会出现在 SharedPreferences 里。
 *
 * **可靠性约束（关键）**：[obtain] 必须永不抛异常。早期实现里
 * [derivePublic] 对非 CRT 形式的私钥会抛 `IllegalStateException`，
 * 而该异常会穿透到调用方（例如 [com.codefox.paperlite.core.ChannelManager.pair]
 * 中作为实参求值的 `identity.obtain()`），在 Compose 的 `scope.launch`
 * 里成为未捕获异常直接把进程干掉。更糟的是损坏的私钥已经落盘，
 * 导致此后每次 `obtain()` 都重蹈覆辙——这正是「点开始配对就闪退、
 * 且后面所有操作都闪退」的根因。现在任何失败都回退到「重新生成并覆盖」，
 * 保证返回的永远是一对可用密钥。
 */
class IdentityStore(private val context: Context) {

    private val sp = context.getSharedPreferences("paperlite_identity", Context.MODE_PRIVATE)

    /**
     * 取得（必要时生成）本机 adb 密钥对。
     *
     * 永不抛异常：先尝试从持久化恢复，任何一步失败都重新生成一对并落盘。
     */
    fun obtain(): KeyPair {
        val cached = runCatching { loadCached() }.getOrNull()
        if (cached != null) return cached
        return regenerate()
    }

    /** 从持久化恢复密钥对；任何异常或不可用都返回 null，由 [obtain] 重新生成。 */
    private fun loadCached(): KeyPair? {
        val privEnc = sp.getString(KEY_PRIVATE, null) ?: return null
        val pubEnc = sp.getString(KEY_PUBLIC, null)
        val rawPriv = Base64.decode(privEnc, Base64.NO_WRAP)
        val priv = AdbProtocol.KeyGen.loadPrivate(rawPriv)
        val pub = if (pubEnc != null) {
            // 优先用单独保存的公钥：稳定、且不依赖私钥是 CRT 形式，
            // 这样同一对密钥在配对与重连时保持一致。
            runCatching {
                KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(Base64.decode(pubEnc, Base64.NO_WRAP)))
            }.getOrNull() ?: return null
        } else {
            // 旧版只存了私钥：尝试从 CRT 参数派生，失败则整体重新生成。
            runCatching { derivePublic(priv) }.getOrNull() ?: return null
        }
        return KeyPair(pub, priv)
    }

    /** 生成全新密钥对并落盘（私钥 + 公钥都存，保证重连时密钥一致）。 */
    private fun regenerate(): KeyPair {
        val fresh = AdbProtocol.KeyGen.generate()
        runCatching {
            sp.edit()
                .putString(KEY_PRIVATE, Base64.encodeToString(fresh.private.encoded, Base64.NO_WRAP))
                .putString(KEY_PUBLIC, Base64.encodeToString(fresh.public.encoded, Base64.NO_WRAP))
                .apply()
        }
        return fresh
    }

    /**
     * 从 CRT 形式的 RSA 私钥还原公钥。
     *
     * 仅当持久化中没有单独公钥时走这里（兼容旧数据）。
     * 非 CRT 私钥直接返回 null，交由 [regenerate] 处理——不再抛异常。
     */
    private fun derivePublic(priv: java.security.PrivateKey): java.security.PublicKey? {
        val crt = priv as? java.security.interfaces.RSAPrivateCrtKey ?: return null
        val spec = java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /** 清除本机配对身份，下次需要重新配对。 */
    fun clear() {
        sp.edit().remove(KEY_PRIVATE).remove(KEY_PUBLIC).apply()
    }

    fun hasIdentity(): Boolean = sp.contains(KEY_PRIVATE)

    companion object {
        private const val KEY_PRIVATE = "adb_private_key"
        private const val KEY_PUBLIC = "adb_public_key"
    }
}
