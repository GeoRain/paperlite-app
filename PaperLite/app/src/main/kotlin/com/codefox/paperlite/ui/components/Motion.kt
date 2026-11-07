package com.codefox.paperlite.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.codefox.paperlite.ui.theme.Motion

/**
 * 折叠区块。
 *
 * 展开时高度从 0 撑开并淡入，收起时高度收回并淡出——
 * 这是「每一处展开收起都有动画」的实现处。
 *
 * @param visible 是否展开
 * @param expandFromTop 从顶部展开（true）还是从底部展开（false）
 */
@Composable
fun ExpandableSection(
    visible: Boolean,
    modifier: Modifier = Modifier,
    expandFromTop: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = expandVertically(
            animationSpec = tween(Motion.EXPAND, easing = Motion.emphasized),
            expandFrom = if (expandFromTop) Alignment.Top else Alignment.Bottom,
        ) + fadeIn(tween(Motion.MEDIUM, easing = Motion.enterEasing)),
        exit = shrinkVertically(
            animationSpec = tween(Motion.EXPAND, easing = Motion.exitEasing),
            shrinkTowards = if (expandFromTop) Alignment.Top else Alignment.Bottom,
        ) + fadeOut(tween(Motion.QUICK, easing = Motion.exitEasing)),
    ) {
        Column(content = content)
    }
}

/**
 * 内容切换动画。
 *
 * 页面内切分组时用它：新内容淡入 + 轻微上移，旧内容淡出。
 * 位移只有 14dp，不会让用户等动画。
 */
@Composable
fun <T> AnimatedSectionSwitch(
    target: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    androidx.compose.animation.AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(Motion.LONG, easing = Motion.emphasized)) +
                slideInVertically(
                    tween(Motion.LONG, easing = Motion.emphasized),
                ) { Motion.switchOffset })
                .togetherWith(
                    fadeOut(tween(Motion.QUICK, easing = Motion.exitEasing)),
                )
        },
        label = "section-switch",
    ) { value ->
        content(value)
    }
}

/**
 * 高度自适应。
 *
 * 列表项内容变化（例如应用状态从「可更新」变成「已安装」）时，
 * 容器高度平滑过渡而不是「啪」地跳一下。
 */
@Composable
fun AnimatedHeight(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.animateContentSize(
            animationSpec = tween(Motion.EXPAND, easing = Motion.emphasized),
        ),
    ) {
        content()
    }
}
