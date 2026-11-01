package com.codefox.paperlite.core.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 接收系统包安装器的结果回执。
 *
 * 走 `IntentSender` 拉起安装界面时，把 PendingIntent 的 action 设成本 receiver，
 * 系统装完（或失败）会回一个带 `EXTRA_STATUS` 的广播。
 *
 * 注意：安装结果常量在 [PackageInstaller] 上（`STATUS_*`），
 * 不是 `PackageManager` 的 `INSTALL_*`——后者是 `pm install` 命令的返回码，两套不能混用。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_PACKAGE)
            ?: ""
        val ok = status == PackageInstaller.STATUS_SUCCESS
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        InstallResult.report(
            InstallResult.Event(
                packageName = pkg,
                success = ok,
                message = if (ok) "安装成功" else describe(status, detail),
            ),
        )
    }

    private fun describe(status: Int, detail: String?): String {
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已取消"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被拦截（可能被设备策略或安全软件阻止）"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装失败：与已安装应用冲突，需先卸载"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "安装失败：与当前系统不兼容"
            PackageInstaller.STATUS_FAILURE_INVALID -> "安装失败：安装包无效"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "安装失败：存储空间不足"
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "需要你手动确认安装"
            Int.MIN_VALUE -> "未收到安装结果"
            else -> "安装失败：错误码 $status"
        }
        return if (detail.isNullOrBlank()) base else "$base（$detail）"
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
    }
}

/** 安装结果总线。 */
object InstallResult {
    data class Event(val packageName: String, val success: Boolean, val message: String)

    private val _last = MutableStateFlow<Event?>(null)
    val last: StateFlow<Event?> = _last.asStateFlow()

    fun report(e: Event) {
        _last.value = e
    }

    fun consume() {
        _last.value = null
    }
}
