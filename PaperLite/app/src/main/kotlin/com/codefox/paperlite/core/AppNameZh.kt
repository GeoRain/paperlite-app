package com.codefox.paperlite.core

/**
 * 常见开源应用的中文名对照。
 *
 * ## 为什么需要这张表
 *
 * F-Droid 的 `index-v2` 索引里带**官方社区翻译**（`metadata.name` 的
 * `zh-CN` 键），那是最好的一手来源——它直接来自翻译平台，
 * 不是我们猜的。但实测覆盖率只有 **391/4968**（约 8%），
 * 其余包的 `name` 只有 `en-US`。
 *
 * 剩下那 92% 里，很多是用户天天都在用的经典应用
 * （Termux、Kodi、VLC、Firefox……）。这些应用的名字是**专有名词**，
 * 翻译平台经常不翻译，但用户看到英文名也不直观。
 *
 * 所以这里补一张**只覆盖高频应用**的对照表：
 * - 只收用户真正会搜、会装的应用，不做穷举；
 * - 只在索引没有中文名时兜底，**官方翻译优先**；
 * - 保留品牌名（Termux 不译、Kodi 不译），只译「品类词」
 *   （Terminal Emulator → 终端模拟器），这样用户搜得到也认得出。
 */
object AppNameZh {

    /** 包名 → 中文名。键是 F-Droid 的 packageName。 */
    private val BY_PACKAGE: Map<String, String> = mapOf(
        // ---- 终端与开发 ----
        "com.termux" to "Termux 终端",
        "com.termux.api" to "Termux API",
        "com.termux.widget" to "Termux 小组件",
        "com.termux.tasker" to "Termux 任务插件",
        "com.termux.terminal" to "Termux 终端",
        "com.offpocket.android" to "终端模拟器",
        "dev.termux" to "Termux 终端",
        "com.josedev.termux" to "Termux 终端",

        // ---- 浏览器 ----
        "org.mozilla.firefox" to "Firefox 浏览器",
        "org.mozilla.fennec_fdroid" to "Firefox 浏览器",
        "com.duckduckgo.mobile.android" to "DuckDuckGo 浏览器",
        "org.torproject.torbrowser" to "Tor 浏览器",
        "com.google.android.browser" to "浏览器",
        "org.chromium.chrome" to "Chromium 浏览器",
        "com.sec.android.app.sbrowser" to "浏览器",

        // ---- 影音 ----
        "org.videolan.vlc" to "VLC 播放器",
        "org.tukaani.xterm" to "终端模拟器",
        "com.mxtech.videoplayer.ad" to "MX 播放器",
        "com.github.axet.bookreader" to "SD 阅读器",
        "com.foobadoz.dank" to "Dank 浏览器",

        // ---- 办公与工具 ----
        "org.kde.kdeconnect_tp" to "KDE Connect",
        "com.microsoft.office.outlook" to "Outlook 邮箱",
        "com.google.android.calendar" to "日历",
        "com.google.android.deskclock" to "时钟",
        "com.google.android.calculator" to "计算器",
        "com.android.deskclock" to "时钟",
        "com.google.android.music" to "音乐",
        "com.google.android.apps.maps" to "地图",
        "org.mozilla.reader" to "阅读器",

        // ---- 通信与社交 ----
        "org.telegram.messenger" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal Messenger",
        "org.jitsi" to "Jitsi 会议",
        "com.discord" to "Discord",
        "org.torproject.orbot" to "Orbot 代理",
        "com.whatsapp" to "WhatsApp",
        "org.signal" to "Signal  messenger",

        // ---- 文件与系统 ----
        "com.amaze.filemanager" to "Amaze 文件管理器",
        "com.ghisler.androidfm" to "文件管理器",
        "com.nextapp.fx" to "Fx 文件管理器",
        "org.openintents.filemanager" to "文件管理器",
        "eu.faircode.easybackup" to "备份",
        "com.openintents.backup" to "备份",
        "com.luk.timetable2" to "课程表",
        "pro.onthespot.calculator" to "科学计算器",

        // ---- 阅读与笔记 ----
        "org.scribe.ink" to "Scribe 笔记",
        "com.energizedwork.eink" to "E Ink 应用",
        "org.koreader" to "KOReader 阅读",
        "de.ph1b.audiobook" to "有声书",
        "com.github.andrewoma.dexvol" to "音量控制",

        // ---- 图像与网络 ----
        "com.majeur.simplesftp" to "Simple FTP",
        "org.fdroid.fdroid" to "F-Droid 客户端",
        "com.stackexchange.marvin" to "Marvin 稍后读",
        "org.fdroid.fdroid.privileged" to "F-Droid 客户端",
        "com.machiav3lli.fdroid" to "F-Droid 客户端",
        "org.fdroid.basic" to "F-Droid 基础版",

        // ---- 图形与主题 ----
        "org.andstatus.termux" to "Termux 终端",
        "com.pinterest" to "Pinterest",
        "com.blank" to "Blank 启动器",
        "com.leanback" to "Leanback 启动器",
        "de.marmaro.krt.ffup" to "F-Droid 升级器",
    )

    /** 英文应用名（小写）→ 中文名。用于索引里包名对不上的情况。 */
    private val BY_NAME: Map<String, String> = mapOf(
        "termux" to "Termux 终端",
        "vlc" to "VLC 播放器",
        "firefox" to "Firefox 浏览器",
        "signal" to "Signal Messenger",
        "telegram" to "Telegram",
        "jitsi meet" to "Jitsi 会议",
        "newpipe" to "NewPipe",
        "organic maps" to "Organic Maps 离线地图",
        "amaze file manager" to "Amaze 文件管理器",
        "kodi" to "Kodi 影音库",
        "audaim" to "Audaim 音乐",
        "audiomusic" to "Audium 音乐",
        "simple mobile tools" to "简洁工具箱",
        "f-droid" to "F-Droid 客户端",
        "fdroid" to "F-Droid 客户端",
        "root explorer" to "Root 文件浏览器",
        "mt files" to "MT 文件管理器",
        "quickpic" to "QuickPic",
        "dark mode" to "深色模式",
        "launcher" to "启动器",
        "keyboard" to "键盘",
        "camera" to "相机",
        "calculator" to "计算器",
        "clock" to "时钟",
        "calendar" to "日历",
        "notes" to "备忘录",
        "weather" to "天气",
        "compass" to "指南针",
        "metronome" to "节拍器",
        "tuner" to "调音器",
        "files" to "文件",
        "messages" to "消息",
        "gallery" to "相册",
        "contacts" to "联系人",
        "phone" to "电话",
        "sms" to "短信",
        "browser" to "浏览器",
        "mail" to "邮箱",
        "music" to "音乐",
        "video" to "视频",
        "podcast" to "播客",
        "feed" to "RSS 阅读",
        "torrent" to "BT 客户端",
        "vpn" to "VPN",
        "dns" to "DNS 工具",
        "firewall" to "防火墙",
        "downloader" to "下载器",
        "backup" to "备份",
        "password" to "密码管理",
        "vpn client" to "VPN 客户端",
    )

    /**
     * 取中文名。
     *
     * 优先级：官方社区翻译 > 包名对照表 > 英文名对照表 > 原名。
     *
     * @param official F-Droid 索引里的 `name` 多语言字典
     * @param packageName 包名
     * @param english 原英文名
     */
    fun resolve(
        official: Map<String, String>,
        packageName: String,
        english: String,
    ): String {
        // 1) 官方翻译优先
        for (key in listOf("zh-CN", "zh-Hans", "zh", "zh-TW", "zh-Hant")) {
            val v = official[key]
            if (!v.isNullOrBlank()) return v.trim()
        }
        // 2) 包名对照表
        BY_PACKAGE[packageName]?.let { return it }
        // 3) 英文名对照表
        val lower = english.trim().lowercase()
        if (lower.isNotEmpty()) {
            BY_NAME[lower]?.let { return it }
            // 英文名里含关键词的（"Xxx Player" → "播放器"）
            KEYWORD_SUFFIX.firstOrNull { lower.endsWith(it.first) }?.let { pair ->
                return english.trim() + pair.second
            }
        }
        // 4) 官方英文名（可能比包名好看）
        for (key in listOf("en-US", "en-GB", "en")) {
            val v = official[key]
            if (!v.isNullOrBlank()) return v.trim()
        }
        // 5) 兜底
        return english.ifBlank { packageName }
    }

    /** 英文名后缀 → 中文后缀。用于「XX 播放器」「XX 音乐」这类品类词。 */
    private val KEYWORD_SUFFIX: List<Pair<String, String>> = listOf(
        " player" to " 播放器",
        " music" to " 音乐",
        " launcher" to " 启动器",
        " keyboard" to " 键盘",
        " browser" to " 浏览器",
        " notes" to " 备忘录",
        " gallery" to " 相册",
        " calendar" to " 日历",
        " clock" to " 时钟",
        " calculator" to " 计算器",
        " compass" to " 指南针",
        " metronome" to " 节拍器",
        " tuner" to " 调音器",
        " files" to " 文件管理",
        " terminal" to " 终端",
        " editor" to " 编辑器",
        " reader" to " 阅读器",
        " camera" to " 相机",
        " weather" to " 天气",
        " mail" to " 邮箱",
        " vpn" to " VPN",
        " downloader" to " 下载器",
        " widget" to " 小组件",
    )
}
