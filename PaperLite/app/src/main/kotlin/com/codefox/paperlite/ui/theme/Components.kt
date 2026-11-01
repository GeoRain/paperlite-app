package com.codefox.paperlite.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.SwitchColors
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 按钮与开关的配色。
 *
 * ## 为什么不用 Miuix 默认值
 *
 * Miuix 的 `ButtonDefaults.buttonColors()` 与 `SwitchDefaults.switchColors()`
 * 走的是它自己那套 Material 色彩推导，落到本项目上有两个问题：
 *
 * 1. **按钮文字色不跟随背景色**。默认的 `contentColor` 是「主色上的文字色」，
 *    一旦把按钮背景改成危险红（用于「卸载」这类不可逆操作），
 *    文字还是原来的深色，红底深字几乎看不清。
 * 2. **开关的轨道色偏灰**。默认 unchecked 轨道用的是中性灰，
 *    在纯白底上显得脏，而且和橙色强调色不是一套语言。
 *
 * 所以这里统一自己算：
 * - 按钮：背景取给定色，文字一律取「在给定色上可读」的颜色（按亮度自动选黑或白）
 * - 开关：打开时用主色，关掉时用低饱和灰，滑块始终为白
 */

/** 在给定背景色上可读的前景色：亮底配深字，暗底配白字。 */
fun readableOn(background: Color): Color {
    // 感知亮度：0.299R + 0.587G + 0.114B
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.55f) Color(0xFF1A1A1C) else Color(0xFFFFFFFF)
}

/**
 * 按钮配色。
 *
 * @param tint 背景色；传 null 时用主题主色
 */
@Composable
fun paperButtonColors(tint: Color? = null): ButtonColors {
    val primary = MiuixTheme.colorScheme.primary
    val bg = tint ?: primary
    val content = readableOn(bg)
    return remember(bg, content) {
        ButtonColors(
            color = bg,
            disabledColor = bg.copy(alpha = 0.38f),
            contentColor = content,
            disabledContentColor = content.copy(alpha = 0.7f),
        )
    }
}

/**
 * 开关配色。
 *
 * @param accent 打开时的强调色，默认取主题主色
 */
@Composable
fun paperSwitchColors(accent: Color? = null): SwitchColors {
    val primary = accent ?: MiuixTheme.colorScheme.primary
    val offTrack = MiuixTheme.colorScheme.surfaceContainerHighest
    val offThumb = MiuixTheme.colorScheme.onSurfaceContainerVariant
    return remember(primary, offTrack, offThumb) {
        SwitchColors(
            checkedThumbColor = Color(0xFFFFFFFF),
            uncheckedThumbColor = offThumb,
            disabledCheckedThumbColor = Color(0xFFFFFFFF).copy(alpha = 0.6f),
            disabledUncheckedThumbColor = offThumb.copy(alpha = 0.5f),
            checkedTrackColor = primary,
            uncheckedTrackColor = offTrack,
            disabledCheckedTrackColor = primary.copy(alpha = 0.4f),
            disabledUncheckedTrackColor = offTrack.copy(alpha = 0.5f),
        )
    }
}
