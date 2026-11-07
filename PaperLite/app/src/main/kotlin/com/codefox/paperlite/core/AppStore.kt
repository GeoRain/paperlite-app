package com.codefox.paperlite.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * 应用商店源。
 *
 * 抄「应用商店」（`top.nkbe.appmarket`）的行为：拉列表 → 下载 APK → **自动静默安装**。
 *
 * 那个应用用的是 OPPO 商店接口（`api-cn.store.heytapmobi.com`），请求带签名，
 * 签名算法没法照抄（设备指纹 + 加密），所以这里改用**公开无签名**的源：
 * F-Droid 官方仓库（国内走清华镜像）。
 *
 * ## 索引版本与中文名
 *
 * F-Droid 有两代索引，差别在于**多语言信息**：
 *
 * | | index-v1 | index-v2 |
 * | --- | --- | --- |
 * | 应用名 | 只有英文原名 | `name` 是多语言字典 |
 * | 应用简介 | 只有英文 | `summary` 有 30+ 语言，**含 zh-CN** |
 * | 体积 | 17MB（gzip 后） | 22MB（gzip 后） |
 *
 * v1 的 `name` 字段是纯字符串、且很多包根本没填，界面上只能显示包名；
 * v2 的 `name` / `summary` 是 `{ "en-US": ..., "zh-CN": ... }` 这样的字典，
 * F-Droid 官方翻译就在里面——这是**上游提供的**中文名，不是我们自己猜的。
 *
 * 所以默认源用 v2，取名规则是「有 zh-CN 用 zh-CN，没有就回退 en-US」。
 * v1 保留为降级源：有些自建仓库只提供 v1。
 *
 * 解析用 kotlinx.serialization 的流式解码：索引解压后有 70 多 MB，
 * 一次性读成字符串会吃掉几百 MB 内存，流式解码只保留解析出来的条目。
 */
object AppStore {

    /** 商店条目。 */
    data class Entry(
        val name: String,
        val packageName: String,
        val versionName: String,
        val sizeText: String,
        val summary: String,
        val apkUrl: String,
    )

    /** 一个可用的商店源。 */
    data class Source(
        val name: String,
        /** 索引地址：F-Droid 风格的 `index-v2.json` 或 `index-v1.jar`。 */
        val indexUrl: String,
        /** 索引格式。 */
        val format: Format = Format.V2,
    )

    /** 索引格式。 */
    enum class Format { V2, V1 }

    /** 内置默认源（清华 / 中科大镜像的 F-Droid 仓库）。 */
    val DEFAULT_SOURCES: List<Source> = listOf(
        Source(
            name = "开源应用仓库（清华镜像）",
            indexUrl = "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/index-v2.json",
            format = Format.V2,
        ),
        Source(
            name = "开源应用仓库（中科大镜像）",
            indexUrl = "https://mirrors.ustc.edu.cn/fdroid/repo/index-v2.json",
            format = Format.V2,
        ),
        Source(
            name = "开源应用仓库（精简索引）",
            indexUrl = "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/index-v1.jar",
            format = Format.V1,
        ),
    )

    /** F-Droid v2 索引结构（只取需要的字段）。 */
    @Serializable
    private data class FdIndexV2(
        val packages: Map<String, FdPackageV2> = emptyMap(),
    )

    @Serializable
    private data class FdPackageV2(
        val metadata: FdMetadataV2 = FdMetadataV2(),
        val versions: Map<String, FdVersionV2> = emptyMap(),
    )

    @Serializable
    private data class FdMetadataV2(
        /** 多语言应用名，`{"en-US": "Termux", "zh-CN": "..."}`。 */
        val name: Map<String, String> = emptyMap(),
        /** 多语言简介。 */
        val summary: Map<String, String> = emptyMap(),
        val authorName: String = "",
        val categories: List<String> = emptyList(),
    )

    @Serializable
    private data class FdVersionV2(
        val file: FdFileV2 = FdFileV2(),
        val manifest: FdManifestV2 = FdManifestV2(),
    )

    @Serializable
    private data class FdFileV2(
        val name: String = "",
        val size: Long = 0,
    )

    @Serializable
    private data class FdManifestV2(
        val versionName: String = "",
        val versionCode: Long = 0,
    )

    /** F-Droid v1 索引结构（只有英文名，作为降级）。 */
    @Serializable
    private data class FdIndexV1(
        val packages: Map<String, List<FdPackageV1>> = emptyMap(),
    )

    @Serializable
    private data class FdPackageV1(
        val packageName: String = "",
        val apkName: String = "",
        val versionName: String = "",
        val name: String = "",
        val summary: String = "",
        val size: Long = 0,
    )

    /**
     * 拉取商店列表。
     *
     * 每个包只保留最新版本，避免把历史版本也塞进列表。
     */
    suspend fun fetch(source: Source): Result<List<Entry>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(source.indexUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 180_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PaperLite")
                // 索引有 70MB，不压缩就是白流 70MB；服务端支持 gzip 就让它压
                setRequestProperty("Accept-Encoding", "gzip")
            }
            val base = source.indexUrl.substringBeforeLast('/')
            val json = Json { ignoreUnknownKeys = true; isLenient = true }

            val raw = conn.inputStream
            val stream: InputStream = when {
                conn.contentEncoding?.contains("gzip", true) == true -> GZIPInputStream(raw)
                source.format == Format.V1 -> raw
                else -> raw
            }

            val entries = when (source.format) {
                Format.V2 -> stream.use { input ->
                    val index = json.decodeFromStream<FdIndexV2>(input)
                    index.packages.mapNotNull { (pkg, entry) ->
                        val version = entry.versions.values.maxByOrNull { it.manifest.versionCode }
                            ?: return@mapNotNull null
                        val apkPath = version.file.name
                        if (apkPath.isBlank()) return@mapNotNull null
                        val english = entry.metadata.name["en-US"].orEmpty()
                        val name = AppNameZh.resolve(entry.metadata.name, pkg, english)
                        val summary = pickLocalized(entry.metadata.summary, "")
                        Entry(
                            name = name,
                            packageName = pkg,
                            versionName = version.manifest.versionName.ifBlank { "-" },
                            sizeText = formatSize(version.file.size),
                            summary = summary,
                            apkUrl = "$base$apkPath",
                        )
                    }
                }

                Format.V1 -> stream.use { input ->
                    // v1 是 zip 包，里面才是 json
                    ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && !entry.name.endsWith(".json")) {
                            entry = zip.nextEntry
                        }
                        if (entry == null) error("索引里没有 json 文件")
                        val index = json.decodeFromStream<FdIndexV1>(zip)
                        index.packages.mapNotNull { (pkg, versions) ->
                            val v = versions.lastOrNull() ?: return@mapNotNull null
                            if (v.apkName.isBlank()) return@mapNotNull null
                            Entry(
                                name = v.name.ifBlank { pkg },
                                packageName = v.packageName.ifBlank { pkg },
                                versionName = v.versionName.ifBlank { "-" },
                                sizeText = formatSize(v.size),
                                summary = v.summary,
                                apkUrl = "$base/${v.apkName}",
                            )
                        }
                    }
                }
            }
            entries.sortedBy { it.name.lowercase() }
        }
    }

    /**
     * 从多语言字典里挑一个名字。
     *
     * 优先中文（`zh-CN` → `zh` → `zh-Hans`），其次英文，最后回退到包名。
     * F-Droid 的翻译是社区提交的，覆盖率不是 100%，
     * 没有中文的包显示英文原名比硬翻更可靠。
     */
    private fun pickLocalized(map: Map<String, String>, fallback: String): String {
        if (map.isEmpty()) return fallback
        for (key in listOf("zh-CN", "zh-Hans", "zh", "zh-TW", "zh-Hant")) {
            val v = map[key]
            if (!v.isNullOrBlank()) return v.trim()
        }
        for (key in listOf("en-US", "en-GB", "en")) {
            val v = map[key]
            if (!v.isNullOrBlank()) return v.trim()
        }
        // 兜底：任意一个非空值
        return map.values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: fallback
    }

    /**
     * 下载 APK 到本地并返回文件。
     *
     * 放到应用的外部目录，shell 与系统服务都读得到。
     */
    suspend fun download(context: Context, url: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "store")
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, "store_${System.currentTimeMillis()}.apk")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 180_000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() > 0) target else error("下载内容为空")
            }
        }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "-"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
