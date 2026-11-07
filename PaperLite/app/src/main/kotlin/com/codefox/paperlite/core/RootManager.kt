package com.codefox.paperlite.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Root 状态读取（只读）。
 *
 * 对齐 SukiSU（KernelSU 分支）的目录结构，用 shell 直接读：
 *
 * ```
 * /data/adb/ksu/.allowlist  授权名单
 * /data/adb/modules/        已生效模块
 * /data/adb/modules_update/ 待生效模块
 * ```
 *
 * 这里**只是观察**：授权与 su 由官方 ksud 负责，PaperLite 不再自己
 * 做一套 root 管理器（旧版 SuManager 已删除）。
 */
object RootManager {

    /** su 的候选路径，和 KernelSU/Magisk 的安装位置对齐。 */
    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/magisk/su",
        "/data/adb/ksud",
    )

    /** 常见的 root 框架判定目录。 */
    private val FRAMEWORKS = listOf(
        "/data/adb/ksu" to "KernelSU 系（SukiSU / KernelSU / KernelSU-Next）",
        "/data/adb/magisk" to "Magisk",
        "/data/adb/ap" to "APatch",
        "/data/adb/su" to "SU 通用实现",
    )

    /** root 状态。 */
    data class Status(
        val available: Boolean = false,
        /** 执行 `id` 的原始输出，例如 `uid=0(root) gid=0(root)`。 */
        val uidLine: String = "",
        /** 命中的 su 路径。 */
        val suPath: String? = null,
        /** 识别到的 root 框架。 */
        val framework: String? = null,
        /** 框架版本（有则填）。 */
        val version: String? = null,
        /** 内核版本。 */
        val kernelVersion: String = "",
        /** SELinux 状态：Enforcing / Permissive / 未知。 */
        val selinux: String = "未知",
        /** Seccomp 状态。 */
        val seccomp: String = "未知",
        /** 设备型号。 */
        val model: String = "",
        /** Build 指纹。 */
        val fingerprint: String = "",
        /** 内核 KMI 版本。 */
        val kmi: String = "",
        /** 说明文字，直接可以显示。 */
        val detail: String = "未取得 root",
    )

    /**
     * 采集设备状态。
     *
     * 有 shell 通道时读内核侧信息（内核版本、SELinux、Seccomp、KMI），
     * 没有时退回本进程能拿到的（Build.*）。
     */
    suspend fun collect(channels: ChannelManager): Status {
        val root = detect(channels)
        val props = runCatching {
            channels.execAsShell(
                "getprop ro.kernel.version; getenforce; " +
                    "grep -c Seccomp /proc/self/status; " +
                    "getprop ro.product.model; getprop ro.build.fingerprint; " +
                    "uname -r",
                timeoutMs = 20_000L,
            )
        }.getOrNull()
        val lines = props?.output?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        fun lineAt(i: Int): String = lines.getOrNull(i).orEmpty()

        val selinux = lineAt(1).takeIf { it.isNotEmpty() } ?: "未知"
        val seccompCount = lineAt(2).toIntOrNull()
        val seccomp = if (seccompCount == null) "未知" else if (seccompCount > 0) "已启用" else "未启用"

        return root.copy(
            kernelVersion = lineAt(6).ifBlank { System.getProperty("os.version", "") },
            selinux = selinux,
            seccomp = seccomp,
            model = lineAt(3).ifBlank { android.os.Build.MODEL },
            fingerprint = lineAt(4).ifBlank { android.os.Build.FINGERPRINT },
            kmi = kmiOf(lineAt(6)),
        )
    }

    /** 从 `uname -r`（形如 5.10.101-android12-9）里取 KMI 段。 */
    private fun kmiOf(kernel: String): String {
        val m = Regex("(\\d+\\.\\d+)-android(\\d+)-(\\d+)").find(kernel) ?: return ""
        return "android${m.groupValues[2]}-${m.groupValues[3]}"
    }

    /** 模块条目。 */
    data class Module(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        /** 是否被 disable 文件停用。 */
        val disabled: Boolean,
        /** 是否处于待生效目录。 */
        val pending: Boolean,
    )

    /**
     * 探测 root 状态。
     *
     * 判定标准是**真的执行一次 `id`**：只看 su 文件在不在会被
     * 「Magisk 装了但没授权」骗过去——文件在，执行却弹授权框或直接拒绝。
     */
    suspend fun detect(channels: ChannelManager): Status = withContext(Dispatchers.IO) {
        val idOut = runCatching {
            channels.execAsShell("id", timeoutMs = 8_000L)
        }.getOrNull()
        val rootLike = idOut?.ok == true &&
            (idOut.output.contains("uid=0") || idOut.output.contains("uid=2000"))

        val suPath = SU_PATHS.firstOrNull { path ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }

        if (!rootLike) {
            return@withContext Status(
                available = false,
                suPath = suPath,
                framework = null,
                detail = if (suPath != null) {
                    "找到 $suPath，但当前身份拿不到 root（可能未授权）"
                } else {
                    "未找到 su，也没有 root 通道"
                },
            )
        }

        val framework = FRAMEWORKS.firstOrNull { (path, _) ->
            runCatching { File(path).exists() }.getOrDefault(false)
        }?.second

        val version = readVersion(channels)

        Status(
            available = true,
            uidLine = idOut?.output?.trim().orEmpty().lines().firstOrNull().orEmpty(),
            suPath = suPath,
            framework = framework,
            version = version,
            detail = buildString {
                append(idOut?.output?.trim()?.lines()?.firstOrNull() ?: "uid=0")
                if (framework != null) append(" · ").append(framework)
                if (version != null) append(" ").append(version)
            },
        )
    }

    /** 读框架版本：su -v / su -V 都试一遍。 */
    private suspend fun readVersion(channels: ChannelManager): String? {
        val out = runCatching {
            channels.execAsShell("su -v 2>/dev/null || su -V 2>/dev/null", timeoutMs = 8_000L)
        }.getOrNull() ?: return null
        val text = out.output.trim()
        if (text.isBlank() || !out.ok) return null
        return text.lines().firstOrNull { it.isNotBlank() }?.trim()
    }

    /**
     * 列出已安装模块。
     *
     * 只在有 root 时才有意义：没 root 读不到 `/data/adb/modules`。
     */
    suspend fun listModules(channels: ChannelManager): List<Module> = withContext(Dispatchers.IO) {
        val modules = ArrayList<Module>()
        readModules(channels, "/data/adb/modules/", pending = false)?.let(modules::addAll)
        readModules(channels, "/data/adb/modules_update/", pending = true)?.let(modules::addAll)
        modules
    }

    private suspend fun readModules(channels: ChannelManager, dir: String, pending: Boolean): List<Module>? {
        val listing = runCatching {
            channels.execAsShell("ls -1 '$dir' 2>/dev/null", timeoutMs = 15_000L)
        }.getOrNull() ?: return null
        if (!listing.ok) return null

        val names = listing.output.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return emptyList()

        val result = ArrayList<Module>(names.size)
        for (name in names) {
            val prop = runCatching {
                channels.execAsShell("cat '$dir$name/module.prop' 2>/dev/null", timeoutMs = 15_000L)
            }.getOrNull() ?: continue
            val kv = HashMap<String, String>()
            prop.output.lineSequence().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) kv[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            if (kv.isEmpty()) continue
            val disabled = runCatching {
                channels.execAsShell("test -f '$dir$name/disable' && echo yes || echo no", timeoutMs = 10_000L)
            }.getOrNull()?.output?.contains("yes") == true

            result += Module(
                id = kv["id"] ?: name,
                name = kv["name"] ?: name,
                version = kv["version"] ?: "",
                author = kv["author"] ?: "",
                description = kv["description"] ?: "",
                disabled = disabled,
                pending = pending,
            )
        }
        return result
    }

    /**
     * 超级用户授权名单概况。
     *
     * KernelSU 的 `.allowlist` 是二进制，这里不硬解格式，
     * 而是给出「文件是否存在 + 条目数 + 前几个 uid」，
     * 需要逐条管理时用下面的 [grant]/[revoke] 走 su 授权流程。
     */
    suspend fun allowlistSummary(channels: ChannelManager): String = withContext(Dispatchers.IO) {
        val probe = runCatching {
            channels.execAsShell(
                "ls -l /data/adb/ksu/.allowlist 2>/dev/null; " +
                    "cat /data/adb/ksu/.allowlist 2>/dev/null | tr -c '[:print:]' '\\n' | grep -c . ",
                timeoutMs = 15_000L,
            )
        }.getOrNull()
        if (probe == null || !probe.ok) {
            return@withContext "读不到授权名单（需要 root）"
        }
        val lines = probe.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val size = lines.firstOrNull { it.contains(".allowlist") }
            ?.split(" ")
            ?.getOrNull(4)
            ?.trim()
        val count = lines.lastOrNull()?.toIntOrNull()
        buildString {
            append("授权名单：")
            append(if (size != null) "${size}B" else "存在")
            if (count != null) append("，可解析条目 $count 条")
        }
    }

    /**
     * 以 root 身份执行一条命令（等价于 SukiSU 里给某个应用授权后的 su 执行）。
     *
     * 走的是 shell 通道而不是硬编码 `su`，
     * 这样在有无线调试会话的设备上也能用。
     */
    suspend fun runAsRoot(channels: ChannelManager, command: String): ExecResult =
        withContext(Dispatchers.IO) {
            channels.execAsShell(command, timeoutMs = 60_000L)
        }

    /**
     * 冻结 / 解冻一个应用。
     *
     * 这正是 SukiSU 与作业帮设备管控共用的底层能力：
     * `pm disable-user` 不需要 root，但在 root 下更稳。
     */
    suspend fun setAppEnabled(
        channels: ChannelManager,
        packageName: String,
        enabled: Boolean,
    ): ExecResult = withContext(Dispatchers.IO) {
        val cmd = if (enabled) {
            "pm enable '$packageName'"
        } else {
            "pm disable-user --user 0 '$packageName'"
        }
        channels.execAsShell(cmd, timeoutMs = 30_000L)
    }
}
