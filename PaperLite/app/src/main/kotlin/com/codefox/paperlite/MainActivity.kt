package com.codefox.paperlite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.ui.PaperLiteApp
import com.codefox.paperlite.ui.theme.resolveDark
import com.codefox.paperlite.ui.theme.PaperLiteTheme

/**
 * 唯一 Activity。
 *
 * 顶部白条消除的三处配合：
 * 1. 主题用 `Theme.DeviceDefault.NoActionBar`，系统不再画 ActionBar / 标题栏
 * 2. [enableEdgeToEdge] 让内容延伸到状态栏与导航栏下方，状态栏透明只留时间电量
 * 3. 页面标题全部由 Compose 自绘，见 `ui/components/PageHeader.kt`
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as App

        setContent {
            val themeMode by app.settings.themeMode.collectAsStateWithLifecycle()
            val dark = resolveDark(themeMode)
            PaperLiteTheme(dark = dark) {
                PaperLiteApp(
                    settings = app.settings,
                    channels = app.channels,
                    darkTheme = dark,
                )
            }
        }
    }
}
