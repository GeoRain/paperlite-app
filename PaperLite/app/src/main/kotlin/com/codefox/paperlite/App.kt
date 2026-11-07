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
        // 必须在任何业务初始化之前装好崩溃落盘：
        // ChannelManager 构造里加载密钥 / 建立通道状态，某些 Android 12 ROM 上
        // 可能直接抛异常，如果这里在 install 之前崩，进程会无声无息地死掉，
        // 连 last.txt 都写不出来，排查就只剩「打开就崩」四个字。
        com.codefox.paperlite.core.CrashGuard.install(this)
        settings = Settings(this)
        channels = ChannelManager(this, settings)
        settings.appendLog("PaperLite 启动，版本 ${BuildConfig.VERSION_NAME}")
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
