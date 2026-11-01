package com.codefox.paperlite.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.core.content.edit
import com.codefox.paperlite.ui.theme.ThemeMode

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

    private val _fontScale = MutableStateFlow(sp.getInt(K_FONT, 100))
    val fontScale: StateFlow<Int> = _fontScale.asStateFlow()

    /** 隐藏不可用按钮：通道没就绪时把按钮藏起来而不是置灰。 */
    private val _hideUnavailable = MutableStateFlow(sp.getBoolean(K_HIDE_UNAVAIL, false))
    val hideUnavailable: StateFlow<Boolean> = _hideUnavailable.asStateFlow()

    private val _dynamicColor = MutableStateFlow(sp.getBoolean(K_DYNAMIC, false))
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

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

    fun setThemeMode(v: ThemeMode) {
        _themeMode.value = v
        sp.edit { putString(K_THEME, v.name) }
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

    fun setNoticeUrl(v: String) {
        _noticeUrl.value = v.trim()
        sp.edit { putString(K_NOTICE_URL, _noticeUrl.value) }
    }

    // ---------- 日志 ----------
    private val logLock = Any()

    fun appendLog(line: String) {
        synchronized(logLock) {
            val cur = _logLines.value
            val next = if (cur.size >= MAX_LOG) cur.subList(cur.size - MAX_LOG + 1, cur.size) + line else cur + line
            _logLines.value = next
        }
    }

    fun clearLog() {
        _logLines.value = emptyList()
    }

    fun logText(): String = _logLines.value.joinToString("\n")

    companion object {
        private const val MAX_LOG = 600

        private const val K_THEME = "theme_mode"
        private const val K_FONT = "font_scale"
        private const val K_HIDE_UNAVAIL = "hide_unavailable"
        private const val K_DYNAMIC = "dynamic_color"
        private const val K_LOOP_PORT = "loop_port"
        private const val K_PAIR_PORT = "pair_port"
        private const val K_CONNECT_PORT = "connect_port"
        private const val K_LAST_DEVICE = "last_device"
        private const val K_NOTICE_URL = "notice_url"
    }
}
