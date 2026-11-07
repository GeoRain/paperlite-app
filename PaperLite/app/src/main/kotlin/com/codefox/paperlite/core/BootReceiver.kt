package com.codefox.paperlite.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机接收器。
 *
 * 抄「无障碍管理器」的自动提权模型：设备重启后开机自启时，
 * 如果开了「开机即提权」，这里就自动跑一次漏洞利用。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AutoRoot.autoRootOnBoot(context)) return
        val app = context.applicationContext
        val channels = (app as? com.codefox.paperlite.App)?.channels ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = DirtyFrag.run(app, channels)
                if (result.success) {
                    AutoActivate.activate(app, channels)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
