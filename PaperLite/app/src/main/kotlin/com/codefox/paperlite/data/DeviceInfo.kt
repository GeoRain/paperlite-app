package com.codefox.paperlite.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import java.io.File

/** 一条已安装应用信息。 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val enabled: Boolean,
    val suspended: Boolean,
    val uninstallBlocked: Boolean,
    val sizeBytes: Long,
)

/** 设备信息分组。 */
data class InfoGroup(val title: String, val rows: List<Pair<String, String>>)

/**
 * 设备信息采集。
 *
 * 一部分来自 Build / SystemProperties，一部分要从高权限通道读，
 * 拿不到的行会显示「未获取」而不是伪造。
 */
object DeviceInfo {

    fun basic(context: Context): List<InfoGroup> {
        val pm = context.packageManager
        val pkgInfo = runCatching {
            pm.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        val groups = mutableListOf<InfoGroup>()

        groups += InfoGroup(
            "设备基本信息",
            listOf(
                "品牌" to Build.BRAND.orEmpty(),
                "型号" to Build.MODEL.orEmpty(),
                "设备代号" to Build.DEVICE.orEmpty(),
                "序列号" to (runCatching { Build.getSerial() }.getOrNull() ?: prop("ro.serialno")),
            ),
        )

        groups += InfoGroup(
            "系统",
            listOf(
                "系统版本" to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
                "构建号" to Build.DISPLAY.orEmpty(),
                "内部版本" to prop("ro.build.display.id"),
                "安全补丁" to Build.VERSION.SECURITY_PATCH.orEmpty(),
                "系统指纹" to prop("ro.build.fingerprint"),
                "是否作业帮设备" to if (isZybDevice()) "是" else "否",
            ),
        )

        groups += InfoGroup(
            "环境配置",
            listOf(
                "开发者选项" to if (devOptionsEnabled(context)) "已开启" else "已关闭",
                "无线调试" to if (wirelessDebuggingOn(context)) "已开启" else "未开启",
                "设备所有者" to (com.codefox.paperlite.core.DeviceOwner.ownerPackage(context) ?: "无"),
                "本应用设备码" to deviceCode(context),
                "本应用版本" to (pkgInfo?.let { "${it.versionName} (${it.longVersionCode})" } ?: "-"),
            ),
        )

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        groups += InfoGroup(
            "内存与存储",
            listOf(
                "运行内存" to formatSize(totalRam(context)),
                "可用内存" to formatSize(mi.availMem),
                "数据分区总容量" to formatSize(statFs("/data").first),
                "数据分区可用" to formatSize(statFs("/data").second),
            ),
        )

        return groups
    }

    /** 进程列表（需要高权限才能看到完整信息）。 */
    fun processes(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val procDir = File("/proc")
        procDir.listFiles()?.forEach { f ->
            if (!f.name.all { it.isDigit() }) return@forEach
            val cmdline = runCatching {
                File(f, "cmdline").readText().trimEnd('\u0000')
            }.getOrNull().orEmpty()
            if (cmdline.isNotBlank()) out += f.name to cmdline
        }
        return out.sortedBy { it.second }
    }

    fun prop(key: String): String = runCatching {
        val p = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        val v = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        v
    }.getOrDefault("")

    fun deviceCode(context: Context): String {
        val raw = listOfNotNull(
            Build.getSerial().takeIf { it.isNotBlank() && it != Build.UNKNOWN },
            Build.FINGERPRINT,
            Build.BOARD,
        ).joinToString("|")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
        return digest.take(8).joinToString("") { "%02X".format(it) }
    }

    fun isZybDevice(): Boolean =
        prop("ro.product.brand").contains("zuoyebang", true) ||
            prop("ro.build.display.id").contains("zyb", true)

    fun devOptionsEnabled(context: Context): Boolean = runCatching {
        AndroidSettings.Global.getInt(
            context.contentResolver,
            AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1
    }.getOrDefault(false)

    fun wirelessDebuggingOn(context: Context): Boolean = runCatching {
        AndroidSettings.Global.getInt(
            context.contentResolver,
            "adb_wifi_enabled",
            0,
        ) == 1
    }.getOrDefault(false)

    fun totalRam(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return mi.totalMem
    }

    fun statFs(path: String): Pair<Long, Long> = runCatching {
        val st = android.os.StatFs(path)
        (st.blockCountLong * st.blockSizeLong) to (st.availableBlocksLong * st.blockSizeLong)
    }.getOrDefault(0L to 0L)

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "-"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    /** 打开应用详情页。 */
    fun openAppInfo(context: Context, pkg: String) {
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 批量读取已安装应用。
     *
     * 必须在 IO 线程调用。这个函数会遍历几百个包，每个包都要读
     * `getApplicationLabel` / `sourceDir`，在主线程上做必然卡顿甚至 ANR。
     *
     * 三条硬性约束（都是闪退来源）：
     * 1. `applicationInfo` 可能为 null —— 直接 `!!` 会 NPE，整页崩掉
     * 2. `suspended` 不能靠再查一次 `enabled` 来推断 —— 那是错的，
     *    而且会对每个包多发一次 IPC，这里改用真实的
     *    `ApplicationInfo.FLAG_SUSPENDED`
     * 3. 返回列表的 `packageName` 必须唯一 —— 它被当作 `LazyColumn` 的 key，
     *    一旦重复，Compose 会抛 `IllegalArgumentException: Key was already used`
     */
    fun installedApps(
        context: Context,
        includeSystem: Boolean,
        isBlocked: (String) -> Boolean,
    ): List<AppEntry> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val pkgs = runCatching { pm.getInstalledPackages(flags) }.getOrDefault(emptyList())
        val result = ArrayList<AppEntry>(pkgs.size)
        val seen = HashSet<String>(pkgs.size)
        for (p in pkgs) {
            val name = p.packageName ?: continue
            if (name == context.packageName) continue
            // 同一个包理论上只会出现一次，但某些定制 ROM 会重复返回，
            // 重复的 key 直接让 Compose 崩，这里先挡掉
            if (!seen.add(name)) continue

            val ai = p.applicationInfo
            val sys = ((ai?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
            if (sys && !includeSystem) continue

            val flags2 = ai?.flags ?: 0
            val suspended = (flags2 and FLAG_SUSPENDED) != 0
            val label = if (ai != null) {
                runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(name)
            } else {
                name
            }

            result += AppEntry(
                packageName = name,
                label = label.ifBlank { name },
                versionName = p.versionName ?: "-",
                versionCode = runCatching { p.longVersionCode }.getOrDefault(0L),
                isSystem = sys,
                enabled = ai?.enabled ?: true,
                suspended = suspended,
                uninstallBlocked = runCatching { isBlocked(name) }.getOrDefault(false),
                // sourceDir 为空时 File("") 指向进程 cwd，会得到一个假的大小
                sizeBytes = runCatching {
                    val dir = ai?.sourceDir
                    if (dir.isNullOrBlank()) 0L else File(dir).length()
                }.getOrDefault(0L),
            )
        }
        return result.sortedBy { it.label.lowercase() }
    }

    /** `ApplicationInfo.FLAG_SUSPENDED` 是隐藏常量，这里写死它的值。 */
    private const val FLAG_SUSPENDED = 1 shl 30
}
