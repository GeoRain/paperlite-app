package com.codefox.paperlite.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 界面风格。
 *
 * 两套都是「同一份功能、两套皮肤」，切风格只换视觉语言，不动任何业务逻辑：
 *
 * - [Miuix]：默认。澎湃 OS / MIUI 的观感——纯白或纯黑打底、
 *   靠色块而非阴影分层、按钮是胶囊、卡片圆角大。
 * - [Material3]：Google Material 3 的观感——色调面（tonal surface）分层、
 *   阴影与描边参与结构、按钮带图标位、圆角按 M3 形状规范收敛。
 */
enum class DesignStyle(val value: String, val title: String, val desc: String) {
    Miuix("miuix", "澎湃风格", "MIUI 观感：大圆角卡片、胶囊按钮、纯色底"),
    Material3("m3", "Material 3", "Google 规范：色调面分层、描边与阴影参与结构"),
    ;

    companion object {
        fun of(raw: String?): DesignStyle =
            entries.firstOrNull { it.value == raw } ?: Miuix
    }
}

/** 当前界面风格，由主题注入。 */
val LocalDesignStyle = staticCompositionLocalOf { DesignStyle.Miuix }

/**
 * 形状规范。
 *
 * M3 的形状有明确的层级（extraSmall 4dp → large 28dp），
 * 澎湃风格则一律偏大圆角。把这套数值抽出来，组件只按语义取形状，
 * 换风格时不用改组件。
 */
@Immutable
data class ShapeSpec(
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    val cornerButton: Dp,
    val cornerFull: Dp,
) {
    companion object {
        /** 澎湃风格：一切都偏圆。 */
        val Miuix = ShapeSpec(
            cornerSmall = 14.dp,
            cornerMedium = 20.dp,
            cornerLarge = 28.dp,
            cornerButton = 50.dp,
            cornerFull = 50.dp,
        )

        /** Material 3：按 M3 形状阶梯收敛。 */
        val Material3 = ShapeSpec(
            cornerSmall = 8.dp,
            cornerMedium = 12.dp,
            cornerLarge = 16.dp,
            cornerButton = 20.dp,
            cornerFull = 50.dp,
        )
    }
}

/**
 * 组件表面色。
 *
 * 两套风格的分层方式不同：澎湃靠「纯底 + 浅灰块」，M3 靠「色调面 + 描边」。
 * 抽出来之后，容器类组件只管按语义取色。
 */
@Immutable
data class SurfaceSpec(
    /** 卡片/面板底色。 */
    val container: Color,
    /** 卡片/面板上的正文色。 */
    val onContainer: Color,
    /** 次级容器（输入框底、分段选择器底）。 */
    val containerVariant: Color,
    /** 次级容器上的正文色。 */
    val onContainerVariant: Color,
    /** 更浅一层的容器（列表项 hover 底等）。 */
    val containerHigh: Color,
    /** 分隔线 / 描边色。 */
    val outline: Color,
    /** 是否用描边代替阴影来表达层级。 */
    val useOutline: Boolean,
    /** 是否给容器加阴影（M3 的 elevation）。 */
    val elevation: Dp,
)

/** 当前形状规范。 */
val LocalShapes = staticCompositionLocalOf { ShapeSpec.Miuix }

/** 当前表面色规范。 */
val LocalSurfaces = staticCompositionLocalOf<SurfaceSpec?> { null }
