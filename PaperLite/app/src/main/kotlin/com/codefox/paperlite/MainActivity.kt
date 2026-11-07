package com.codefox.paperlite

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.ui.PaperLiteApp
import com.codefox.paperlite.ui.theme.BrandColors
import com.codefox.paperlite.ui.theme.ColorSource
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.PaperLiteTheme
import com.codefox.paperlite.ui.theme.WallpaperStore
import com.codefox.paperlite.ui.theme.resolveDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 唯一 Activity。
 *
 * 顶部白条消除的三处配合：
 * 1. 主题用 `Theme.DeviceDefault.NoActionBar`，系统不再画 ActionBar / 标题栏
 * 2. [enableEdgeToEdge] 让内容延伸到状态栏与导航栏下方，状态栏透明只留时间电量
 * 3. 页面标题全部由 Compose 自绘，见 `ui/components/PageHeader.kt`
 *
 * 壁纸与取色在这里汇合：读图 → 提主色 → 生成品牌色 → 注入主题，
 * 同一张位图再交给 [PaperLiteApp] 画背景，保证「看到的背景」与「取到的颜色」
 * 永远来自同一张图，不会换了图配色却没跟着变。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 官方推荐：enableEdgeToEdge 在 super.onCreate 之前调用，
        // 这样首帧（含启动画面过渡）就已经是全面屏布局，不会先白一下再跳
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as App

        setContent {
            val themeMode by app.settings.themeMode.collectAsStateWithLifecycle()
            val designStyle by app.settings.designStyle.collectAsStateWithLifecycle()
            val colorSource by app.settings.colorSource.collectAsStateWithLifecycle()
            val wallpaperEnabled by app.settings.wallpaperEnabled.collectAsStateWithLifecycle()
            val wallpaperPath by app.settings.wallpaperPath.collectAsStateWithLifecycle()
            val dark = resolveDark(themeMode)

            // 位图只在「路径变化」时重读；配色来源切换不触发重复 IO
            val wallpaper by produceState<Pair<Bitmap?, ImageBitmap?>?>(
                initialValue = null,
                key1 = wallpaperPath,
            ) {
                value = withContext(Dispatchers.IO) {
                    val bitmap = WallpaperStore.load(this@MainActivity, wallpaperPath)
                    bitmap to bitmap?.asImageBitmap()
                }
            }

            // 从位图取色（或读系统动态色），全部在 IO 线程算。
            // 依赖项里必须带**位图本身**：位图是异步读进来的，
            // 只盯路径的话首次加载完成时不会重算，取色会一直回退小米蓝。
            // 用 LaunchedEffect 而不是 produceState，是因为它有 4 个依赖，
            // 而 produceState 最多只给 3 个 key。
            var brand by remember { mutableStateOf<BrandColors?>(null) }
            LaunchedEffect(colorSource, wallpaper?.first, dark, designStyle) {
                brand = withContext(Dispatchers.IO) {
                    val m3 = designStyle == DesignStyle.Material3
                    when (colorSource) {
                        ColorSource.Xiaomi -> BrandColors.xiaomi(dark, m3)
                        ColorSource.Wallpaper -> wallpaper?.first?.let {
                            BrandColors.fromBitmap(it, dark, m3)
                        } ?: BrandColors.xiaomi(dark, m3)
                        ColorSource.SystemMonet ->
                            BrandColors.fromSystem(this@MainActivity, dark, m3)
                                ?: BrandColors.xiaomi(dark, m3)
                    }
                }
            }

            PaperLiteTheme(dark = dark, style = designStyle, brand = brand) {
                PaperLiteApp(
                    settings = app.settings,
                    channels = app.channels,
                    darkTheme = dark,
                    wallpaper = if (wallpaperEnabled) wallpaper?.second else null,
                )
            }
        }
    }
}
