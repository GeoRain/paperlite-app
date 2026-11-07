package com.codefox.paperlite.core

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地 APK 库。
 *
 * 对应 InstallerX 首页那个「本地安装包列表」：列出下载目录里的 APK，
 * 标出每个包的安装状态（未安装 / 可更新 / 已安装），点一下就能装。
 */
object ApkLibrary {

    /** 扫描目录。 */
    private val DIRS = listOf(
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Downloads",
        "/storage/emulated/0/Downloads_apk",
        "/sdcard/Download",
        "/sdcard/Downloads",
    )

    /** 一个待安装的安装包。 */
    data class Item(
        val file: File,
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val sizeBytes: Long,
        val modifiedAt: Long,
        /** 与设备上已装版本的关系。 */
        val state: State,
    ) {
        /** 安装状态。 */
        enum class State {
            /** 没装过。 */
            NEW,

            /** 已安装，且本地包版本更高 → 可更新。 */
            UPDATABLE,

            /** 已安装，版本相同。 */
            INSTALLED,

            /** 已安装，但本地包版本更低 → 需先卸载。 */
            DOWNGRADE,
        }
    }

    /**
     * 扫描本地 APK 并标注安装状态。
     *
     * 解析包信息用 `PackageManager.getPackageArchiveInfo`，
     * 它不安装也能读出包名与版本；图标这里不取，避免为列表页解码大量位图。
     */
    suspend fun scan(context: Context, limit: Int = 200): List<Item> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getInstalledPackages(0).associate { it.packageName to it.longVersionCode }
        }.getOrDefault(emptyMap())

        val files = ArrayList<File>()
        for (dir in DIRS) {
            val d = File(dir)
            if (!d.isDirectory) continue
            runCatching {
                d.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".apk", true)) files += f
                }
            }
        }
        // 同一目录可能被多个别名命中，按绝对路径去重
        val unique = files.distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
            .take(limit)

        val items = ArrayList<Item>(unique.size)
        for (f in unique) {
            val info = runCatching {
                pm.getPackageArchiveInfo(f.absolutePath, 0)
            }.getOrNull() ?: continue
            val pkg = info.packageName ?: continue
            val versionName = info.versionName ?: "-"
            val versionCode = runCatching { info.longVersionCode }.getOrDefault(0L)
            val label = runCatching {
                info.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
            }.getOrNull() ?: pkg

            val current = installed[pkg]
            val state = when {
                current == null -> Item.State.NEW
                versionCode > current -> Item.State.UPDATABLE
                versionCode == current -> Item.State.INSTALLED
                else -> Item.State.DOWNGRADE
            }

            items += Item(
                file = f,
                packageName = pkg,
                label = label,
                versionName = versionName,
                versionCode = versionCode,
                sizeBytes = f.length(),
                modifiedAt = f.lastModified(),
                state = state,
            )
        }
        items
    }

    /** 目录里可写吗（决定能不能把包复制进来）。 */
    fun writableDirs(): List<File> = DIRS.map { File(it) }.filter { it.isDirectory && it.canWrite() }

    /** 设备上已安装、且与某个 APK 包名匹配的应用（用于「卸载后重装」）。 */
    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
