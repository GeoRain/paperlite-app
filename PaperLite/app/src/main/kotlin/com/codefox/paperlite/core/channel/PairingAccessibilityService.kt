package com.codefox.paperlite.core.channel

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配对码自动填写。
 *
 * 只做一件事：在系统「使用配对码配对设备」页面上，找到 6 位配对码输入框，
 * 把 [pendingCode] 填进去并点击「配对」。
 *
 * 隐私边界：不读取任何其他页面的内容，不采集文本，识别到一次后立即清空。
 */
class PairingAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 解锁事件：抄「无障碍管理器」的做法，设备一解锁就自动尝试提权
        if (event != null &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            isUnlockEvent(event)
        ) {
            triggerAutoRoot()
        }

        val code = pendingCode ?: return
        if (event == null) return
        // 只在窗口内容变化时尝试
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val root = rootInActiveWindow ?: return
        if (fillCode(root, code)) {
            pendingCode = null
            lastResult = "已自动填写配对码"
        }
        // API 33 起 AccessibilityNodeInfo 不再需要手动 recycle，这里是老版本兜底
        @Suppress("DEPRECATION")
        runCatching { root.recycle() }
    }

    /** 判断是不是锁屏/开屏这类事件。 */
    private fun isUnlockEvent(event: AccessibilityEvent): Boolean {
        val type = event.className?.toString().orEmpty()
        return type.contains("Keyguard", true) ||
            type.contains("StatusBar", true) ||
            type.contains("SystemUI", true) ||
            type.contains("LockScreen", true)
    }

    /** 触发一次自动提权（失败不打扰用户，只写日志）。 */
    private fun triggerAutoRoot() {
        val now = System.currentTimeMillis()
        if (now - lastAutoRootAt < 60_000L) return
        lastAutoRootAt = now
        val app = applicationContext
        if (!com.codefox.paperlite.core.AutoRoot.autoRootOnUnlock(app)) return
        val channels = (app as? com.codefox.paperlite.App)?.channels ?: return
        CoroutineScope(Dispatchers.IO).launch {
            com.codefox.paperlite.core.AutoRoot.onDeviceUnlocked(app, channels)
        }
    }

    /** 递归查找 EditText 并填入配对码，随后点击确认按钮。 */
    private fun fillCode(root: AccessibilityNodeInfo, code: String): Boolean {
        val edit = findEditable(root) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            code,
        )
        val set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!set) {
            // 退回到逐字粘贴
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val clip = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            clip?.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
            edit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        // 点击「配对 / 确认 / 确定」——从窗口根节点找，而不是从 EditText 往上找
        val button = findButton(root)
        button?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return true
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.contains("EditText") == true) {
            if (node.isEnabled && node.isVisibleToUser) return node
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val hit = findEditable(child)
            if (hit != null) return hit
        }
        return null
    }

    private fun findButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = node.text?.toString().orEmpty()
        if (node.isClickable && (label.contains("配对") || label.contains("确认") ||
                label.contains("确定") || label.equals("Pair", true) ||
                label.equals("OK", true))
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val hit = findButton(child)
            if (hit != null) return hit
        }
        return null
    }

    private var lastAutoRootAt = 0L

    companion object {
        @Volatile
        private var instance: PairingAccessibilityService? = null

        /** 等待填写的配对码。 */
        @Volatile
        var pendingCode: String? = null

        /** 最近一次识别结果，UI 用来提示。 */
        @Volatile
        var lastResult: String? = null

        private val armed = AtomicBoolean(false)

        fun isConnected(): Boolean = instance != null

        /** 准备填写：把识别到的配对码投递给无障碍服务。 */
        fun arm(code: String) {
            pendingCode = code
            armed.set(true)
        }

        fun disarm() {
            pendingCode = null
            armed.set(false)
        }

        /** 拉起系统的无障碍设置页，让用户手动开启。 */
        fun settingsIntent(): android.content.Intent =
            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
