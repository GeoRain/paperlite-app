package com.codefox.paperlite.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.codefox.paperlite.data.InstallAuthorizer
import com.codefox.paperlite.data.InstallErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 安装 / 卸载执行器。
 *
 * 安装链路完全对齐原版 JC Code V3 的三通道顺序：
 * 1. **app_process 安装代理**（原版「安装通道①」）：把本应用的 APK 通过
 *    `CLASSPATH` 挂给 `app_process`，让它在 system 身份下用 PackageInstaller
 *    会话 API 提交安装——这是唯一能做到「无确认框真静默」的路。
 * 2. **本地 pm install**（原版「安装通道②」）：`pm install -r -d`。
 * 3. **无线调试会话推包**（原版「安装通道③」）：推包到 /data/local/tmp 再 pm install。
 * 4. **设备所有者静默会话**：owner 权限下的 PackageInstaller 会话。
 * 5. **系统包安装器**：全部失败时拉起系统界面，让用户手动确认。
 */
class Installer(
    private val context: Context,
    private val channels: ChannelManager,
) {

    data class Outcome(
        val success: Boolean,
        val via: String,
        val message: String,
        val requiresUserAction: Boolean = false,
    )

    /** 安装一个 APK 文件。 */
    suspend fun install(apk: File): Outcome = withContext(Dispatchers.IO) {
        // 整条链路整体兜底：任何一种通道实现炸了都只是安装失败，不是闪退
        runCatching { installInner(apk) }.getOrElse { e ->
            channels.log("安装异常：${e::class.java.simpleName} ${e.message}")
            Outcome(false, "全部", "安装过程出错：${e.message ?: e::class.java.simpleName}")
        }
    }

    private suspend fun installInner(apk: File): Outcome {
        if (!apk.exists()) {
            return Outcome(false, "无", "文件不存在：${apk.absolutePath}")
        }
        if (!apk.name.endsWith(".apk", true)) {
            return Outcome(false, "无", "不是 APK 文件")
        }
        // 校验包结构，避免把网页跳转页当安装包
        val parsed = parseApk(apk)
        if (parsed == null) {
            return Outcome(false, "无", "无法解析安装包，可能已损坏")
        }
        channels.log("安装开始：${parsed.label} ${parsed.packageName} v${parsed.versionName}")

        val notes = ArrayList<String>(4)

        // 安装授权器：决定「以谁的身份执行安装」。
        // 概念与 InstallerX 的 Authorizer 一致，默认 Auto（按优先级依次尝试）。
        val authorizer = channels.settingsRef.installAuthorizer.value
        channels.log("安装：授权器 = ${authorizer.title}")

        // 通道⓪：作业帮 / AIbangOS 系统服务静默安装。
        // 放在最前面是有理由的——这条链路来自 InstallerX 的 Birchua 授权器，
        // 它直接调系统服务 `ZybosSysApiManager.silentInstallApk`，
        // 既不要 root，也不要用户确认，还会顺手解除 PadMS 的安装拦截；
        // 在作业帮设备上，它是唯一能做到「真·无声安装」的路。
        if (authorizer == InstallAuthorizer.Zybos || authorizer == InstallAuthorizer.Auto) {
            if (ZybosApi.isAvailable(context)) {
                val r = ZybosApi.silentInstall(context, apk, parsed.packageName)
                if (r.success) {
                    return Outcome(true, "作业帮系统服务", r.message)
                }
                notes += "作业帮系统服务：${r.message}"
                channels.log("安装：作业帮系统服务未成功 → ${r.message}")
            } else if (authorizer == InstallAuthorizer.Zybos) {
                notes += "作业帮系统服务：设备不支持"
                channels.log("安装：作业帮系统服务：设备不支持")
            }
        }

        // 指定了「只要系统安装器」时直接拉起系统界面，不做任何静默尝试
        if (authorizer == InstallAuthorizer.System) {
            val launched = launchSystemInstaller(apk)
            return if (launched) {
                Outcome(false, "系统包安装器", "已拉起系统安装界面，请手动确认", true)
            } else {
                Outcome(false, "系统包安装器", "无法拉起系统安装界面")
            }
        }

        // 是否真的能拿到 shell 身份，必须**现场探测**，不能只看缓存里的通道状态：
        // 应用重启后 adb 会话是空的，而无线调试端口还记在设置里，
        // 状态却仍停在「不可用」——于是安装直接掉到系统安装器，
        // 表现就是「明明配对过，静默安装却一直失败」。
        val wantShell = authorizer == InstallAuthorizer.Auto ||
            authorizer == InstallAuthorizer.Root ||
            authorizer == InstallAuthorizer.Shell
        val shellReady = if (wantShell) channels.acquireShellIdentity() else false
        if (wantShell && !shellReady) {
            channels.log("安装：当前没有 root / adb shell 身份，静默通道不可用")
        }

        // 目标 APK 放到 shell 读得到的位置（原路径本就可读时直接用原路径）
        val staged = if (shellReady) stageTargetApk(apk) else null
        if (shellReady && staged == null) notes += "目标 APK 无法放到 shell 可读位置"

        // 通道①：pm install。
        // shell(uid 2000) 本身就带 INSTALL_PACKAGES，pm install 不会弹确认框，
        // 这是最稳的一条路，失败原因也最具体（INSTALL_FAILED_* 会原样带出来）。
        //
        // 附加参数来自「安装选项」（对齐 InstallerX 的 config_* 开关）：
        // 允许降级 -d、允许测试包 -t、多用户 --user all、声明安装者 -i。
        val options = channels.settingsRef.installOptions.value
        if (staged != null) {
            val r = channels.execAsShell(
                "pm install${options.pmFlags()} \"$staged\"",
                timeoutMs = 240_000L,
            )
            if (r.output.contains("Success", true)) {
                cleanupStaged(apk, staged)
                if (options.autoDelete) {
                    runCatching { apk.delete() }
                    channels.log("安装：已按选项删除安装包 ${apk.name}")
                }
                return Outcome(true, "pm install", "已通过 pm install 静默安装")
            }
            val why = brief(r)
            notes += "pm install：$why"
            channels.log("安装：pm install 未成功 → $why")
            // 把 INSTALL_FAILED_* 翻译成中文，别让用户盯着英文常量
            InstallErrors.explain(r.output)?.let {
                notes += it
                channels.log("安装：$it")
            }
        }

        // 通道②：app_process 安装代理。
        // 关键点：失败必须继续往下走，而不是直接 return ——
        // 代理在部分 ROM 上会因 SELinux / systemMain 失败，
        // 但同样的包用 pm install 反而能装上。
        if (staged != null) {
            val r = installViaAgent(apk, staged)
            if (r != null && r.success) {
                cleanupStaged(apk, staged)
                return r
            }
            if (r != null) notes += "安装代理：${r.message}"
        }

        cleanupStaged(apk, staged)

        // 通道③：设备所有者静默会话
        if ((authorizer == InstallAuthorizer.Auto || authorizer == InstallAuthorizer.Owner) &&
            channels.states.value[Channel.DeviceOwner]?.available == true
        ) {
            if (silentInstallViaOwner(apk)) {
                return Outcome(true, "设备所有者", "已通过设备所有者静默安装")
            }
            notes += "设备所有者：会话提交失败"
        }

        // 通道④：拉起系统包安装器
        val detail = if (notes.isEmpty()) "" else "（${notes.joinToString("；")}）"
        if (launchSystemInstaller(apk)) {
            return Outcome(
                success = false,
                via = "系统包安装器",
                message = "已拉起系统安装界面，请手动确认$detail",
                requiresUserAction = true,
            )
        }
        return Outcome(false, "全部", "所有安装通道均失败$detail，详见运行日志")
    }

    /** 把失败结果压成一行可读的原因。 */
    private fun brief(r: ExecResult, limit: Int = 160): String {
        val text = r.output.ifBlank { r.error.orEmpty() }
        if (text.isBlank()) return r.error ?: "无输出"
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val key = lines.firstOrNull {
            it.contains("Failure", true) ||
                it.contains("Exception", true) ||
                it.contains("Error", true) ||
                it.contains("denied", true)
        } ?: lines.lastOrNull()
        return (key ?: "无输出").take(limit)
    }

    /** 清掉临时暂存的 APK（原路径不删）。 */
    private suspend fun cleanupStaged(apk: File, staged: String?) {
        if (staged == null || staged == apk.absolutePath) return
        runCatching { channels.execAsShell("rm -f \"$staged\" 2>/dev/null", timeoutMs = 15_000L) }
    }

    /** 安装代理的 dex 是否真的被打进了 assets。 */
    private fun agentPackaged(): Boolean = runCatching {
        context.assets.open("agent/classes.dex").use { it.read() >= 0 }
    }.getOrDefault(false)

    /**
     * 通道①：用 app_process 安装代理完成静默安装。
     *
     * 命令形态与原版 JC Code V3 一致：
     * ```
     * CLASSPATH='<代理 dex 目录>' app_process /system/bin \\
     *     com.codefox.paperlite.core.install.InstallAgent '<目标 APK>' 2>&1
     * ```
     *
     * ## 与原版的差异，以及为什么这样更好
     *
     * 原版的 CLASSPATH 指向**自身 APK**（`applicationInfo.sourceDir`）。
     * 之所以能这么用，是因为原版只有 2 个 dex，安装代理恰好落在主 dex
     * `classes.dex` 里，而 `app_process` 只会装载主 dex。
     *
     * 本项目主 APK 有 11 个 dex（Compose + Miuix 占满了主 dex），
     * 安装代理无论怎么放都进不了主 dex——挂自身 APK 必然
     * `ClassNotFoundException`。
     *
     * 所以这里把安装代理**单独编译成一个 9.5KB 的 dex**
     * （见 `app/src/agent/java/.../InstallAgent.java` 与
     * `app/build.gradle.kts` 里的 `dexAgent` 任务），
     * 打包进 `assets/agent/classes.dex`，运行时推到目标机，
     * CLASSPATH 指向那个目录。
     *
     * 效果与原版完全等价，但 app_process 只加载 9.5KB，
     * 不必背着 47MB 的主包去解析所有 dex，启动快得多，也不容易超时。
     *
     * @return 成功返回 Outcome，失败返回 null（继续走下一通道）
     */
    private suspend fun installViaAgent(apk: File, target: String): Outcome? =
        withContext(Dispatchers.IO) {
            // assets 里没有代理 dex 时这条通道根本跑不起来，
            // 直接给一条明确日志，而不是让用户看到「ClassNotFoundException」这种间接现象
            if (!agentPackaged()) {
                channels.log("安装代理：assets/agent/classes.dex 缺失（打包时没有生成），跳过该通道")
                return@withContext null
            }
            // 代理 dex 要先出现在目标机上（已打包成与原版同构的 zip）
            val agentZip = stageAgentDex() ?: run {
                channels.log("安装代理：代理包推送失败，跳过")
                return@withContext null
            }

            val cmd = buildString {
                append("CLASSPATH='$agentZip' app_process /system/bin ")
                append("com.codefox.paperlite.core.install.InstallAgent ")
                append("'$target' 2>&1")
            }
            channels.log("安装代理：CLASSPATH=$agentZip app_process … InstallAgent '$target'")
            // 必须以 shell 身份执行：app_process 要 attach system_server，
            // 应用自己的 uid 会被 SELinux 拦掉
            val r = channels.execAsShell(cmd, timeoutMs = 120_000L)
            channels.execAsShell("rm -f '$agentZip' 2>/dev/null", timeoutMs = 15_000L)

            val out = r.output
            when {
                out.contains("INSTALL_AGENT_OK") ->
                    Outcome(true, "安装代理", "已通过 app_process 安装代理静默安装")
                out.contains("INSTALL_AGENT_TIMEOUT") ->
                    Outcome(false, "安装代理", "安装代理超时，30 秒内未装上")
                out.contains("INSTALL_AGENT_FAIL") -> {
                    val line = out.lineSequence().firstOrNull { it.contains("INSTALL_AGENT_FAIL") }?.trim()
                    Outcome(false, "安装代理", "安装被系统拒绝：${line ?: ""}")
                }
                out.contains("INSTALL_AGENT_ERROR") -> {
                    val line = out.lineSequence().firstOrNull { it.contains("INSTALL_AGENT_ERROR") }?.trim()
                    Outcome(false, "安装代理", "安装代理报错：${line ?: ""}")
                }
                !r.ok && out.isBlank() -> {
                    channels.log("安装代理：命令无输出（${r.error ?: "退出码异常"}）")
                    Outcome(false, "安装代理", r.error ?: "命令无输出")
                }
                else -> {
                    channels.log("安装代理未返回明确结果：${out.take(200)}")
                    null
                }
            }
        }

    /**
     * 把安装代理推到目标机，返回它在目标机上的**路径**。
     *
     * ## 为什么打成 zip 而不是推一个裸目录
     *
     * 原版 JC Code V3 的命令是：
     * ```
     * CLASSPATH='<自身 APK 路径>' app_process /system/bin com.jc.code.v3.InstallAgent '<apk>'
     * ```
     * 它的 CLASSPATH 指向的是一个 **APK**——也就是「内含 classes.dex 的 zip」。
     * ART 打开 classpath 条目时走的是 `DexFile::Open` → 解 zip → 找 `classes.dex`
     * 这条路径，与「给一个目录、让 ART 自己扫目录」是**两条不同的代码路径**，
     * 后者在部分 ROM 上并不成立。
     *
     * 所以这里严格对齐原版：把代理 dex 打包成一个 zip（entry 名就是
     * `classes.dex`），推到目标机后让 CLASSPATH 指向这个 zip 文件。
     * 这样 ART 走的就是和原版一模一样的那条加载路径。
     *
     * ## 为什么不能 `cat` 一下就完事
     *
     * shell(uid 2000) 在 Android 11+ 读不到 `/data/user/0/<pkg>/cache`，
     * 而 `>` 重定向会先把目标文件建出来——于是 `ls` 照样能看到文件，
     * 实际推过去的是**空文件**，最终表现为 ClassNotFoundException 且极难排查。
     * 所以走已经验证过的 base64 分块推送，并**校验远端字节数**。
     */
    private suspend fun stageAgentDex(): String? {
        val remoteDir = "/data/local/tmp/pl_agent"
        val remoteZip = "$remoteDir/agent.zip"
        // 先在本地把 dex 打成 zip（等价于一个极小的 APK）
        val local = writeAgentAssetAsZip() ?: return null
        val size = local.length()
        if (size <= 0L) {
            channels.log("安装代理：打包出来的 agent.zip 为空")
            return null
        }
        val mk = channels.execAsShell(
            "mkdir -p $remoteDir && : > $remoteZip && echo ok",
            timeoutMs = 20_000L,
        )
        if (!mk.ok || !mk.output.contains("ok")) {
            channels.log("安装代理：无法创建远程目录（${mk.output.take(60)}）")
            return null
        }
        if (!pushBinary(local, remoteZip)) {
            channels.log("安装代理：zip 推送失败")
            return null
        }
        // 校验远端大小，避免出现「推了空文件还以为成功」
        val remoteSize = channels.execAsShell("wc -c < $remoteZip", timeoutMs = 20_000L)
            .output.trim().toLongOrNull() ?: -1L
        if (remoteSize != size) {
            channels.log("安装代理：zip 大小校验失败（本地 $size / 远端 $remoteSize）")
            return null
        }
        return remoteZip
    }

    /**
     * 用 base64 分块把二进制文件推到目标机。
     *
     * 与 [pushFile] 同源，但不做「先探目录可写」那一步（调用方已经建好目录），
     * 并且返回真实推送结果而不是布尔，方便上层区分「没推」和「推了但不对」。
     */
    private suspend fun pushBinary(local: File, remote: String): Boolean =
        withContext(Dispatchers.IO) {
            val chunk = 96 * 1024
            val buffer = ByteArray(chunk)
            var consecutiveFailures = 0
            local.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    val b64 = android.util.Base64.encodeToString(
                        if (n == chunk) buffer else buffer.copyOf(n),
                        android.util.Base64.NO_WRAP,
                    )
                    val r = channels.execAsShell(
                        "echo '$b64' | base64 -d >> $remote",
                        timeoutMs = 30_000L,
                    )
                    if (!r.ok) {
                        consecutiveFailures++
                        channels.log("分块推送失败（第 $consecutiveFailures 次）")
                        if (consecutiveFailures >= 2) return@withContext false
                    } else {
                        consecutiveFailures = 0
                    }
                }
            }
            true
        }

    /**
     * 把 assets 里的 agent dex 打成 zip（entry 名 `classes.dex`），落到应用私有目录。
     *
     * 产物结构与原版指向自身 APK 的效果一致：ART 拿到 zip 后会在里面找
     * `classes.dex` 并加载其中唯一的入口类。
     */
    private fun writeAgentAssetAsZip(): File? = runCatching {
        val dexBytes = context.assets.open("agent/classes.dex").use { it.readBytes() }
        if (dexBytes.isEmpty()) return@runCatching null
        val out = File(context.cacheDir, "agent/agent.zip")
        out.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
            zip.write(dexBytes)
            zip.closeEntry()
        }
        if (out.exists() && out.length() > 0) out else null
    }.getOrNull()

    /**
     * 把目标 APK 放到目标机上 shell 能读到的位置。
     *
     * 原版的规则是：路径以 `/storage/` 或 `/sdcard/` 开头就直接用，
     * 否则先复制到外部存储。这里照搬同样的判断，
     * 再补一个 `/data/local/tmp` 兜底（那是最容易被 shell 读到的地方）。
     *
     * @return 目标机上可读的路径；无法处理时返回 null
     */
    private suspend fun stageTargetApk(apk: File): String? {
        val path = apk.absolutePath
        // 原版规则：/storage/ 与 /sdcard/ 开头的路径 shell 直接能读。
        // 但**应用私有目录下的 Android/data 除外**——Android 11 起
        // adb shell 没有权限进入 /storage/emulated/0/Android/data，
        // 所以应用自己放在那里的 APK 必须搬走。
        val inAndroidData = path.contains("/Android/data/")
        if (!inAndroidData && (path.startsWith("/storage/") || path.startsWith("/sdcard/"))) {
            return path
        }
        // 其余路径（应用私有目录）shell 读不到，
        // 用分块推送的方式搬到 /data/local/tmp
        val remote = "/data/local/tmp/paperlite_target.apk"
        val ok = pushFile(apk, remote)
        return if (ok) remote else null
    }

    /**
     * 安装诊断。
     *
     * 直接把安装代理命令跑一遍并把**原始输出**返回给界面，
     * 让用户能自己看到到底卡在哪一步（找不到类、权限拒绝、还是超时）。
     * 这不是给用户看的「结果」，是给排查用的「证据」。
     */
    suspend fun diagnose(apk: File): String = withContext(Dispatchers.IO) {
        if (!apk.exists()) return@withContext "APK 不存在：${apk.absolutePath}"
        val hasAgent = agentPackaged()
        val target = stageTargetApk(apk)
            ?: return@withContext "无法把目标 APK 放到 shell 可读位置"
        if (!hasAgent) {
            return@withContext buildString {
                appendLine("代理资产：缺失（assets/agent/classes.dex 没有打进 APK）")
                appendLine("目标 APK：$target")
                appendLine("结论：安装代理通道不可用，需要重新打包（preBuild 会生成该 dex）。")
            }
        }
        val agentZip = stageAgentDex()
            ?: return@withContext "无法把安装代理包推到目标机（通道可能不是 shell 身份）"
        val zipInfo = channels.execAsShell(
            "ls -l '$agentZip' 2>&1 | head -1",
            timeoutMs = 15_000L,
        )
        val cmd = "CLASSPATH='$agentZip' app_process /system/bin " +
            "com.codefox.paperlite.core.install.InstallAgent '$target' 2>&1"
        val r = channels.execAsShell(cmd, timeoutMs = 120_000L)
        channels.execAsShell("rm -f '$agentZip' 2>/dev/null", timeoutMs = 15_000L)
        buildString {
            appendLine("代理资产：已打包")
            appendLine("命令：$cmd")
            appendLine("通道：${r.via}")
            appendLine("退出码：${if (r.ok) 0 else 1}")
            if (r.error != null) appendLine("错误：${r.error}")
            appendLine("代理包：${zipInfo.output.trim()}")
            appendLine("目标 APK：$target")
            appendLine("--- 输出 ---")
            append(r.output.take(2000))
            appendLine()
            appendLine(conclude(r))
        }
    }

    /**
     * 根据诊断输出给出一句可执行的结论。
     *
     * 光贴原始输出没用，用户需要知道「接下来该做什么」。
     */
    private fun conclude(r: com.codefox.paperlite.core.ExecResult): String {
        val out = r.output
        return when {
            out.contains("INSTALL_AGENT_OK") ->
                "结论：安装代理执行成功，应用已装上。"

            out.contains("systemMain failed") ||
                out.contains("system context unavailable") ->
                "结论：安装代理拿不到 system 身份。app_process 必须在 root 或 adb shell " +
                    "身份下运行，请先在「配对」页完成无线调试配对，或使用带 root 的设备。" +
                    "（用应用自身 uid 跑会被 SELinux 拦掉 system_server 访问）"

            out.contains("ClassNotFoundException") ->
                "结论：找不到安装代理入口类。检查 CLASSPATH 指向的 APK 是否含 " +
                    "com.codefox.paperlite.core.install.InstallAgent。"

            out.contains("INSTALL_AGENT_TIMEOUT") ->
                "结论：会话提交了但 30 秒内没装上，通常是包签名与已安装版本冲突，或存储空间不足。"

            out.contains("INSTALL_AGENT_FAIL") ->
                "结论：系统拒绝了这次安装。可能是签名不一致、版本降级被禁，或设备策略禁止。"

            out.contains("INSTALL_AGENT_ERROR") ->
                "结论：安装代理内部出错，上面的输出里有具体原因。"

            r.via == "无" ||
                (r.error != null && r.error.contains("没有可用的 shell 身份通道")) ->
                "结论：当前没有任何能以 shell 身份执行命令的通道。" +
                    "请先在「配对」页完成无线调试配对，或使用带 root 的设备。"

            else ->
                "结论：命令执行结束但没有明确结果，请结合上面的原始输出判断。"
        }
    }

    /** 用设备所有者权限做静默安装。 */
    private fun silentInstallViaOwner(apk: File): Boolean {
        // owner 下可以用 PackageInstaller 的静默会话
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = runCatching { pi.createSession(params) }.getOrNull() ?: return false
        return runCatching {
            pi.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, com.codefox.paperlite.core.channel.InstallResultReceiver::class.java)
                    .putExtra(com.codefox.paperlite.core.channel.InstallResultReceiver.EXTRA_PACKAGE, apk.name)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    android.app.PendingIntent.FLAG_MUTABLE else 0
                val pending = android.app.PendingIntent.getBroadcast(
                    context, sessionId, intent, flags,
                )
                session.commit(pending.intentSender)
            }
            true
        }.getOrElse {
            channels.log("设备所有者安装失败：${it.message}")
            runCatching { pi.abandonSession(sessionId) }
            false
        }
    }

    /**
     * 用 shell 流把本地文件写进设备。
     *
     * 两处硬限制，都是为了避免「装个大包就把应用拖死」：
     * - 单次推包最多 256MB，超过直接放弃并回退到系统安装器
     * - 分块数有上限；并且一旦连续两次分块失败就中止，
     *   而不是一直循环到文件读完（原来的写法在通道半死时会空转几十次 exec）
     */
    private suspend fun pushFile(local: File, remote: String): Boolean = withContext(Dispatchers.IO) {
        val size = local.length()
        if (size <= 0L) {
            channels.log("安装包为空")
            return@withContext false
        }
        if (size > MAX_PUSH_BYTES) {
            channels.log("安装包 ${size / 1024 / 1024}MB 超过推包上限，改走系统安装器")
            return@withContext false
        }
        // 先确认目录可写（/data/local/tmp 只有 shell / root 身份能写）
        val probe = channels.execAsShell(
            "echo ok > $remote.probe && rm -f $remote.probe && echo ok",
            timeoutMs = 20_000L,
        )
        if (!probe.ok || !probe.output.contains("ok")) {
            channels.log("远程目录不可写：$remote")
            return@withContext false
        }
        // 用 base64 分块写入，避免二进制被 shell 解释
        val chunk = 96 * 1024
        val buffer = ByteArray(chunk)
        val init = channels.execAsShell(": > $remote", timeoutMs = 20_000L)
        if (!init.ok) return@withContext false

        var consecutiveFailures = 0
        var chunks = 0
        local.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                if (++chunks > MAX_CHUNKS) {
                    channels.log("分块数超过上限，中止推包")
                    return@withContext false
                }
                val b64 = android.util.Base64.encodeToString(
                    if (n == chunk) buffer else buffer.copyOf(n),
                    android.util.Base64.NO_WRAP,
                )
                val r = channels.execAsShell(
                    "echo '$b64' | base64 -d >> $remote",
                    timeoutMs = 30_000L,
                )
                if (!r.ok) {
                    consecutiveFailures++
                    channels.log("推包分块失败（第 ${consecutiveFailures} 次）")
                    if (consecutiveFailures >= 2) {
                        channels.log("连续失败，放弃推包")
                        return@withContext false
                    }
                } else {
                    consecutiveFailures = 0
                }
                // 只有进度才是有效信息，别把每个分块的 base64 都塞进日志
                if (chunks % 32 == 0) {
                    val mb = chunks.toLong() * chunk / 1024 / 1024
                    channels.log("推包进度 ${mb}MB / ${size / 1024 / 1024}MB")
                }
            }
        }
        val remoteSize = channels.execAsShell("wc -c < $remote", timeoutMs = 20_000L)
            .output.trim().toLongOrNull() ?: -1L
        remoteSize == size
    }

    /** 拉起系统包安装器。 */
    fun launchSystemInstaller(apk: File): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    apk,
                ),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse {
        channels.log("拉起系统安装器失败：${it.message}")
        false
    }

    /** 卸载应用。 */
    suspend fun uninstall(packageName: String, keepData: Boolean): Outcome =
        withContext(Dispatchers.IO) {
            if (packageName.isBlank()) return@withContext Outcome(false, "无", "包名为空")
            runCatching { uninstallInner(packageName, keepData) }.getOrElse {
                Outcome(false, "全部", "卸载出错：${it.message ?: it::class.java.simpleName}")
            }
        }

    private suspend fun uninstallInner(packageName: String, keepData: Boolean): Outcome {
        val states = channels.states.value
        val cmd = if (keepData) "pm uninstall -k $packageName" else "pm uninstall $packageName"
        val authorizer = channels.settingsRef.installAuthorizer.value

        // 0. 作业帮系统服务（InstallerX 的 Birchua 授权器同款）
        if ((authorizer == InstallAuthorizer.Auto || authorizer == InstallAuthorizer.Zybos) &&
            ZybosApi.isAvailable(context)
        ) {
            val r = ZybosApi.silentUninstall(context, packageName)
            if (r.success) {
                return Outcome(true, "作业帮系统服务", r.message)
            }
            channels.log("卸载：作业帮系统服务未成功 → ${r.message}")
        }

        // 1. Root
        if (states[Channel.Root]?.available == true &&
            (authorizer == InstallAuthorizer.Auto || authorizer == InstallAuthorizer.Root)
        ) {
            val r = channels.execAsShell(cmd)
            if (r.ok && !r.output.contains("Failure")) {
                return Outcome(true, "Root", "已通过 Root 静默卸载")
            }
        }

        // 2. 无线调试 / 本地回环
        if ((states[Channel.Wireless]?.available == true || states[Channel.LocalLoop]?.available == true) &&
            (authorizer == InstallAuthorizer.Auto || authorizer == InstallAuthorizer.Shell)
        ) {
            val r = channels.execAsShell(cmd)
            if (r.ok && !r.output.contains("Failure")) {
                return Outcome(true, r.via, "已通过 ${r.via} 卸载")
            }
            // 卸载失败但包还在，说明是系统应用，需要先停用
            return Outcome(false, r.via, "卸载失败：${r.error ?: "系统未检测到卸载结果"}")
        }

        // 3. 设备所有者：卸不掉时至少停用
        if (states[Channel.DeviceOwner]?.available == true) {
            val ok = DeviceOwner.setSuspended(context, packageName, true)
            if (ok) {
                return Outcome(true, "设备所有者", "已临时停用（无法卸载的系统应用）")
            }
        }

        // 4. 系统卸载界面
        val launched = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
        return if (launched) {
            Outcome(false, "系统卸载器", "已拉起系统卸载界面", true)
        } else {
            Outcome(false, "全部", "无可用卸载通道")
        }
    }

    /** 冻结 / 解冻应用（停用而非卸载）。 */
    suspend fun freeze(packageName: String, frozen: Boolean): Outcome =
        withContext(Dispatchers.IO) {
            if (packageName.isBlank()) return@withContext Outcome(false, "无", "包名为空")
            runCatching {
                val cmd = if (frozen)
                    "pm disable-user --user 0 $packageName"
                else
                    "pm enable $packageName"
                val r = channels.execAsShell(cmd)
                if (r.ok) {
                    Outcome(true, r.via, if (frozen) "已停用并停止运行" else "已解冻")
                } else if (channels.states.value[Channel.DeviceOwner]?.available == true) {
                    // owner 兜底
                    val ok = DeviceOwner.setSuspended(context, packageName, frozen)
                    if (ok) Outcome(true, "设备所有者", if (frozen) "已临时停用" else "已恢复")
                    else Outcome(false, r.via, "冻结失败：${r.error ?: "权限不足"}")
                } else {
                    Outcome(false, r.via, "冻结失败：${r.error ?: "权限不足"}")
                }
            }.getOrElse {
                Outcome(false, "全部", "操作出错：${it.message ?: it::class.java.simpleName}")
            }
        }

    /** 清除应用数据。 */
    suspend fun clearData(packageName: String): Outcome = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext Outcome(false, "无", "包名为空")
        runCatching {
            val r = channels.execAsShell("pm clear $packageName")
            if (r.ok && r.output.contains("Success")) Outcome(true, r.via, "已清除应用数据")
            else Outcome(false, r.via, "清除失败：${r.error ?: r.output.take(80)}")
        }.getOrElse {
            Outcome(false, "全部", "清除出错：${it.message ?: it::class.java.simpleName}")
        }
    }

    /** 解析 APK 基本信息。 */
    fun parseApk(apk: File): ApkMeta? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        info.applicationInfo?.sourceDir = apk.absolutePath
        info.applicationInfo?.publicSourceDir = apk.absolutePath
        ApkMeta(
            packageName = info.packageName,
            versionName = info.versionName ?: "-",
            versionCode = runCatching { info.longVersionCode }.getOrDefault(0L),
            label = runCatching {
                pm.getApplicationLabel(info.applicationInfo!!).toString()
            }.getOrDefault(info.packageName),
            size = apk.length(),
        )
    }.getOrNull()

    data class ApkMeta(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val label: String,
        val size: Long,
    )

    private companion object {
        /**
         * 单次推包上限。
         *
         * 原版限制在 64MB，但现在的 APK 动辄上百兆（微信、游戏包），
         * 卡在 64MB 等于「稍大一点的包就永远装不上」。
         * 提到 256MB 后覆盖绝大多数真实安装包；仍然留上限，
         * 是为了避免极端情况下把内存和通道拖死。
         */
        const val MAX_PUSH_BYTES = 256L * 1024 * 1024
        /** 分块上限（96KB × 3000 ≈ 288MB，留出余量）。 */
        const val MAX_CHUNKS = 3000
    }
}
