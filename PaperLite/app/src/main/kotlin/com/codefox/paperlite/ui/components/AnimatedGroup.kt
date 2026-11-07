package com.codefox.paperlite.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codefox.paperlite.ui.theme.Motion

/**
 * LazyColumn 分组项的过渡动画。
 *
 * ## 为什么用 animateItem 而不是 AnimatedVisibility
 *
 * 页面里的分组是 `if (section == "xxx") item(key = "...") { ... }` 这种写法——
 * 分组不匹配时 item **根本不在列表里**。要让它「淡出」，
 * 就得让 item 一直在列表里、用 AnimatedVisibility 控制显隐，
 * 代价是所有分组的内容都会被组合一遍（授权名单那一块有几十个应用，
 * 每次重组都要重画）。
 *
 * `animateItem` 是 Compose 1.7+ 提供的 LazyItemScope 修饰符，
 * 专门解决「item 出现/消失」的过渡：出现时淡入 + 从下方位移进来，
 * 消失时淡出，而且**只组合当前可见的 item**。
 *
 * 三种动画各司其职：
 * - `fadeInSpec`：分组出现时的淡入
 * - `placementSpec`：列表项位置变化时的位移（插入/删除导致下面的内容上移）
 * - `fadeOutSpec`：分组消失时的淡出
 */
@Composable
fun LazyItemScope.AnimatedGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.animateItem(
            fadeInSpec = tween(Motion.MEDIUM, easing = Motion.enterEasing),
            placementSpec = tween(Motion.LONG, easing = Motion.emphasized),
            fadeOutSpec = tween(Motion.QUICK, easing = Motion.exitEasing),
        ),
    ) {
        content()
    }
}
