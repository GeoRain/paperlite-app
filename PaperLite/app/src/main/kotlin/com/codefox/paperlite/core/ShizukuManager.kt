package com.codefox.paperlite.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shizuku 服务的安装与启动。
 *
 * ## 只做一件事：把官方服务跑起来
 *
 * 旧版这里自己造了「等价身份」「授权名单」「以 Shizuku 身份执行命令」，
 * 但那些本质是 PaperLite 自己的 shell 通道和命令行页，属于重复功能，已删除。
 * Shizuku 真正的价值是它的 **binder 服务**（给第三方应用授权高权限 API），
 * 那件事只能由 Shizuku 服务端自己做，本类只负责把服务端拉起来。
 *
 * ## 启动件从哪来
 *
 * `libshizuku.so` 是官方的**启动器 ELF**（带 `main`），不是 JNI 库。
 * 它吃 `--apk=<Shizuku APK>`，自己设 `CLASSPATH` /
 * `-Dshizuku.library.path=<apk目录>/lib/<abi>`，再
 * `execvp("app_process", ... rikka.shizuku.server.ShizukuService")`。
 *
 * 关键约束：`ShizukuService.main()` 会查自己的 `ApplicationInfo`，
 * 从自身 classes.dex 加载服务端类，找不到就 `System.exit(50)`。
 * 所以**服务端必须安装官方 Shizuku APK**，光投启动器起不来。
 * PaperLite 内置官方 APK（[BundledBinaries.shizukuApk]），
 * 没装时自己静默安装，用户不用去外面找包、也不用先打开它生成 start.sh。
 */
object ShizukuManager {

    /** 官方 Shizuku 包名。 */
    const val PACKAGE = BundledBinaries.SHIZUKU_PACKAGE

    /** 启动器与随行库在设备上的落地目录（adb shell / root 可写）。 */
    private const val REMOTE_DIR = "/data/local/tmp/paperlite_shizuku"
    private const val REMOTE_STARTER = "$REMOTE_DIR/libshizuku.so"

    /** rish 调用方包名：写进官方脚本的 RISH_APPLICATION_ID 占位符。 */
    private const val SHIZUKU_CALLER = "com.codefox.paperlite"

    /** 安装信息。 */
    data class InstallInfo(
        val installed: Boolean = false,
        val versionName: String = "",
        val versionCode: Long = 0,
    )

    /** 运行状态。 */
    data class RunState(
        val running: Boolean = false,
        /** 检出的服务名 / 进程名。 */
        val detail: String = "未检测",
    )

    /** 启动结果。 */
    data class StartResult(
        val success: Boolean,
        val message: String,
        val detail: String? = null,
    )

    // ------------------------------------------------------------------
    // 安装检测
    // ------------------------------------------------------------------

    /** Shizuku 是否装在这台设备上。 */
    fun installInfo(context: Context): InstallInfo = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(PACKAGE, 0)
        InstallInfo(
            installed = true,
            versionName = info.versionName ?: "",
            versionCode = info.longVersionCode,
        )
    }.getOrElse { InstallInfo() }

    // ------------------------------------------------------------------
    // 运行检测
    // ------------------------------------------------------------------

    /**
     * 检测 Shizuku 服务是否在跑。
     *
     * 判据是系统服务列表里有没有 `shizuku` 这个 binder 服务——
     * Shizuku 服务起来后一定会注册它，比看进程更准。
     */
    suspend fun runState(channels: ChannelManager): RunState = withContext(Dispatchers.IO) {
        val r = runCatching {
            channels.execAsShell("service list 2>/dev/null | grep -i shizuku", timeoutMs = 20_000L)
        }.getOrNull()
        if (r == null) {
            return@withContext RunState(false, "需要 shell 通道才能检测")
        }
        val hit = r.output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        if (hit != null) {
            RunState(true, hit)
        } else {
            // 退一步再看进程
            val ps = runCatching {
                channels.execAsShell("ps -A 2>/dev/null | grep -i shizuku", timeoutMs = 20_000L)
            }.getOrNull()
            val proc = ps?.output?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
            if (proc != null) RunState(true, proc) else RunState(false, "服务未运行")
        }
    }

    // ------------------------------------------------------------------
    // 启动
    // ------------------------------------------------------------------

    /**
     * 把 Shizuku 服务拉起来。
     *
     * 顺序：
     * 1. 没装就用内置官方 APK 静默安装（服务端类只存在于它的 classes.dex）；
     * 2. 把内置的启动器 ELF 按 ABI 投放到 [REMOTE_DIR]；
     * 3. `pm path` 拿到 Shizuku 的 base.apk，交给启动器 `--apk=`。
     *
     * 不再依赖官方应用生成的 `start.sh`——那份脚本要用户先手动打开一次
     * Shizuku 应用才会创建，是旧版最大的隐性前置条件。
     */
    suspend fun start(
        context: Context,
        channels: ChannelManager,
        installer: Installer,
    ): StartResult = withContext(Dispatchers.IO) {
        val shellReady = channels.acquireShellIdentity()
        if (!shellReady) {
            return@withContext StartResult(
                false,
                "没有 shell 身份，无法投放启动器。请先在「超级用户」页完成无线调试配对",
            )
        }

        // 1. 确保服务端 APK 已安装
        if (!installInfo(context).installed) {
            val apk = BundledBinaries.shizukuApk(context)
                ?: return@withContext StartResult(false, "缺少内置的 Shizuku APK")
            val outcome = installer.install(apk)
            if (!outcome.success) {
                return@withContext StartResult(
                    false,
                    "内置 Shizuku 安装未成功（${outcome.via}）：${outcome.message}",
                )
            }
        }

        val apkPath = runCatching {
            channels.execAsShell("pm path $PACKAGE 2>/dev/null | head -1", timeoutMs = 20_000L)
        }.getOrNull()?.output?.trim()?.lines()?.firstOrNull { it.startsWith("package:") }
            ?.removePrefix("package:")
        if (apkPath.isNullOrBlank() || !File(apkPath).exists()) {
            return@withContext StartResult(false, "拿不到 Shizuku 的 APK 路径，安装可能还没完成")
        }

        // 2. 投放启动器 + 随行库（rish / adb 客户端）
        val (starterBytes, abi) = BundledBinaries.shizukuStarter(context)
            ?: return@withContext StartResult(
                false,
                "本机架构没有随包内置的 Shizuku 启动器",
            )
        val mkdir = channels.execAsShell("mkdir -p '$REMOTE_DIR/$abi'", timeoutMs = 20_000L)
        if (!mkdir.ok) {
            return@withContext StartResult(false, "创建启动目录失败：${mkdir.error ?: mkdir.output}")
        }
        if (!pushBinary(channels, starterBytes, REMOTE_STARTER)) {
            return@withContext StartResult(false, "启动器投放失败")
        }

        // 3. 官方启动器接管：它会自己拼 CLASSPATH / library.path 并 exec app_process
        val cmd = "'$REMOTE_STARTER' --apk='$apkPath' 2>&1"
        val r = runCatching { channels.execAsShell(cmd, timeoutMs = 90_000L) }.getOrNull()
            ?: return@withContext StartResult(false, "启动器执行超时")
        val output = r.output.trim()
        val ok = r.ok || output.contains("exit with 0", ignoreCase = true) ||
            output.contains("shizuku_server pid", ignoreCase = true)
        return@withContext if (ok) {
            StartResult(
                true,
                "已用内置启动器拉起 Shizuku 服务（$abi），稍候刷新状态",
                output.take(200),
            )
        } else {
            StartResult(
                false,
                "启动器退出：${output.take(200).ifBlank { r.error ?: "无输出" }}",
                output.take(200),
            )
        }
    }

    /**
     * 投放官方 `rish` 终端接入件，供 adb shell / 终端应用使用。
     *
     * `rish` 是 Shizuku 官方给终端应用的客户端：服务跑起来后，
     * `sh rish <命令>` 就能以 Shizuku 身份执行。它和服务端是两回事，
     * 所以单独投放、不塞进启动流程。
     *
     * 两个必须照做的细节（都来自官方脚本本身）：
     * - `rish` 会找同目录的 `rish_shizuku.dex`，两者必须放一起；
     * - Android 14+ 的 app_process **拒绝加载可写的 dex**，所以 dex 要 chmod 400。
     *
     * PaperLite 不自己执行 `rish`（那是命令行页的重复功能），
     * 只把它放到设备上，让用户在终端里用。
     */
    suspend fun stageRish(context: Context, channels: ChannelManager): StartResult =
        withContext(Dispatchers.IO) {
            if (!channels.acquireShellIdentity()) {
                return@withContext StartResult(false, "没有 shell 身份，无法投放 rish")
            }
            val (script, dex) = BundledBinaries.shizukuRish(context)
                ?: return@withContext StartResult(false, "缺少内置的 rish 组件")
            val (_, abi) = BundledBinaries.shizukuStarter(context)
                ?: return@withContext StartResult(false, "本机架构没有随包内置组件")

            val mk = channels.execAsShell("mkdir -p '$REMOTE_DIR/$abi'", timeoutMs = 20_000L)
            if (!mk.ok) return@withContext StartResult(false, "创建目录失败")

            if (!pushBinary(channels, dex, "$REMOTE_DIR/rish_shizuku.dex")) {
                return@withContext StartResult(false, "rish_shizuku.dex 投放失败")
            }
            // 官方脚本要求：去掉写权限，否则 Android 14+ 加载不了
            val chmodDex = channels.execAsShell(
                "chmod 400 '$REMOTE_DIR/rish_shizuku.dex'",
                timeoutMs = 20_000L,
            )
            if (!chmodDex.ok) return@withContext StartResult(false, "dex 权限设置失败")

            // 官方脚本里 RISH_APPLICATION_ID 的默认值是占位符 "PKG"，
            // 注释明确要求替换成调用方的应用 id；不换的话 Shizuku 服务端
            // 收到一个不存在的包名，授权永远过不了。
            val patchedScript = String(script, Charsets.UTF_8)
                .replace("RISH_APPLICATION_ID=\"PKG\"", "RISH_APPLICATION_ID=\"${SHIZUKU_CALLER}\"")
                .toByteArray(Charsets.UTF_8)

            if (!pushBinary(channels, patchedScript, "$REMOTE_DIR/rish")) {
                return@withContext StartResult(false, "rish 脚本投放失败")
            }
            for ((name, bytes) in BundledBinaries.shizukuLibs(context, abi)) {
                if (!pushBinary(channels, bytes, "$REMOTE_DIR/$abi/$name")) {
                    return@withContext StartResult(false, "$name 投放失败")
                }
            }
            StartResult(
                true,
                "已投放：$REMOTE_DIR/rish（在终端里 sh rish id 即可验证）",
                "$REMOTE_DIR/rish",
            )
        }

    /**
     * 把一个二进制文件推到设备上并加上可执行权限。
     *
     * 走 shell 通道，用 base64 分块传输——shell 里直接写二进制会被解释掉。
     * 与 DirtyFrag 的投放是同一套做法，区别只在这里不记逐行日志。
     */
    private suspend fun pushBinary(
        channels: ChannelManager,
        bytes: ByteArray,
        remote: String,
    ): Boolean {
        val chunk = 96 * 1024
        val init = channels.execAsShell(": > '$remote'", timeoutMs = 30_000L)
        if (!init.ok) return false
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunk, bytes.size)
            val b64 = android.util.Base64.encodeToString(
                bytes.copyOfRange(offset, end),
                android.util.Base64.NO_WRAP,
            )
            val r = channels.execAsShell(
                "echo '$b64' | base64 -d >> '$remote'",
                timeoutMs = 60_000L,
            )
            if (!r.ok) return false
            offset = end
        }
        return channels.execAsShell("chmod 755 '$remote'", timeoutMs = 30_000L).ok
    }
}
