package com.codefox.paperlite.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.ui.RailEntry
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.LocalDesignStyle
import com.codefox.paperlite.ui.theme.Motion
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 风格感知侧边栏。
 *
 * 两套实现的差别不只是皮肤：
 *
 * - **澎湃风格**：Miuix 的 `NavigationRail`，选中项是整块高亮圆角底，
 *   图标在上、文字在下，MIUI 一贯的观感；
 * - **Material 3**：见 [M3NavigationRail]，是按 M3 宽屏导航规范重做的。
 *
 * 两边都带滚动：侧边栏 7 项在 900px 高的屏上放不下，
 * 必须能滚到第 7 项（以前第 7 项直接被裁掉，够不着）。
 */
@Composable
fun SkinNavigationRail(
    entries: List<RailEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalDesignStyle.current == DesignStyle.Material3) {
        M3NavigationRail(
            entries = entries,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = modifier,
        )
        return
    }

    NavigationRail(
        state = top.yukonga.miuix.kmp.basic.rememberNavigationRailState(),
        modifier = modifier.fillMaxHeight(),
        scrollState = rememberScrollState(),
        header = {
            // 顶部品牌位：真实的 APK 图标（与桌面上一模一样的那个），
            // 而不是写死的 "PL" 字块——用户认图标比认字母快得多。
            RailBrand(compact = true)
        },
    ) {
        entries.forEachIndexed { index, entry ->
            NavigationRailItem(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                icon = entry.icon,
                label = entry.label,
            )
        }
    }
}

/**
 * 侧边栏顶部的品牌位。
 *
 * 两套风格共用同一份实现：图标用 [AppIcon]（真实 APK 图标），
 * 下面跟一行小字应用名。
 *
 * Miuix 的 `NavigationRail` 用 `header` 插槽放它，
 * M3 版本是自绘 rail，所以在 rail 内部自己画。
 *
 * 排版与配色随风格切换：Miuix 风格下用 Miuix 的字号与灰阶，
 * M3 风格下用 M3 的 labelSmall，避免两种字体混在同一块侧边栏里。
 */
@Composable
private fun RailBrand(compact: Boolean = false) {
    val m3 = LocalDesignStyle.current == DesignStyle.Material3

    // 连点彩蛋：5 次点击必须落在 1.5 秒的时间窗内，
    // 中途停太久就重新计数，避免用户平时点着玩误解锁。
    var taps by remember { mutableIntStateOf(0) }
    var firstTapAt by remember { mutableLongStateOf(0L) }
    val unlocked by rememberEggUnlocked()

    Column(
        modifier = Modifier.padding(bottom = if (compact) 6.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - firstTapAt > EasterEgg.TAP_WINDOW_MS) {
                    taps = 0
                    firstTapAt = now
                }
                taps++
                if (taps >= EasterEgg.REQUIRED_TAPS) {
                    taps = 0
                    firstTapAt = 0L
                    EasterEgg.setUnlocked(!unlocked)
                }
            },
        ) {
            AppIcon(
                size = if (compact) 32.dp else 40.dp,
                corner = if (compact) 16.dp else 20.dp,
            )
        }
        Spacer(Modifier.height(3.dp))
        if (m3) {
            androidx.compose.material3.Text(
                text = "PaperLite",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            top.yukonga.miuix.kmp.basic.Text(
                text = "PaperLite",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 侧边栏分组。
 *
 * M3 的宽屏导航栏（NavigationRail）允许把目的地按语义分组，
 * 每组配一个小标题——这在 7 个目的地这个量级上是必要的，
 * 否则「概览 / 应用 / 超级用户 / Shizuku / 无障碍 / 命令行 / 设置」
 * 平铺下来用户根本分不出哪几个是一类。
 */
enum class RailGroup(val title: String) {
    Overview("概览"),
    Manage("管理"),
    Privileged("提权"),
    System("系统"),
}

/**
 * M3 风格的宽屏导航栏。
 *
 * ## 与 Miuix 版的差别
 *
 * 1. **分组标题**：目的地按 [RailGroup] 分组，每组一个 12sp 的小标题。
 * 2. **药丸指示器**：选中项是 M3 的 pill 形 indicator（不是整块高亮底），
 *    药丸只包住图标，文字另行着色——这是 M3 与 MIUI 最明显的形态差别。
 * 3. **等宽布局**：每一项高度一致、图标居中，文字在下方。
 * 4. **顶部品牌区**：最上面放应用名与图标，
 *    符合 M3 对导航栏顶部的处理，也让人知道自己在哪个应用里。
 * 5. **宽度更宽**：M3 的 rail 是 80dp 起步，这里给 96dp 让中文标签放得下。
 */
@Composable
private fun M3NavigationRail(
    entries: List<RailEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val shapes = androidx.compose.material3.MaterialTheme.shapes

    Row(
        modifier = modifier
            .fillMaxHeight()
            .width(96.dp)
            .background(scheme.surfaceContainer)
            .padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- 顶部品牌区 ----
            RailBrand()

            // ---- 分组导航 ----
            var lastGroup: RailGroup? = null
            entries.forEachIndexed { index, entry ->
                val group = groupOf(entry.destination)
                if (group != null && group != lastGroup) {
                    if (lastGroup != null) Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Text(
                        text = group.title,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, bottom = 3.dp),
                        maxLines = 1,
                    )
                    lastGroup = group
                }
                M3RailItem(
                    entry = entry,
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * M3 导航项：药丸指示器 + 图标 + 文字。
 *
 * 药丸只包住图标（56×32dp），这是 M3 的规范：
 * 指示器是「图标背后的胶囊」，文字靠颜色区分选中与否。
 */
@Composable
private fun M3RailItem(
    entry: RailEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme

    val indicator by animateColorAsState(
        targetValue = if (selected) scheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.enterEasing),
        label = "rail-indicator",
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.enterEasing),
        label = "rail-icon",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.enterEasing),
        label = "rail-label",
    )
    // 药丸横向拉伸一点点：M3 Expressive 的指示器会随选中动画微微变宽
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.86f,
        animationSpec = tween(Motion.MEDIUM, easing = Motion.emphasized),
        label = "rail-scale",
    )

    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .width((56 * scale).dp)
                .clip(RoundedCornerShape(16.dp))
                .background(indicator),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        androidx.compose.material3.Text(
            text = entry.label,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 目的地属于哪个分组。 */
private fun groupOf(destination: com.codefox.paperlite.ui.Destination): RailGroup? =
    when (destination) {
        com.codefox.paperlite.ui.Destination.Home -> RailGroup.Overview
        com.codefox.paperlite.ui.Destination.Apps -> RailGroup.Manage
        com.codefox.paperlite.ui.Destination.Superuser,
        com.codefox.paperlite.ui.Destination.Shizuku,
        com.codefox.paperlite.ui.Destination.Accessibility,
        -> RailGroup.Privileged

        com.codefox.paperlite.ui.Destination.Terminal,
        com.codefox.paperlite.ui.Destination.Settings,
        -> RailGroup.System
    }
