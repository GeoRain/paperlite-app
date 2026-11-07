package com.codefox.paperlite.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.codefox.paperlite.core.channel.PairingAccessibilityService

/**
 * 自动提权（抄「无障碍管理器」的行为模型）。
 *
 * 那个应用的核心是：无障碍服务监听**开屏与解锁**事件，
 * 在设备解锁的瞬间触发提权流程——因为内核漏洞利用要求设备处于已解锁状态。
 *
 * PaperLite 这边实现同一套逻辑：
 * - 无障碍服务收到解锁事件 → 自动跑 DirtyFrag；
 * - 开机广播 → 同样流程；
 * - 检测无障碍服务是否被系统禁用，被禁用就给出重新启用的提示。
 *
 * 开关状态存在 SharedPreferences 里。
 */
object AutoRoot {

    private const val PREFS = "paperlite_autoroot"
    private const val K_UNLOCK = "auto_root_on_unlock"
    private const val K_BOOT = "auto_root_on_boot"
    private const val K_KEEP_ALIVE = "keep_alive"

    /** PaperLite 自己的无障碍服务组件名。 */
    const val SERVICE_COMPONENT = "com.codefox.paperlite/com.codefox.paperlite.core.channel.PairingAccessibilityService"

    // ------------------------------------------------------------------
    // 开关
    // ------------------------------------------------------------------

    fun autoRootOnUnlock(context: Context): Boolean =
        prefs(context).getBoolean(K_UNLOCK, false)

    fun setAutoRootOnUnlock(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_UNLOCK, on).apply()
    }

    fun autoRootOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(K_BOOT, false)

    fun setAutoRootOnBoot(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_BOOT, on).apply()
    }

    fun keepAliveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(K_KEEP_ALIVE, false)

    fun setKeepAlive(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(K_KEEP_ALIVE, on).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // 无障碍服务状态
    // ------------------------------------------------------------------

    /** PaperLite 的无障碍服务是否已启用。 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = enabledServices(context) ?: return false
        return enabled.contains(SERVICE_COMPONENT, ignoreCase = true) ||
            enabled.contains("com.codefox.paperlite", ignoreCase = true)
    }

    /** 系统里已启用的无障碍服务列表。 */
    fun enabledServices(context: Context): String? = runCatching {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        if (raw.isNullOrBlank()) null else raw
    }.getOrNull()

    /** 是否被系统禁用了（组件存在但没在启用列表里）。 */
    fun isServiceDisabled(context: Context): Boolean {
        val component = android.content.ComponentName(context, PairingAccessibilityService::class.java)
        val state = runCatching {
            context.packageManager.getServiceInfo(component, 0).enabled
        }.getOrDefault(true)
        return !state
    }

    /**
     * 解锁事件触发提权。
     *
     * 由无障碍服务在收到窗口状态变化时调用；只在开了开关、还没 root 时跑利用。
     * ksud 随包内置，不再需要先挑一个设备上装好的 SU 管理器。
     */
    suspend fun onDeviceUnlocked(context: Context, channels: ChannelManager) {
        if (!autoRootOnUnlock(context)) return
        if (RootManager.detect(channels).available) return
        val result = DirtyFrag.run(context, channels)
        if (result.success) {
            AutoActivate.activate(context, channels)
        }
    }
}
