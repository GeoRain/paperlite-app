package com.codefox.paperlite.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.ui.theme.LocalShapes
import com.codefox.paperlite.ui.theme.Motion
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页内标签栏。
 *
 * ## 什么时候用 Tab，什么时候用汉堡菜单
 *
 * 这两者不是替代关系，分工是：
 *
 * - **Tab 栏**：页面的**主要**子功能，数量 2~4 个，需要一眼看到、
 *   频繁来回切。放在页面顶部，是内容区的一部分。
 * - **汉堡菜单**：**低频**或**配置类**功能（源设置、日志、诊断、关于），
 *   平时不需要看到，但偶尔要能进去。收在右上角溢出菜单里。
 *
 * 所以应用商店的「商店 / 已下载」用 Tab（天天用），
 * 「源设置」进汉堡菜单（配一次就不动了）。
 *
 * ## 视觉
 *
 * 滑动胶囊：选中的那一格背景色做动画过渡，滑块跟着走。
 */
@Composable
fun <T> PageTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shapes = LocalShapes.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.pad, vertical = 4.dp)
            .clip(RoundedCornerShape(shapes.cornerMedium))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            val container by animateColorAsState(
                targetValue = if (active) MiuixTheme.colorScheme.primary else Color.Transparent,
                animationSpec = tween(Motion.MEDIUM, easing = Motion.enterEasing),
                label = "tab-container",
            )
            val content by animateColorAsState(
                targetValue = if (active) {
                    MiuixTheme.colorScheme.onPrimary
                } else {
                    MiuixTheme.colorScheme.onSurfaceContainerVariant
                },
                animationSpec = tween(Motion.MEDIUM, easing = Motion.enterEasing),
                label = "tab-content",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(shapes.cornerSmall))
                    .background(container)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MiuixTheme.textStyles.footnote1,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
