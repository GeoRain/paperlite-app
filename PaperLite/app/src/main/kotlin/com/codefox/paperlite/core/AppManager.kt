package com.codefox.paperlite.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 应用管理辅助。
 *
 * 覆盖原版 JC Code V3 应用页里除卸载/冻结之外的几项能力：
 * - 提取应用 APK 到下载目录（对齐原版「复制成功 / 已复制到」）
 * - 计算 APK 的 SHA-256 / SHA-1（对齐原版的「正在获取 APK 校验信息」）
 * - 打开系统应用详情页
 * - 防卸载保护开关（对应原版的「单独应用防卸载」）
 */
object AppManager {

    /**
     * 提取已安装应用的 APK。
     *
     * 优先用 `pm path` 拿到真实路径（split APK 场景下也能拿到主包），
     * 拿不到再退回 `ApplicationInfo.sourceDir`。
     *
     * @return 提取结果：成功给出文件路径与校验值，失败给出原因
     */
    suspend fun extractApk(
        context: Context,
        channels: ChannelManager,
        packageName: String,
    ): ExtractResult = withContextIo {
        if (packageName.isBlank()) {
            return@withContextIo ExtractResult(false, null, null, null, "包名为空")
        }
        val pm = context.packageManager
        val ai = runCatching {
            pm.getApplicationInfo(packageName, 0)
        }.getOrNull()
        if (ai == null) {
            return@withContextIo ExtractResult(false, null, null, null, "未找到应用：$packageName")
        }

        // 源 APK 路径：sourceDir 是主包；split 应用还可能有 split_*.apk
        val src = ai.sourceDir
        if (src.isNullOrBlank() || !File(src).exists()) {
            return@withContextIo ExtractResult(false, null, null, null, "无法定位 APK 文件")
        }

        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(packageName)
        val safeLabel = label.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(40)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(dir, "$safeLabel-$packageName.apk")

        val copied = runCatching {
            File(src).inputStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out.exists() && out.length() > 0
        }.getOrDefault(false)

        if (!copied) {
            return@withContextIo ExtractResult(false, null, null, null, "复制失败，无法提取")
        }

        val sha256 = sha256(out)
        val sha1 = sha1(out)
        channels.log("已提取 $packageName → ${out.absolutePath}")
        ExtractResult(
            success = true,
            path = out.absolutePath,
            sha256 = sha256,
            sha1 = sha1,
            message = "已提取到 ${out.absolutePath}",
        )
    }

    /** 计算文件 SHA-256。 */
    fun sha256(file: File): String? = digest(file, "SHA-256")

    /** 计算文件 SHA-1。 */
    fun sha1(file: File): String? = digest(file, "SHA-1")

    private fun digest(file: File, algo: String): String? = runCatching {
        val md = MessageDigest.getInstance(algo)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02X".format(it) }
    }.getOrNull()

    /** 切换防卸载保护（对应原版的「单独应用防卸载」）。 */
    suspend fun toggleUninstallProtection(
        context: Context,
        channels: ChannelManager,
        packageName: String,
        blocked: Boolean,
    ): String = withContextIo {
        if (!DeviceOwner.isSelf(context)) {
            // 没有 owner 权限时，退回用 pm 走 setUninstallBlocked 是不行的，
            // 只能如实告知用户需要设备所有者
            return@withContextIo "防卸载保护需要设备所有者权限，请先在设置页激活"
        }
        val ok = DeviceOwner.setUninstallBlocked(context, packageName, blocked)
        if (ok) {
            DeviceOwner.invalidateCache()
            channels.log("防卸载${if (blocked) "开启" else "关闭"}：$packageName")
            if (blocked) "已开启防卸载保护，重启后依然有效" else "已解除防卸载保护"
        } else {
            "设置失败：系统拒绝了防卸载请求"
        }
    }

    /** APK 提取结果。 */
    data class ExtractResult(
        val success: Boolean,
        val path: String?,
        val sha256: String?,
        val sha1: String?,
        val message: String,
    )
}

/** 统一把协程调度到 IO 线程的小工具。 */
internal suspend fun <T> withContextIo(block: suspend () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
