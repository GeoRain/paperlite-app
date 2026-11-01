package com.codefox.paperlite.core.channel

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * 自实现的 ADB 客户端（无线调试 + 本地回环共用一个实现）。
 *
 * 协议要点（AOSP `system/core/adb/protocol.txt`）：
 * - 24 字节小端包头：command(4) arg0(4) arg1(4) data_length(4) data_crc32(4) magic(4)
 * - magic = command xor 0xFFFFFFFF
 * - 认证：`AUTH`/`TOKEN` 拿 challenge，用 RSA 私钥做 PKCS#1 v1.5 SHA-1 签名回 `AUTH`/`SIGNATURE`
 * - 配对：TLS-PSK（Android 11+ 无线调试），psk 为 6 位配对码派生的原始字节
 *
 * 这里不依赖任何第三方 adb 库，全部手写，便于在无 NDK 的环境下编译与审计。
 */
internal object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x4f4b4159
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541
    const val A_STLS = 0x534c5453

    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 256 * 1024

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val STLS_VERSION = 0x01000000

    fun makeHeader(cmd: Int, arg0: Int, arg1: Int, len: Int): ByteArray {
        val b = ByteArray(24)
        putInt(b, 0, cmd)
        putInt(b, 4, arg0)
        putInt(b, 8, arg1)
        putInt(b, 12, len)
        putInt(b, 16, crc32(ByteArray(0), 0, 0).toInt())
        putInt(b, 20, cmd.inv())
        return b
    }

    fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = (v ushr 8 and 0xff).toByte()
        b[off + 2] = (v ushr 16 and 0xff).toByte()
        b[off + 3] = (v ushr 24 and 0xff).toByte()
    }

    fun readInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private val crcTable = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
            }
            t[n] = c
        }
    }

    fun crc32(data: ByteArray, off: Int, len: Int): Long {
        var c = 0xFFFFFFFFL.toInt()
        for (i in off until off + len) {
            c = crcTable[(c xor data[i].toInt()) and 0xff] xor (c ushr 8)
        }
        return (c.inv().toLong() and 0xFFFFFFFFL)
    }

    /** 把公钥编码成 adb 需要的 "AAAA... user@host" 形式。 */
    fun encodePublicKey(pub: RSAPublicKey, user: String): ByteArray {
        val n = pub.modulus.toByteArray().stripLeadingZero()
        val e = pub.publicExponent.toByteArray().stripLeadingZero()
        val key = ByteArrayOutputStream()
        key.write(int32le(32)) // 内部结构长度占位，稍后修正
        key.write(int32le(n.size))
        key.write(n)
        key.write(int32le(e.size))
        key.write(e)
        val body = key.toByteArray()
        // 第一个 32 位是 n 与 e 的结构长度之和
        val structLen = body.size - 4
        val fixed = ByteArray(body.size)
        System.arraycopy(body, 0, fixed, 0, body.size)
        putInt(fixed, 0, structLen)
        val b64 = android.util.Base64.encodeToString(fixed, android.util.Base64.NO_WRAP)
        return "$b64 $user@paperlite".toByteArray(Charsets.UTF_8)
    }

    private fun ByteArray.stripLeadingZero(): ByteArray =
        if (size > 1 && this[0] == 0.toByte()) copyOfRange(1, size) else this

    private fun int32le(v: Int): ByteArray {
        val b = ByteArray(4)
        putInt(b, 0, v)
        return b
    }

    object KeyGen {
        /** 生成 2048 位 RSA，adb 只支持 RSA 签名校验。 */
        fun generate(): KeyPair {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048)
            return g.generateKeyPair()
        }

        fun loadPrivate(encoded: ByteArray): PrivateKey =
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded))

        /** TOKEN 签名：PKCS#1 v1.5 + SHA-1（不带 DigestInfo 前缀，adb 自己拼）。 */
        fun signToken(token: ByteArray, priv: PrivateKey): ByteArray {
            val sig = Signature.getInstance("NONEwithRSA")
            sig.initSign(priv)
            sig.update(token)
            return sig.sign()
        }

        fun decryptToken(token: ByteArray, priv: PrivateKey): ByteArray? = runCatching {
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(Cipher.DECRYPT_MODE, priv)
            c.doFinal(token)
        }.getOrNull()

        fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/**
 * 单条 ADB 连接：负责握手、认证、打开流、收发数据。
 *
 * 线程模型：所有读写都在调用方的协程线程串行执行，不做内置线程，
 * 以免与 Compose 的协程作用域冲突。
 */
class AdbConnection(
    private val socket: Socket,
    private val keyPair: KeyPair,
) : Closeable {

    private val input: DataInputStream = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream().buffered()

    private var nextLocalId = 1
    private var maxData: Int = AdbProtocol.A_MAXDATA

    /** 已完成握手与认证。 */
    fun connect(deviceName: String = "paperlite"): Boolean {
        val pub = AdbProtocol.encodePublicKey(keyPair.public as RSAPublicKey, "paperlite")
        write(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, pub)

        while (true) {
            val (cmd, a0, _, data) = read()
            when (cmd) {
                AdbProtocol.A_CNXN -> {
                    // banner 里可能带 maxdata
                    if (a0 != 0) maxData = a0.coerceIn(4096, AdbProtocol.A_MAXDATA)
                    return true
                }
                AdbProtocol.A_AUTH -> {
                    when (a0) {
                        AdbProtocol.AUTH_TOKEN -> {
                            val signed = AdbProtocol.KeyGen.signToken(data, keyPair.private)
                            write(AdbProtocol.A_AUTH, AdbProtocol.AUTH_SIGNATURE, 0, signed)
                        }
                        else -> {
                            // 服务器不接受签名，回传公钥等用户点「允许」
                            write(AdbProtocol.A_AUTH, AdbProtocol.AUTH_RSAPUBLICKEY, 0, pub)
                        }
                    }
                }
                AdbProtocol.A_STLS -> return true
                else -> Unit
            }
        }
    }

    /** 打开一条 shell 流并读完全部输出。 */
    fun shell(command: String, timeoutMs: Long = 30_000): ShellResult {
        val localId = nextLocalId++
        val payload = "$command\u0000".toByteArray(Charsets.UTF_8)
        write(AdbProtocol.A_OPEN, localId, 0, payload)

        val out = ByteArrayOutputStream()
        var remoteId = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        val buffer = StringBuilder()

        try {
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    runCatching { write(AdbProtocol.A_CLSE, localId, remoteId, ByteArray(0)) }
                    return ShellResult(command, out.toString("UTF-8"), 124, "命令超时")
                }
                val (cmd, a0, a1, data) = read()
                when (cmd) {
                    AdbProtocol.A_OKAY -> {
                        remoteId = a0
                        buffer.setLength(0)
                        buffer.append("OKAY")
                    }
                    AdbProtocol.A_WRTE -> {
                        remoteId = a0
                        out.write(data)
                        if (data.isNotEmpty()) {
                            // 回 ACK，否则大输出会卡住
                            write(AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0))
                        }
                    }
                    AdbProtocol.A_CLSE -> {
                        remoteId = a0
                        runCatching { write(AdbProtocol.A_CLSE, localId, remoteId, ByteArray(0)) }
                        return ShellResult(command, out.toString("UTF-8"), 0, null)
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            return ShellResult(command, out.toString("UTF-8"), 1, e.message ?: "IO 异常")
        }
    }

    /** 流式安装：返回 (remoteId)，后续用 [writeSync] 推包。 */
    fun openStream(service: String): Int {
        val localId = nextLocalId++
        write(AdbProtocol.A_OPEN, localId, 0, "$service\u0000".toByteArray(Charsets.UTF_8))
        while (true) {
            val (cmd, a0, a1, _) = read()
            if (cmd == AdbProtocol.A_OKAY) {
                lastRemoteId = a1
                return localId
            }
            if (cmd == AdbProtocol.A_CLSE) {
                lastRemoteId = a1
                throw IllegalStateException("服务被拒绝: $service")
            }
        }
    }

    var lastRemoteId: Int = 0
        private set

    fun writeSync(localId: Int, remoteId: Int, data: ByteArray) {
        if (data.isEmpty()) return
        write(AdbProtocol.A_WRTE, localId, remoteId, data)
        // 等 ACK
        var guard = 0
        while (guard++ < 1000) {
            val (cmd, _, _, _) = read()
            if (cmd == AdbProtocol.A_OKAY || cmd == AdbProtocol.A_WRTE) return
            if (cmd == AdbProtocol.A_CLSE) throw IllegalStateException("流被关闭")
        }
    }

    fun closeStream(localId: Int) {
        runCatching { write(AdbProtocol.A_CLSE, localId, lastRemoteId, ByteArray(0)) }
    }

    fun write(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        output.write(AdbProtocol.makeHeader(cmd, a0, a1, data.size))
        if (data.isNotEmpty()) output.write(data)
        output.flush()
    }

    fun read(): Frame {
        val header = ByteArray(24)
        input.readFully(header)
        val cmd = AdbProtocol.readInt(header, 0)
        val a0 = AdbProtocol.readInt(header, 4)
        val a1 = AdbProtocol.readInt(header, 8)
        val len = AdbProtocol.readInt(header, 12)
        val magic = AdbProtocol.readInt(header, 20)
        require(magic == cmd.inv()) { "ADB 包头 magic 校验失败" }
        require(len in 0..AdbProtocol.A_MAXDATA) { "ADB 包长度异常: $len" }
        val data = if (len > 0) ByteArray(len).also { input.readFully(it) } else ByteArray(0)
        return Frame(cmd, a0, a1, data)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    data class Frame(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    companion object {
        fun open(host: String, port: Int, keyPair: KeyPair, timeoutMs: Int = 8000): AdbConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = 30_000
            return AdbConnection(socket, keyPair)
        }
    }
}

/** 一次 shell 调用的结果。 */
data class ShellResult(
    val command: String,
    val output: String,
    val exitCode: Int,
    val error: String?,
) {
    val ok: Boolean get() = error == null && exitCode == 0

    fun lineOf(prefix: String): String? =
        output.lineSequence().firstOrNull { it.trimStart().startsWith(prefix) }
            ?.removePrefix(prefix)?.trim()
}
