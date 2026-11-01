package com.codefox.paperlite.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.data.Settings
import com.codefox.paperlite.ui.pages.AboutPage
import com.codefox.paperlite.ui.pages.AppsPage
import com.codefox.paperlite.ui.pages.DevicePage
import com.codefox.paperlite.ui.pages.HomePage
import com.codefox.paperlite.ui.pages.InstallPage
import com.codefox.paperlite.ui.pages.PairingPage
import com.codefox.paperlite.ui.pages.NoticePage
import com.codefox.paperlite.ui.pages.SettingsPage
import com.codefox.paperlite.ui.pages.TerminalPage
import com.codefox.paperlite.ui.pages.UninstallPage
import com.codefox.paperlite.ui.theme.pageBackground
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState

/**
 * PaperLite 主界面。
 *
 * 侧边栏严格遵循 Miuix 宽屏导航组件的用法：
 * - 组件：`NavigationRail` + `NavigationRailItem`（top.yukonga.miuix.kmp.basic）
 * - 布局：`Row` 并排 —— 左侧 rail，主内容区是第二个子项并 `weight(1f)`
 * - 模式：可切换，`rememberNavigationRailState()` + `state = railState`
 * - 选中：单一 `selectedIndex`，`selected = selectedIndex == index`
 *
 * 动画：
 * - 启动时先由 [SplashOverlay] 显示 `PaperLite` 大字并渐隐
 * - 主界面经由 [EntranceContainer] 推近入场
 * - 切页用 `AnimatedContent`：缓出淡入 + 小幅横向位移，退场只做淡出
 *
 * 页面组织：**一个功能一个页面**，共 10 个目的地，全部可用。
 */
@Composable
fun PaperLiteApp(
    settings: Settings,
    channels: ChannelManager,
    darkTheme: Boolean,
) {
    // 图标列表只构造一次
    val railEntries = remember { buildRailEntries() }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val railState = rememberNavigationRailState()
    var splashDone by rememberSaveable { mutableStateOf(false) }
    val bg = pageBackground()

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        EntranceContainer(visible = splashDone) {
            // Scaffold 负责把内容让开状态栏 / 导航栏 / 刘海，
            // 页面内部不再硬算 inset，DPI 异常的机器上也不会再画到系统栏下面去。
            Scaffold(
                containerColor = bg,
                modifier = Modifier.fillMaxSize(),
            ) { padding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    NavigationRail(
                        state = railState,
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        railEntries.forEachIndexed { index, entry ->
                            NavigationRailItem(
                                selected = selectedIndex == index,
                                onClick = { selectedIndex = index },
                                icon = entry.icon,
                                label = entry.label,
                            )
                        }
                    }

                    // 主内容区，占剩余空间
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val destination = railEntries
                            .getOrNull(selectedIndex.coerceIn(0, railEntries.lastIndex))
                            ?.destination ?: Destination.Home

                        AnimatedContent(
                            targetState = destination,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                val dx = if (forward) 18 else -18
                                // 丝滑优先：入场缓出长一点、位移小一点、退出只做淡出，
                                // 避免两页同时位移/缩放产生的「抖一下」观感
                                (fadeIn(tween(340, easing = LinearOutSlowInEasing)) +
                                    slideInHorizontally(
                                        tween(380, easing = FastOutSlowInEasing),
                                    ) { dx } +
                                    scaleIn(tween(380, easing = LinearOutSlowInEasing), initialScale = 0.994f))
                                    .togetherWith(
                                        fadeOut(tween(220, easing = FastOutSlowInEasing)),
                                    )
                            },
                            label = "page",
                        ) { dest ->
                            when (dest) {
                                Destination.Home -> HomePage(channels = channels)

                                Destination.Apps -> AppsPage(channels = channels)

                                Destination.Install -> InstallPage(channels = channels)

                                Destination.Uninstall -> UninstallPage(channels = channels)

                                Destination.Device -> DevicePage()

                                Destination.Terminal -> TerminalPage(channels = channels)

                                Destination.Pairing -> PairingPage(
                                    channels = channels,
                                    settings = settings,
                                )

                                Destination.Notice -> NoticePage(settings = settings)

                                Destination.Settings -> SettingsPage(
                                    settings = settings,
                                    darkTheme = darkTheme,
                                )

                                Destination.About -> AboutPage()
                            }
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
