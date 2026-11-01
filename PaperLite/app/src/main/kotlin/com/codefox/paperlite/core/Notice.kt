package com.codefox.paperlite.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 公告。
 *
 * ## 为什么这样设计
 *
 * 用户的服务器不是全天候开放的，如果把公告功能做成「每次启动都去服务器拉」，
 * 服务器一关公告就取不到，页面会一直空着或者报错。
 *
 * 所以这里用**双轨制**：
 *
 * 1. **本地公告优先**。公告内容写在应用自己的存储里（`files/notice.json`），
 *    装一次就一直在，服务器关不关都能看到。
 * 2. **远程公告作为可选增强**。用户可以自己填一个公告地址（可以是任意能返回
 *    JSON 的 URL），能连上就更新，连不上就继续用本地那份，**不会因此报错**。
 *
 * 也就是说：服务器关着不影响公告功能，公告功能不依赖任何服务器。
 */
object Notice {

    /** 一条公告。 */
    data class Item(
        val id: String,
        val title: String,
        val body: String,
        val date: String,
        val level: Level,
    )

    /** 公告级别，决定展示用的颜色。 */
    enum class Level {
        Info,
        Warn,
        Important;

        companion object {
            fun of(raw: String?): Level = when (raw?.lowercase()) {
                "warn", "warning" -> Warn
                "important", "danger", "error" -> Important
                else -> Info
            }
        }
    }

    /** 公告列表 + 拉取状态。 */
    data class State(
        val items: List<Item> = emptyList(),
        val updatedAt: Long = 0L,
        val source: Source = Source.Local,
    )

    /** 公告来源。 */
    enum class Source {
        /** 来自本地存储（离线可用）。 */
        Local,

        /** 来自用户配置的远程地址。 */
        Remote,

        /** 远程拉取失败，正在用本地缓存。 */
        LocalFallback,
    }

    private const val FILE_NAME = "notice.json"
    private const val URL_KEY = "notice_url"
    private const val TIMEOUT_MS = 6_000

    // ------------------------------------------------------------ 本地读取

    /** 读取当前公告（本地优先）。 */
    fun read(context: Context, settings: com.codefox.paperlite.data.Settings): State {
        val local = readLocal(context)
        return if (local.items.isNotEmpty()) {
            State(local.items, local.updatedAt, Source.Local)
        } else {
            // 首次启动给一份内置公告，让页面不至于空着
            val seed = seedItems()
            writeLocal(context, seed)
            State(seed, System.currentTimeMillis(), Source.Local)
        }
    }

    /** 内置公告：首次启动时写入本地，之后由用户或远程更新覆盖。 */
    private fun seedItems(): List<Item> = listOf(
        Item(
            id = "welcome",
            title = "欢迎使用 PaperLite",
            body = "先在「配对」页完成无线调试配对，配对成功后「安装」「终端」等页面即可使用。" +
                "配对码可以手动输入，也可以用「识别配对码」按钮自动读取。",
            date = "",
            level = Level.Info,
        ),
        Item(
            id = "silent-install",
            title = "静默安装是怎么做到的",
            body = "PaperLite 会把自身 APK 挂给 app_process，让它在 system 身份下走 " +
                "PackageInstaller 会话 API 提交安装，所以不会出现确认弹窗。" +
                "如果这条通道失败，会依次回退到 pm install、无线调试推包、设备所有者会话，"+
                "最后才是系统安装界面。",
            date = "",
            level = Level.Info,
        ),
        Item(
            id = "offline",
            title = "公告与服务器无关",
            body = "本页面显示的公告保存在手机本地，服务器关着也能看到。" +
                "如果你填了远程公告地址，能连上就会更新，连不上则继续显示本地这份。",
            date = "",
            level = Level.Info,
        ),
    )

    private fun readLocal(context: Context): State {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) return State()
        return runCatching {
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items += Item(
                    id = o.optString("id", "n$i"),
                    title = o.optString("title"),
                    body = o.optString("body"),
                    date = o.optString("date"),
                    level = Level.of(o.optString("level")),
                )
            }
            State(items, obj.optLong("updatedAt", 0L))
        }.getOrDefault(State())
    }

    // ------------------------------------------------------------ 写入

    /**
     * 写入本地公告。
     *
     * 供「离线导入」用：用户把一份 JSON 文件放到设备上，导入后永久生效，
     * 不依赖任何服务器。
     */
    fun writeLocal(context: Context, items: List<Item>): Boolean = runCatching {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("body", item.body)
                    .put("date", item.date)
                    .put("level", item.level.name.lowercase()),
            )
        }
        val obj = JSONObject()
            .put("updatedAt", System.currentTimeMillis())
            .put("items", arr)
        File(context.filesDir, FILE_NAME).writeText(obj.toString())
        true
    }.getOrDefault(false)

    /** 读取当前配置的公告地址。 */
    fun url(settings: com.codefox.paperlite.data.Settings): String =
        settings.noticeUrl.value.trim()

    /**
     * 从远程地址拉取公告。
     *
     * 拉取失败**不抛异常**，只是拿不到远程内容——调用方继续用本地那份。
     */
    suspend fun fetch(url: String): List<Item>? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                parse(text)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * 解析公告 JSON。
     *
     * 兼容两种格式：
     * - `{"items": [...]}` —— 完整对象
     * - `[...]` —— 直接是数组
     */
    private fun parse(text: String): List<Item> {
        val trimmed = text.trim()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("items") ?: JSONArray()
        }
        val items = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title")
            val body = o.optString("body")
            // 没有标题也没有正文的直接跳过，不占版面
            if (title.isBlank() && body.isBlank()) continue
            items += Item(
                id = o.optString("id", "n$i"),
                title = title,
                body = body,
                date = o.optString("date"),
                level = Level.of(o.optString("level")),
            )
        }
        return items
    }

    /** 把远程拉到的公告写进本地缓存，供离线使用。 */
    suspend fun refresh(context: Context, settings: com.codefox.paperlite.data.Settings): State {
        val remote = fetch(url(settings))
        if (remote != null && remote.isNotEmpty()) {
            writeLocal(context, remote)
            return State(remote, System.currentTimeMillis(), Source.Remote)
        }
        // 拉不到就用本地缓存，不报错
        val local = readLocal(context)
        return if (local.items.isNotEmpty()) {
            State(local.items, local.updatedAt, Source.LocalFallback)
        } else {
            State(emptyList(), 0L, Source.Local)
        }
    }

    /** 公告地址在设置里的存取键。 */
    const val KEY_URL = URL_KEY
}
