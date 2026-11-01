package com.codefox.paperlite

import android.app.Application
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.data.Settings

/**
 * 进程级单例容器。
 *
 * 所有管理器都在这里创建一次，Activity 重组时不会重复初始化，
 * 也避免把 Context 泄漏进 Compose。
 */
class App : Application() {

    lateinit var settings: Settings
        private set

    lateinit var channels: ChannelManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        channels = ChannelManager(this, settings)
        // 先装崩溃落盘，再写启动日志：之后任何闪退都会留下 last.txt
        com.codefox.paperlite.core.CrashGuard.install(this)
        settings.appendLog("PaperLite 启动，版本 ${BuildConfig.VERSION_NAME}")
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
