package com.codefox.paperlite.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.data.Settings
import com.codefox.paperlite.ui.components.EasterEgg
import com.codefox.paperlite.ui.components.EggIcons
import com.codefox.paperlite.ui.components.HiddenEntry
import com.codefox.paperlite.ui.components.LocalHiddenMenu
import com.codefox.paperlite.ui.theme.ColorSource
import com.codefox.paperlite.ui.pages.AccessibilityPage
import com.codefox.paperlite.ui.pages.AppsPage
import com.codefox.paperlite.ui.pages.HomePage
import com.codefox.paperlite.ui.pages.SettingsPage
import com.codefox.paperlite.ui.pages.ShizukuPage
import com.codefox.paperlite.ui.pages.SuperuserPage
import com.codefox.paperlite.ui.pages.TerminalPage
import com.codefox.paperlite.ui.theme.Motion
import com.codefox.paperlite.ui.components.SkinNavigationRail
import com.codefox.paperlite.ui.theme.pageBackground
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * PaperLite 主界面。
 *
 * 侧边栏严格遵循 Miuix 宽屏导航组件的用法：
 * - 组件：`NavigationRail` + `NavigationRailItem`（top.yukonga.miuix.kmp.basic）
 * - 布局：`Row` 并排 —— 左侧 rail，主内容区是第二个子项并 `weight(1f)`
 * - 模式：可切换，`rememberNavigationRailState()` + `state = railState`
 * - 选中：单一 `selectedIndex`，`selected = selectedIndex == index`
 *
 * ## 切页动画
 *
 * 页面之间保留「淡入 + 轻微横向位移」的过渡（220ms），
 * 让换页有方向感。
 *
 * 但**不做整页缩放**：旧版是「淡入 + 位移 + 缩放」一起上，
 * 缩放要给整页建离屏图层，两个整页 LazyColumn 同时进动画管线，
 * 在低端 Pad 上就是掉帧、点一下要顿半拍。
 * 现在位移距离压到很小、时长压到 220ms，观感是「跟着走」而不是「弹一下」。
 */
@Composable
fun PaperLiteApp(
    settings: Settings,
    channels: ChannelManager,
    darkTheme: Boolean,
    /** 应用内背景壁纸；null 表示不画（用纯色底）。 */
    wallpaper: ImageBitmap? = null,
) {
    // 图标列表只构造一次
    val railEntries = remember { buildRailEntries() }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var splashDone by rememberSaveable { mutableStateOf(false) }
    val bg = pageBackground()
    val context = LocalContext.current

    // 彩蛋状态：启动时从设置恢复，解锁/收起后写回，重启不丢
    LaunchedEffect(Unit) {
        EasterEgg.restore(settings.eggUnlocked.value)
    }
    LaunchedEffect(Unit) {
        EasterEgg.unlocked.collect { settings.setEggUnlocked(it) }
    }

    // 隐藏菜单项：三张图各绑定一个真实动作
    val hiddenMenu = remember(settings, context) {
        listOf(
            HiddenEntry(
                title = "切换背景",
                summary = "显示 / 隐藏应用内壁纸",
                iconRes = EggIcons.Runner,
                onClick = {
                    settings.setWallpaperEnabled(!settings.wallpaperEnabled.value)
                },
            ),
            HiddenEntry(
                title = "换一套配色",
                summary = "在小米蓝 / 壁纸取色 / 系统动态色间轮换",
                iconRes = EggIcons.Card,
                onClick = {
                    val all = ColorSource.available()
                    val cur = all.indexOf(settings.colorSource.value)
                    settings.setColorSource(all[(cur + 1) % all.size])
                },
            ),
            HiddenEntry(
                title = "JCCode",
                summary = "打开 app.aibangos.xyz",
                iconRes = EggIcons.Nest,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://app.aibangos.xyz"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            ),
        )
    }

    // 通道探测放在应用级只做一次：以前是「每次进概览页都探」，
    // 而进其它页又从来没探过，导致安装时拿不到 shell 身份。
    LaunchedEffect(Unit) {
        if (channels.needsProbe()) channels.probeAll()
    }

    CompositionLocalProvider(LocalHiddenMenu provides hiddenMenu) {
    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        // 壁纸铺满 + 一层遮罩。
        // 遮罩是必需的：默认图是大片白云，不压暗的话白底上的白字完全看不清。
        // 深色模式压得更狠一点，浅色模式留多一点图的通透感。
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (darkTheme) Color.Black.copy(alpha = 0.62f)
                        else Color.White.copy(alpha = 0.68f),
                    ),
            )
        }
        // Scaffold 负责把内容让开状态栏 / 导航栏 / 刘海，
        // 页面内部不再硬算 inset，DPI 异常的机器上也不会再画到系统栏下面去。
        Scaffold(
            // 透明才能透出底下那层壁纸；有壁纸时内容区自己不铺底，
            // 卡片/输入框这些容器仍然是不透明的，正文可读性不受影响。
            containerColor = if (wallpaper != null) Color.Transparent else bg,
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                SkinNavigationRail(
                    entries = railEntries,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                )

                // 主内容区，占剩余空间
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val destination = railEntries
                        .getOrNull(selectedIndex.coerceIn(0, railEntries.lastIndex))
                        ?.destination ?: Destination.Home

                    // 页面之间的跳转入口（概览页的快捷卡片、设置里的引导都会用）
                    val open: (Destination) -> Unit = { dest ->
                        val index = railEntries.indexOfFirst { it.destination == dest }
                        if (index >= 0) selectedIndex = index
                    }

                    // 页面切换动画：**淡入 + 轻微横向位移**。
                    //
                    // 旧版做过「淡入 + 位移 + 整页缩放」，低端 Pad 上直接掉帧——
                    // 缩放要给整页建离屏图层，两个整页 LazyColumn 同时进动画管线。
                    // 现在保留过渡感但去掉缩放：位移距离压到 36dp，
                    // 动画时长 220ms，观感是「跟着手指走」而不是「弹一下」。
                    AnimatedContent(
                        targetState = destination,
                        transitionSpec = {
                            // 淡入 + 轻微横向位移 + 高度自适应。
                            // 位移距离只有 1/12 屏宽：不明显到让人觉得在等，
                            // 又足够让人看出「换页了」。
                            (fadeIn(Motion.enter(Motion.LONG)) +
                                slideInHorizontally(Motion.enterOffset(Motion.LONG)) { it / 12 })
                                .togetherWith(
                                    fadeOut(Motion.exit(Motion.QUICK)) +
                                        slideOutHorizontally(Motion.exitOffset(Motion.QUICK)) { -it / 24 },
                                )
                        },
                        label = "page-transition",
                    ) { dest ->
                        when (dest) {
                            Destination.Home -> HomePage(channels = channels, onOpen = open)

                            Destination.Apps -> AppsPage(channels = channels)

                            Destination.Superuser -> SuperuserPage(
                                channels = channels,
                                settings = settings,
                            )

                            Destination.Shizuku -> ShizukuPage(channels = channels)

                            Destination.Accessibility -> AccessibilityPage(channels = channels)


                            Destination.Terminal -> TerminalPage(channels = channels)

                            Destination.Settings -> SettingsPage(
                                settings = settings,
                                darkTheme = darkTheme,
                            )
                        }
                    }
                }
            }
        }

        if (!splashDone) {
            SplashOverlay(onFinished = { splashDone = true })
        }
    }
    }
}
