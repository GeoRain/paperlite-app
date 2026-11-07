package com.codefox.paperlite.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import com.codefox.paperlite.ui.theme.ColorSource
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.ThemeMode

/**
 * 安装授权器。
 *
 * 概念直接对齐 **InstallerX** 的 `ConfigEntity.Authorizer`：
 * InstallerX 把它做成一个下拉项（None / Root / Shizuku / Dhizuku / Birchua / 自定义），
 * 决定安装动作「以谁的身份执行」。
 *
 * PaperLite 的通道比 InstallerX 多，所以这里按本项目实际有的链路列：
 * - **Auto**：按优先级依次尝试（默认，行为最稳）
 * - **Zybos**：作业帮系统服务，对应 InstallerX 的 Birchua 授权器
 * - **Root / Shell**：root 与 adb shell 身份
 * - **Owner**：设备所有者会话
 * - **System**：只走系统安装界面，人工确认
 */
enum class InstallAuthorizer(
    val value: String,
    val title: String,
    val desc: String,
) {
    Auto("auto", "自动", "按优先级依次尝试所有可用通道"),
    Zybos("zybos", "作业帮系统服务", "调用 ZybosSysApiManager 静默安装，仅作业帮设备可用"),
    Root("root", "Root", "用 root 身份执行 pm install"),
    Shell("shell", "ADB shell", "用无线调试 / 本地回环会话执行 pm install"),
    Owner("owner", "设备所有者", "用设备所有者会话提交安装"),
    System("system", "系统安装器", "拉起系统安装界面，需要人工确认"),
    ;

    companion object {
        fun of(raw: String?): InstallAuthorizer =
            entries.firstOrNull { it.value == raw } ?: Auto
    }
}

/**
 * 全量设置。SharedPreferences 里每一项都对应一个 StateFlow，
 * 界面通过 collectAsState 订阅，避免整页重组。
 */
class Settings(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("paperlite", Context.MODE_PRIVATE)

    // ---------- 外观 ----------
    private val _themeMode = MutableStateFlow(ThemeMode.of(sp.getString(K_THEME, null)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** 界面风格：澎湃风 / Material 3。 */
    private val _designStyle = MutableStateFlow(DesignStyle.of(sp.getString(K_STYLE, null)))
    val designStyle: StateFlow<DesignStyle> = _designStyle.asStateFlow()

    private val _fontScale = MutableStateFlow(sp.getInt(K_FONT, 100))
    val fontScale: StateFlow<Int> = _fontScale.asStateFlow()

    /** 隐藏不可用按钮：通道没就绪时把按钮藏起来而不是置灰。 */
    private val _hideUnavailable = MutableStateFlow(sp.getBoolean(K_HIDE_UNAVAIL, false))
    val hideUnavailable: StateFlow<Boolean> = _hideUnavailable.asStateFlow()

    private val _dynamicColor = MutableStateFlow(sp.getBoolean(K_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    /** 配色来源：默认壁纸取色（用户给的默认背景就是一张壁纸）。 */
    private val _colorSource =
        MutableStateFlow(ColorSource.of(sp.getString(K_COLOR_SOURCE, null)))
    val colorSource: StateFlow<ColorSource> = _colorSource.asStateFlow()

    /** 是否把壁纸画成应用内背景。默认开启。 */
    private val _wallpaperEnabled = MutableStateFlow(sp.getBoolean(K_WALLPAPER, true))
    val wallpaperEnabled: StateFlow<Boolean> = _wallpaperEnabled.asStateFlow()

    /**
     * 自定义壁纸在应用私有目录里的文件路径。
     * 为 null 表示用随包内置的 `assets/wallpaper/default.webp`。
     */
    private val _wallpaperPath = MutableStateFlow(sp.getString(K_WALLPAPER_PATH, null))
    val wallpaperPath: StateFlow<String?> = _wallpaperPath.asStateFlow()

    /** 彩蛋：连点左上角图标解锁隐藏菜单。 */
    private val _eggUnlocked = MutableStateFlow(sp.getBoolean(K_EGG, false))
    val eggUnlocked: StateFlow<Boolean> = _eggUnlocked.asStateFlow()

    // ---------- 通道 ----------
    /** 本地回环 adb 的端口，默认 5555。 */
    private val _localLoopPort = MutableStateFlow(sp.getInt(K_LOOP_PORT, 5555))
    val localLoopPort: StateFlow<Int> = _localLoopPort.asStateFlow()

    /** 无线调试配对服务端口，配对成功后由 mDNS 解析得到。 */
    private val _pairPort = MutableStateFlow(sp.getInt(K_PAIR_PORT, 0))
    val pairPort: StateFlow<Int> = _pairPort.asStateFlow()

    private val _connectPort = MutableStateFlow(sp.getInt(K_CONNECT_PORT, 0))
    val connectPort: StateFlow<Int> = _connectPort.asStateFlow()

    /** 上次成功配对的设备标识，用于断线自动重连。 */
    private val _lastDevice = MutableStateFlow(sp.getString(K_LAST_DEVICE, null))
    val lastDevice: StateFlow<String?> = _lastDevice.asStateFlow()

    /**
     * 安装授权器。
     *
     * 对齐 InstallerX 的 Authorizer 下拉项，决定安装以谁的身份执行。
     */
    private val _installAuthorizer =
        MutableStateFlow(InstallAuthorizer.of(sp.getString(K_AUTHORIZER, null)))
    val installAuthorizer: StateFlow<InstallAuthorizer> = _installAuthorizer.asStateFlow()

    /**
     * 安装选项。
     *
     * 对齐 InstallerX 的 `config_*` 开关，逐项落到 SharedPreferences。
     */
    private val _installOptions = MutableStateFlow(
        InstallOptions(
            allowDowngrade = sp.getBoolean(K_OPT_DOWNGRADE, false),
            allowTestOnly = sp.getBoolean(K_OPT_TESTONLY, false),
            allUsers = sp.getBoolean(K_OPT_ALLUSERS, false),
            autoDelete = sp.getBoolean(K_OPT_AUTODELETE, false),
            installerPackage = sp.getString(K_OPT_INSTALLER, "") ?: "",
            installMode = InstallMode.of(sp.getString(K_OPT_MODE, null)),
        ),
    )
    val installOptions: StateFlow<InstallOptions> = _installOptions.asStateFlow()

    // ---------- 公告 ----------
    /**
     * 公告地址（可选）。
     *
     * 留空则只用本地公告，不做任何网络请求。
     * 填了地址也拉不到时自动回落到本地公告，不会报错。
     */
    private val _noticeUrl = MutableStateFlow(sp.getString(K_NOTICE_URL, "") ?: "")
    val noticeUrl: StateFlow<String> = _noticeUrl.asStateFlow()

    // ---------- 运行日志 ----------
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    /**
     * 日志条数。
     *
     * 单独一条 Int 流：设置页标题只要一个数字，
     * 让它去订阅 600 条字符串的列表，每写一行日志整页都要重组一次，
     * 这在安装过程中（频繁写日志）就是肉眼可见的卡顿。
     */
    private val _logCount = MutableStateFlow(0)
    val logCount: StateFlow<Int> = _logCount.asStateFlow()

    fun setThemeMode(v: ThemeMode) {
        _themeMode.value = v
        sp.edit { putString(K_THEME, v.name) }
    }

    fun setDesignStyle(v: DesignStyle) {
        _designStyle.value = v
        sp.edit { putString(K_STYLE, v.value) }
    }

    fun setFontScale(v: Int) {
        _fontScale.value = v.coerceIn(80, 140)
        sp.edit { putInt(K_FONT, _fontScale.value) }
    }

    fun setHideUnavailable(v: Boolean) {
        _hideUnavailable.value = v
        sp.edit { putBoolean(K_HIDE_UNAVAIL, v) }
    }

    fun setDynamicColor(v: Boolean) {
        _dynamicColor.value = v
        sp.edit { putBoolean(K_DYNAMIC, v) }
    }

    fun setColorSource(v: ColorSource) {
        _colorSource.value = v
        sp.edit { putString(K_COLOR_SOURCE, v.value) }
    }

    fun setWallpaperEnabled(v: Boolean) {
        _wallpaperEnabled.value = v
        sp.edit { putBoolean(K_WALLPAPER, v) }
    }

    fun setWallpaperPath(v: String?) {
        _wallpaperPath.value = v
        sp.edit { if (v == null) remove(K_WALLPAPER_PATH) else putString(K_WALLPAPER_PATH, v) }
    }

    fun setEggUnlocked(v: Boolean) {
        _eggUnlocked.value = v
        sp.edit { putBoolean(K_EGG, v) }
    }

    fun setLocalLoopPort(v: Int) {
        _localLoopPort.value = v.coerceIn(1, 65535)
        sp.edit { putInt(K_LOOP_PORT, _localLoopPort.value) }
    }

    fun setPairPort(v: Int) {
        _pairPort.value = v
        sp.edit { putInt(K_PAIR_PORT, v) }
    }

    fun setConnectPort(v: Int) {
        _connectPort.value = v
        sp.edit { putInt(K_CONNECT_PORT, v) }
    }

    fun setLastDevice(v: String?) {
        _lastDevice.value = v
        sp.edit { if (v == null) remove(K_LAST_DEVICE) else putString(K_LAST_DEVICE, v) }
    }

    fun setInstallAuthorizer(v: InstallAuthorizer) {
        _installAuthorizer.value = v
        sp.edit { putString(K_AUTHORIZER, v.value) }
    }

    fun setInstallOptions(v: InstallOptions) {
        _installOptions.value = v
        sp.edit {
            putBoolean(K_OPT_DOWNGRADE, v.allowDowngrade)
            putBoolean(K_OPT_TESTONLY, v.allowTestOnly)
            putBoolean(K_OPT_ALLUSERS, v.allUsers)
            putBoolean(K_OPT_AUTODELETE, v.autoDelete)
            putString(K_OPT_INSTALLER, v.installerPackage)
            putString(K_OPT_MODE, v.installMode.value)
        }
    }

    fun setNoticeUrl(v: String) {
        _noticeUrl.value = v.trim()
        sp.edit { putString(K_NOTICE_URL, _noticeUrl.value) }
    }

    // ---------- 日志 ----------
    private val logLock = Any()

    fun appendLog(line: String) {
        // 单行硬上限：安装推包时会拼出上百 KB 的命令串，
        // 原样留在内存里既拖慢重组也让日志没法看
        val text = if (line.length > 300) line.take(300) + "…" else line
        synchronized(logLock) {
            val cur = _logLines.value
            val next = if (cur.size >= MAX_LOG) {
                cur.subList(cur.size - MAX_LOG + 1, cur.size) + text
            } else {
                cur + text
            }
            _logLines.value = next
            _logCount.value = next.size
        }
    }

    fun clearLog() {
        synchronized(logLock) {
            _logLines.value = emptyList()
            _logCount.value = 0
        }
    }

    fun logText(): String = _logLines.value.joinToString("\n")

    companion object {
        private const val MAX_LOG = 600

        private const val K_THEME = "theme_mode"
        private const val K_STYLE = "design_style"
        private const val K_FONT = "font_scale"
        private const val K_HIDE_UNAVAIL = "hide_unavailable"
        private const val K_DYNAMIC = "dynamic_color"
        private const val K_COLOR_SOURCE = "color_source"
        private const val K_WALLPAPER = "wallpaper_enabled"
        private const val K_WALLPAPER_PATH = "wallpaper_path"
        private const val K_EGG = "egg_unlocked"
        private const val K_LOOP_PORT = "loop_port"
        private const val K_PAIR_PORT = "pair_port"
        private const val K_CONNECT_PORT = "connect_port"
        private const val K_LAST_DEVICE = "last_device"
        private const val K_NOTICE_URL = "notice_url"
        private const val K_AUTHORIZER = "install_authorizer"
        private const val K_OPT_DOWNGRADE = "opt_allow_downgrade"
        private const val K_OPT_TESTONLY = "opt_allow_test_only"
        private const val K_OPT_ALLUSERS = "opt_all_users"
        private const val K_OPT_AUTODELETE = "opt_auto_delete"
        private const val K_OPT_INSTALLER = "opt_installer_package"
        private const val K_OPT_MODE = "opt_install_mode"
    }
}
