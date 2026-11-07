package com.codefox.paperlite.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
 * 小米蓝调色板。
 *
 * 主色取小米澎湃 OS 的系统蓝 `#3482FF`：它比 Android 默认蓝更亮一档，
 * 在纯白底上对比度够、在深色底上也不发闷。
 *
 * 分层方式保留澎湃 OS 的做法：日间以纯白为底，层级靠极浅的灰块区分，
 * 而不是阴影；深色沿用同一色相，把底翻成真正的黑。
 */
object Palette {
    // ---------- 强调色（小米蓝） ----------
    val Blue = Color(0xFF3482FF)
    val BlueDeep = Color(0xFF1F63D8)
    val BlueSoft = Color(0xFFE8F1FF)
    val BlueDark = Color(0xFF5C9AFF)
    val BlueDarkSoft = Color(0xFF16243D)

    // ---------- 日间 ----------
    val White = Color(0xFFFFFFFF)
    val Fill1 = Color(0xFFF6F7F9)
    val Fill2 = Color(0xFFEDEFF2)
    val Fill3 = Color(0xFFE3E6EB)
    val TextPrimary = Color(0xFF14161A)
    val TextSecondary = Color(0xFF6B7280)
    val TextTertiary = Color(0xFF9AA1AC)
    val Divider = Color(0xFFEBEDF0)

    // ---------- 深色 ----------
    val Black = Color(0xFF000000)
    val Fill1Dark = Color(0xFF131519)
    val Fill2Dark = Color(0xFF1B1E24)
    val Fill3Dark = Color(0xFF242830)
    val TextPrimaryDark = Color(0xFFF1F3F5)
    val TextSecondaryDark = Color(0xFF9DA3AD)
    val TextTertiaryDark = Color(0xFF6C727C)
    val DividerDark = Color(0xFF282C33)
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
    warn = Color(0xFFFF7D00),
    danger = Color(0xFFE02E24),
    info = Palette.Blue,
    neutral = Palette.TextTertiary,
    onOk = Color(0xFFFFFFFF),
    onWarn = Color(0xFFFFFFFF),
    onDanger = Color(0xFFFFFFFF),
    onInfo = Color(0xFFFFFFFF),
    onNeutral = Color(0xFFFFFFFF),
)

private val DarkStatus = StatusColors(
    ok = Color(0xFF3DDC7A),
    warn = Color(0xFFFFA751),
    danger = Color(0xFFFF6B60),
    info = Palette.BlueDark,
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
 * 主色统一为小米蓝，不使用 Material 3 的默认紫色。
 *
 * @param style 界面风格。两种风格共用同一套小米蓝色板，
 *   差别在形状阶梯、表面分层方式与组件实现（见 [DesignStyle]）。
 */
@Composable
fun PaperLiteTheme(
    dark: Boolean,
    style: DesignStyle = DesignStyle.Miuix,
    brand: BrandColors? = null,
    content: @Composable () -> Unit,
) {
    val b = brand ?: BrandColors.xiaomi(dark, style == DesignStyle.Material3)
    // 颜色表按 dark / style / 品牌色缓存，避免每次重组都重建 54 个字段的 ColorScheme
    val scheme = remember(dark, style, brand) {
        // Material 3 风格：表面换成 M3 的色调面阶梯。
        // Miuix 的组件读的是同一份 colorScheme，所以只要换掉表面字段，
        // 卡片、输入框、分段器这些组件就自动呈现 M3 的分层方式。
        when {
            style == DesignStyle.Material3 && dark -> darkColorScheme(
                primary = b.primary,
                onPrimary = b.onPrimary,
                primaryVariant = b.primaryVariant,
                onPrimaryVariant = b.onPrimaryVariant,
                primaryContainer = b.primaryContainer,
                onPrimaryContainer = b.onPrimaryContainer,
                secondary = b.secondary,
                onSecondary = b.onSecondary,
                secondaryContainer = b.secondaryContainer,
                onSecondaryContainer = b.onSecondaryContainer,
                background = Color(0xFF0E1116),
                onBackground = Palette.TextPrimaryDark,
                onBackgroundVariant = Palette.TextSecondaryDark,
                surface = Color(0xFF0E1116),
                onSurface = Palette.TextPrimaryDark,
                surfaceVariant = Color(0xFF1F242C),
                onSurfaceSecondary = Palette.TextSecondaryDark,
                onSurfaceVariantSummary = Palette.TextSecondaryDark,
                onSurfaceVariantActions = Palette.TextSecondaryDark,
                surfaceContainer = Color(0xFF1B1F26),
                onSurfaceContainer = Palette.TextPrimaryDark,
                onSurfaceContainerVariant = Palette.TextSecondaryDark,
                surfaceContainerHigh = Color(0xFF232833),
                onSurfaceContainerHigh = Palette.TextPrimaryDark,
                surfaceContainerHighest = Color(0xFF2C323E),
                onSurfaceContainerHighest = Palette.TextPrimaryDark,
                outline = Color(0xFF39404C),
                dividerLine = Color(0xFF262C35),
            )

            style == DesignStyle.Material3 -> lightColorScheme(
                primary = b.primary,
                onPrimary = b.onPrimary,
                primaryVariant = b.primaryVariant,
                onPrimaryVariant = b.onPrimaryVariant,
                primaryContainer = b.primaryContainer,
                onPrimaryContainer = b.onPrimaryContainer,
                secondary = b.secondary,
                onSecondary = b.onSecondary,
                secondaryContainer = b.secondaryContainer,
                onSecondaryContainer = b.onSecondaryContainer,
                background = Color(0xFFFBFCFF),
                onBackground = Palette.TextPrimary,
                onBackgroundVariant = Palette.TextSecondary,
                surface = Color(0xFFFBFCFF),
                onSurface = Palette.TextPrimary,
                surfaceVariant = Color(0xFFE7EDF7),
                onSurfaceSecondary = Palette.TextSecondary,
                onSurfaceVariantSummary = Palette.TextSecondary,
                onSurfaceVariantActions = Palette.TextSecondary,
                surfaceContainer = Color(0xFFF0F4FB),
                onSurfaceContainer = Palette.TextPrimary,
                onSurfaceContainerVariant = Palette.TextSecondary,
                surfaceContainerHigh = Color(0xFFE8EEF9),
                onSurfaceContainerHigh = Palette.TextPrimary,
                surfaceContainerHighest = Color(0xFFE1E9F6),
                onSurfaceContainerHighest = Palette.TextPrimary,
                outline = Color(0xFFC7D2E3),
                dividerLine = Color(0xFFDDE5F1),
            )

            dark -> darkColorScheme(
                primary = b.primary,
                onPrimary = b.onPrimary,
                primaryVariant = b.primaryVariant,
                onPrimaryVariant = b.onPrimaryVariant,
                primaryContainer = b.primaryContainer,
                onPrimaryContainer = b.onPrimaryContainer,
                secondary = b.secondary,
                onSecondary = b.onSecondary,
                secondaryContainer = b.secondaryContainer,
                onSecondaryContainer = b.onSecondaryContainer,
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

            else -> lightColorScheme(
                primary = b.primary,
                onPrimary = b.onPrimary,
                primaryVariant = b.primaryVariant,
                onPrimaryVariant = b.onPrimaryVariant,
                primaryContainer = b.primaryContainer,
                onPrimaryContainer = b.onPrimaryContainer,
                secondary = b.secondary,
                onSecondary = b.onSecondary,
                secondaryContainer = b.secondaryContainer,
                onSecondaryContainer = b.onSecondaryContainer,
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
    val status = (if (dark) DarkStatus else LightStatus).copy(info = b.info)

    // 字体：Google Sans Flex（抄自 Lawnchair，OFL-1.1 开源协议）
    // 14 个样式全部换成同一套字族，界面观感与 Lawnchair 一致。
    val base = MiuixTheme.textStyles
    val gs = googleSans
    val styles = remember(dark, style) {
        if (style == DesignStyle.Material3) {
            // Material 3 风格：字阶直接换成 M3 的排版规范，
            // 标题更大、正文行距更松、辅助信息更小更淡——这是 M3 的辨识度所在。
            val t = androidx.compose.material3.Typography()
            top.yukonga.miuix.kmp.theme.TextStyles(
                t.bodyLarge.copy(fontFamily = gs, color = Color.Unspecified),
                t.bodyMedium.copy(fontFamily = gs, color = Color.Unspecified),
                t.bodyLarge.copy(fontFamily = gs, color = Color.Unspecified),
                t.bodyMedium.copy(fontFamily = gs, color = Color.Unspecified),
                t.labelLarge.copy(fontFamily = gs, color = Color.Unspecified),
                t.labelMedium.copy(fontFamily = gs, color = Color.Unspecified),
                t.labelSmall.copy(fontFamily = gs, color = Color.Unspecified),
                t.displaySmall.copy(fontFamily = gs, color = Color.Unspecified),
                t.headlineSmall.copy(fontFamily = gs, color = Color.Unspecified),
                t.titleSmall.copy(fontFamily = gs, color = Color.Unspecified),
                t.headlineLarge.copy(fontFamily = gs, color = Color.Unspecified),
                t.headlineMedium.copy(fontFamily = gs, color = Color.Unspecified),
                t.headlineSmall.copy(fontFamily = gs, color = Color.Unspecified),
                t.titleMedium.copy(fontFamily = gs, color = Color.Unspecified),
            )
        } else {
            top.yukonga.miuix.kmp.theme.TextStyles(
                base.main.copy(fontFamily = gs),
                base.paragraph.copy(fontFamily = gs),
                base.body1.copy(fontFamily = gs),
                base.body2.copy(fontFamily = gs),
                base.button.copy(fontFamily = gs),
                base.footnote1.copy(fontFamily = gs),
                base.footnote2.copy(fontFamily = gs),
                base.headline1.copy(fontFamily = gs),
                base.headline2.copy(fontFamily = gs),
                base.subtitle.copy(fontFamily = gs),
                base.title1.copy(fontFamily = gs),
                base.title2.copy(fontFamily = gs),
                base.title3.copy(fontFamily = gs),
                base.title4.copy(fontFamily = gs),
            )
        }
    }

    // 表面规范：两套风格的分层方式不同，抽象成一份「组件表面色」。
    val surfaces = remember(dark, style) {
        when {
            style == DesignStyle.Material3 && dark -> SurfaceSpec(
                container = Color(0xFF1B1F26),
                onContainer = Palette.TextPrimaryDark,
                containerVariant = Color(0xFF14171C),
                onContainerVariant = Palette.TextSecondaryDark,
                containerHigh = Color(0xFF232833),
                outline = Color(0xFF39404C),
                useOutline = true,
                elevation = 0.dp,
            )

            style == DesignStyle.Material3 -> SurfaceSpec(
                container = Color(0xFFFFFFFF),
                onContainer = Palette.TextPrimary,
                containerVariant = Color(0xFFF0F4FB),
                onContainerVariant = Palette.TextSecondary,
                containerHigh = Color(0xFFE8EEF9),
                outline = Color(0xFFC7D2E3),
                useOutline = true,
                elevation = 0.dp,
            )

            dark -> SurfaceSpec(
                container = Palette.Fill1Dark,
                onContainer = Palette.TextPrimaryDark,
                containerVariant = Palette.Fill2Dark,
                onContainerVariant = Palette.TextSecondaryDark,
                containerHigh = Palette.Fill3Dark,
                outline = Palette.DividerDark,
                useOutline = false,
                elevation = 0.dp,
            )

            else -> SurfaceSpec(
                container = Palette.Fill1,
                onContainer = Palette.TextPrimary,
                containerVariant = Palette.Fill2,
                onContainerVariant = Palette.TextSecondary,
                containerHigh = Palette.Fill3,
                outline = Palette.Divider,
                useOutline = false,
                elevation = 0.dp,
            )
        }
    }

    MiuixTheme(colors = scheme, textStyles = styles) {
        // M3 主题也要一直提供：Material 3 风格的组件（OutlinedCard、
        // AlertDialog、SegmentedButton…）读的是 MaterialTheme，
        // 少一层包裹就会退化成默认的紫色主题。
        Material3Theme(dark = dark, brand = b) {
            CompositionLocalProvider(
                LocalStatusColors provides status,
                LocalDarkTheme provides dark,
                LocalSectionColor provides (if (dark) Palette.White else Palette.Fill1),
                LocalDesignStyle provides style,
                LocalShapes provides when (style) {
                    DesignStyle.Miuix -> ShapeSpec.Miuix
                    DesignStyle.Material3 -> ShapeSpec.Material3
                },
                LocalSurfaces provides surfaces,
            ) {
                content()
            }
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

/** 当前是否 Material 3 风格。 */
@Composable
fun isMaterial3(): Boolean = LocalDesignStyle.current == DesignStyle.Material3

/**
 * Google Sans Flex 字族。
 *
 * 抄自 Lawnchair（`res/font/googlesansflex_variable.ttf`），
 * Google Sans Flex 以 OFL-1.1 协议发布，可自由使用与分发。
 */
private val googleSans = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(
        com.codefox.paperlite.R.font.googlesansflex,
        androidx.compose.ui.text.font.FontWeight.Normal,
    ),
    androidx.compose.ui.text.font.Font(
        com.codefox.paperlite.R.font.googlesansflex,
        androidx.compose.ui.text.font.FontWeight.Medium,
    ),
    androidx.compose.ui.text.font.Font(
        com.codefox.paperlite.R.font.googlesansflex,
        androidx.compose.ui.text.font.FontWeight.Bold,
    ),
)
