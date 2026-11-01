package com.codefox.paperlite.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 日夜模式，持久化在 [com.codefox.paperlite.data.Settings]。 */
enum class ThemeMode {
    System,
    Light,
    Dark;

    companion object {
        fun of(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: System
    }
}

/**
 * 小米澎湃 OS 3 调色板。
 *
 * 日间以**纯白**为底，层级靠极浅的灰色块区分，而不是阴影；
 * 强调色取澎湃 OS 的橙色，错误色取系统红。
 * 深色沿用同一色相，把底翻成真正的黑。
 */
object Palette {
    // ---------- 强调色（澎湃橙） ----------
    val Orange = Color(0xFFFF6A00)
    val OrangeDeep = Color(0xFFE05C00)
    val OrangeSoft = Color(0xFFFFF0E5)
    val OrangeDark = Color(0xFFFF8A3D)
    val OrangeDarkSoft = Color(0xFF38200C)

    // ---------- 日间 ----------
    val White = Color(0xFFFFFFFF)
    val Fill1 = Color(0xFFF7F7F8)
    val Fill2 = Color(0xFFEFEFF1)
    val Fill3 = Color(0xFFE6E6E9)
    val TextPrimary = Color(0xFF141416)
    val TextSecondary = Color(0xFF6B6B70)
    val TextTertiary = Color(0xFF9A9AA0)
    val Divider = Color(0xFFEBEBEE)

    // ---------- 深色 ----------
    val Black = Color(0xFF000000)
    val Fill1Dark = Color(0xFF141416)
    val Fill2Dark = Color(0xFF1C1C1F)
    val Fill3Dark = Color(0xFF26262A)
    val TextPrimaryDark = Color(0xFFF2F2F4)
    val TextSecondaryDark = Color(0xFF9E9EA4)
    val TextTertiaryDark = Color(0xFF6E6E74)
    val DividerDark = Color(0xFF2A2A2E)
}

/**
 * 能力状态专用色（通道徽标、健康度、日志级别）。
 *
 * 单独放在 CompositionLocal 里而不是塞进 ColorScheme，是因为 Miuix 的
 * ColorScheme 字段集合是固定的，扩展字段放这里不会触发主题重建。
 */
data class StatusColors(
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val info: Color,
    val neutral: Color,
    val onOk: Color,
    val onWarn: Color,
    val onDanger: Color,
    val onInfo: Color,
    val onNeutral: Color,
)

private val LightStatus = StatusColors(
    ok = Color(0xFF12A150),
    warn = Palette.Orange,
    danger = Color(0xFFE02E24),
    info = Color(0xFF1A7AFF),
    neutral = Palette.TextTertiary,
    onOk = Color(0xFFFFFFFF),
    onWarn = Color(0xFFFFFFFF),
    onDanger = Color(0xFFFFFFFF),
    onInfo = Color(0xFFFFFFFF),
    onNeutral = Color(0xFFFFFFFF),
)

private val DarkStatus = StatusColors(
    ok = Color(0xFF3DDC7A),
    warn = Palette.OrangeDark,
    danger = Color(0xFFFF6B60),
    info = Color(0xFF6AA8FF),
    neutral = Color(0xFF8E8E94),
    onOk = Color(0xFF04220F),
    onWarn = Color(0xFF2B1503),
    onDanger = Color(0xFF2E0906),
    onInfo = Color(0xFF06152C),
    onNeutral = Color(0xFF15151A),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** 当前是否深色主题，供页面取纯白/纯黑底。 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** 分区容器底色，由主题注入，避免 Composable 里做颜色比较。 */
val LocalSectionColor = staticCompositionLocalOf { Palette.Fill1 }

/**
 * 应用主题。
 *
 * 明色以纯白 `#FFFFFF` 打底，深色以纯黑 `#000000` 打底；
 * 主色统一为澎湃橙，不使用 Material 3 的紫色。
 */
@Composable
fun PaperLiteTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    // 颜色表按 dark 缓存，避免每次重组都重建 54 个字段的 ColorScheme
    val scheme = remember(dark) {
        if (dark) {
            darkColorScheme(
                primary = Palette.OrangeDark,
                onPrimary = Color(0xFF1F0F02),
                primaryVariant = Palette.Orange,
                onPrimaryVariant = Color(0xFF1F0F02),
                primaryContainer = Palette.OrangeDarkSoft,
                onPrimaryContainer = Palette.OrangeDark,
                secondary = Palette.OrangeDark,
                onSecondary = Color(0xFF1F0F02),
                secondaryContainer = Palette.OrangeDarkSoft,
                onSecondaryContainer = Palette.OrangeDark,
                background = Palette.Black,
                onBackground = Palette.TextPrimaryDark,
                onBackgroundVariant = Palette.TextSecondaryDark,
                surface = Palette.Black,
                onSurface = Palette.TextPrimaryDark,
                surfaceVariant = Palette.Fill1Dark,
                onSurfaceSecondary = Palette.TextSecondaryDark,
                onSurfaceVariantSummary = Palette.TextSecondaryDark,
                onSurfaceVariantActions = Palette.TextSecondaryDark,
                surfaceContainer = Palette.Fill1Dark,
                onSurfaceContainer = Palette.TextPrimaryDark,
                onSurfaceContainerVariant = Palette.TextSecondaryDark,
                surfaceContainerHigh = Palette.Fill2Dark,
                onSurfaceContainerHigh = Palette.TextPrimaryDark,
                surfaceContainerHighest = Palette.Fill3Dark,
                onSurfaceContainerHighest = Palette.TextPrimaryDark,
                outline = Palette.DividerDark,
                dividerLine = Palette.DividerDark,
            )
        } else {
            lightColorScheme(
                primary = Palette.Orange,
                onPrimary = Color(0xFFFFFFFF),
                primaryVariant = Palette.OrangeDeep,
                onPrimaryVariant = Color(0xFFFFFFFF),
                primaryContainer = Palette.OrangeSoft,
                onPrimaryContainer = Palette.OrangeDeep,
                secondary = Palette.Orange,
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Palette.OrangeSoft,
                onSecondaryContainer = Palette.OrangeDeep,
                background = Palette.White,
                onBackground = Palette.TextPrimary,
                onBackgroundVariant = Palette.TextSecondary,
                surface = Palette.White,
                onSurface = Palette.TextPrimary,
                surfaceVariant = Palette.Fill1,
                onSurfaceSecondary = Palette.TextSecondary,
                onSurfaceVariantSummary = Palette.TextSecondary,
                onSurfaceVariantActions = Palette.TextSecondary,
                surfaceContainer = Palette.White,
                onSurfaceContainer = Palette.TextPrimary,
                onSurfaceContainerVariant = Palette.TextSecondary,
                surfaceContainerHigh = Palette.Fill1,
                onSurfaceContainerHigh = Palette.TextPrimary,
                surfaceContainerHighest = Palette.Fill2,
                onSurfaceContainerHighest = Palette.TextPrimary,
                outline = Palette.Divider,
                dividerLine = Palette.Divider,
            )
        }
    }
    val status = if (dark) DarkStatus else LightStatus

    MiuixTheme(colors = scheme) {
        CompositionLocalProvider(
            LocalStatusColors provides status,
            LocalDarkTheme provides dark,
            LocalSectionColor provides (if (dark) Palette.White else Palette.Fill1),
        ) {
            content()
        }
    }
}

/** 系统是否深色。 */
fun isSystemDark(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

@Composable
fun resolveDark(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

fun ThemeMode.displayName(): String = when (this) {
    ThemeMode.System -> "跟随系统"
    ThemeMode.Light -> "浅色"
    ThemeMode.Dark -> "深色"
}

/** 页面底色：日间纯白、夜间纯黑，与澎湃 OS 设置页一致。 */
@Composable
fun pageBackground(): Color =
    if (LocalDarkTheme.current) Palette.Black else Palette.White

/** 分区容器底色，直接取注入值，避免拿 Color 做相等比较。 */
@Composable
fun controlContainer(): Color = LocalSectionColor.current
