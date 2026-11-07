package com.codefox.paperlite.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween

/**
 * 全局动效规格。
 *
 * ## 为什么集中定义
 *
 * 之前每个页面各写各的 `tween(220)`，结果同一种过渡在首页是 220ms、
 * 在设置页是 180ms，滑动方向也不统一——用户能感觉到「有的地方动得猛、
 * 有的地方没动」。这里把时长、曲线、位移全部收口：
 *
 * - **进入**：用 [enter]（减速为主，快速响应）
 * - **退出**：用 [exit]（加速为主，收得干脆，不拖泥带水）
 * - **切换**：用 [switchSpec]，淡入 + 轻微纵向位移
 *
 * 位移刻意做得很小（8~16dp）。大位移在低端 Pad 上会拖出「等它动完」的滞后感，
 * 而用户要的是「点下去立刻有反馈」，所以动效只负责消除突兀，不负责表演。
 */
object Motion {

    /** 标准减速曲线，用于进入。 */
    val enterEasing: Easing = FastOutSlowInEasing

    /** 标准加速曲线，用于退出。 */
    val exitEasing: Easing = LinearOutSlowInEasing

    /** M3 Expressive 的强调曲线：前段快、尾巴稳。 */
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 极短，用于颜色、开关这类不需要位移的插值。 */
    const val QUICK = 140

    /** 常规，菜单、按钮反馈。 */
    const val MEDIUM = 220

    /** 稍长，页面切换、区块切换。 */
    const val LONG = 300

    /** 折叠展开。 */
    const val EXPAND = 260

    /** 遮罩淡入淡出。 */
    const val SCRIM = 180

    /** 进入动画规格。 */
    fun enter(duration: Int = MEDIUM): FiniteAnimationSpec<Float> =
        tween(duration, easing = enterEasing)

    /** 退出动画规格。 */
    fun exit(duration: Int = MEDIUM): FiniteAnimationSpec<Float> =
        tween(duration, easing = exitEasing)

    /** 进入动画规格（位移型，IntOffset）。 */
    fun enterOffset(duration: Int = MEDIUM): FiniteAnimationSpec<IntOffset> =
        tween(duration, easing = enterEasing)

    /** 退出动画规格（位移型，IntOffset）。 */
    fun exitOffset(duration: Int = MEDIUM): FiniteAnimationSpec<IntOffset> =
        tween(duration, easing = exitEasing)

    /** 内容切换规格。 */
    fun switchSpec(duration: Int = LONG): FiniteAnimationSpec<Float> =
        tween(duration, easing = emphasized)

    /** 区块切换时的纵向位移距离。 */
    val switchOffset: Int = 14
}
