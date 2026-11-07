package com.codefox.paperlite.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.LocalDesignStyle
import com.codefox.paperlite.ui.theme.LocalShapes
import com.codefox.paperlite.ui.theme.Motion
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 汉堡菜单。
 *
 * ## 为什么自己写而不用 DropdownMenu
 *
 * 之前用的是 material3 的 `DropdownMenu`，它有内置的弹出动画，但：
 *
 * 1. **收起的动画不统一**——Material 3 的 DropdownMenu 关掉时是「立即消失」，
 *    跟应用其它地方的过渡对不上，用户会看到「弹出来有动画、收回去没有」；
 * 2. **配色是 M3 的**——切到澎湃风格时它仍是 M3 的表面色，两套风格混搭。
 *
 * 现在改成自绘 Popup：弹出是「淡入 + 轻微缩放 + 下滑」，
 * 收起是「淡出 + 缩回 + 上收」，两套风格都统一到 [Motion] 的规格上。
 *
 * ## 退场动画怎么保证播完
 *
 * `Popup` 一旦从组合树里移除就整体消失，里面的 `AnimatedVisibility` 根本没机会播完。
 * 所以这里用两层状态：`open` 控制内容显隐，`mounted` 控制 Popup 本身的生命周期。
 * 收起时先把 `open` 置 false，等退场动画结束再把 `mounted` 置 false。
 *
 * 用途：侧边栏每一项只代表一个大功能，**该功能内部的子功能用这个菜单收纳**，
 * 不再往页面上堆分段标签。
 */
@Composable
fun HamburgerMenu(
    items: List<MenuEntry>,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    var open by remember { mutableStateOf(false) }
    var mounted by remember { mutableStateOf(false) }
    val shapes = LocalShapes.current
    val m3 = LocalDesignStyle.current == DesignStyle.Material3

    // 彩蛋：解锁后把隐藏项追加到每一个汉堡菜单末尾。
    // 放在组件里统一做，页面不用各自挂一遍，也不会漏掉某一页。
    val unlocked by rememberEggUnlocked()
    val hidden = LocalHiddenMenu.current
    val allItems = if (unlocked) items + hidden.map {
        MenuEntry(
            title = it.title,
            summary = it.summary,
            iconRes = it.iconRes,
            onClick = it.onClick,
        )
    } else items

    // 退场动画播完才卸载 Popup
    LaunchedEffect(open) {
        if (open) {
            mounted = true
        } else if (mounted) {
            kotlinx.coroutines.delay(Motion.QUICK + 60L)
            mounted = false
        }
    }

    Box(modifier = modifier) {
        // 图标按钮：40dp 触控目标 + 按下缩放反馈
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (pressed) 0.86f else 1f,
            animationSpec = tween(Motion.QUICK, easing = Motion.enterEasing),
            label = "menu-press",
        )
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(50))
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                ) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = MiuixIcons.More,
                contentDescription = "菜单",
                tint = tint ?: MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.size(22.dp),
            )
        }

        if (mounted) {
            Popup(
                popupPositionProvider = MenuPositionProvider(),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                MenuPanel(
                    items = allItems,
                    visible = open,
                    m3 = m3,
                    corner = shapes.cornerMedium,
                    onPick = {
                        open = false
                        it.onClick()
                    },
                )
            }
        }
    }
}

/**
 * 菜单面板。
 *
 * 挂在 Popup 里，靠近触发按钮的右上角。
 */
@Composable
private fun MenuPanel(
    items: List<MenuEntry>,
    visible: Boolean,
    m3: Boolean,
    corner: Dp,
    onPick: (MenuEntry) -> Unit,
) {
    Box(modifier = Modifier.offset(y = 4.dp)) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(Motion.QUICK, easing = Motion.enterEasing)) +
                scaleIn(
                    tween(Motion.MEDIUM, easing = Motion.emphasized),
                    initialScale = 0.86f,
                ) +
                slideInVertically(tween(Motion.MEDIUM, easing = Motion.emphasized)) { -it / 6 },
            exit = fadeOut(tween(Motion.QUICK, easing = Motion.exitEasing)) +
                scaleOut(
                    tween(Motion.QUICK, easing = Motion.exitEasing),
                    targetScale = 0.92f,
                ) +
                slideOutVertically(tween(Motion.QUICK, easing = Motion.exitEasing)) { -it / 6 },
        ) {
            Column(
                modifier = Modifier
                    .width(248.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(
                        if (m3) {
                            androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MiuixTheme.colorScheme.surfaceContainerHighest
                        },
                    )
                    .then(
                        if (m3) {
                            Modifier.border(
                                width = 1.dp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(corner),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 4.dp),
            ) {
                items.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(corner / 2))
                            .clickable { onPick(entry) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 普通菜单项没有图标，隐藏项才有——这也是解锁后
                        // 一眼能看出「多了点什么」的地方
                        if (entry.iconRes != null) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(entry.iconRes),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        SkinMenuItemText(title = entry.title, summary = entry.summary)
                    }
                }
            }
        }
    }
}

/** 菜单项。iconRes 非空时左侧显示一张图，这是彩蛋隐藏项专用的。 */
data class MenuEntry(
    val title: String,
    val summary: String? = null,
    val iconRes: Int? = null,
    val onClick: () -> Unit,
)

/**
 * 页面内的子功能菜单条。
 *
 * 一行文字说明 + 右侧汉堡按钮，用于把「本页有哪些功能」收在一起，
 * 页面主体只保留当前选中的那一个。
 */
@Composable
fun MenuBar(
    title: String,
    items: List<MenuEntry>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.pad + 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            androidx.compose.material3.Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        HamburgerMenu(items = items)
    }
}


/**
 * 菜单定位。
 *
 * 挂在触发按钮的右下方，右对齐——跟 Material 3 的溢出菜单一致。
 */
private class MenuPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.right - popupContentSize.width
        val y = anchorBounds.bottom
        return IntOffset(x, y)
    }
}
