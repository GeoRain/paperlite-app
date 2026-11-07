package com.codefox.paperlite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme as M3Dark
import androidx.compose.material3.lightColorScheme as M3Light
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Material 3 主题。
 *
 * 只在切到 Material 3 风格时启用，色板仍然沿用小米蓝——
 * 换风格换的是**组件语言**（色调面分层、描边、形状阶梯），
 * 不是换品牌色。所以两套风格放在一起看不会打架。
 *
 * M3 的 `Typography` 也换成 Google Sans Flex，跟 Miuix 风格保持同一套字。
 */
@Composable
fun Material3Theme(
    dark: Boolean,
    brand: BrandColors,
    content: @Composable () -> Unit,
) {
    val scheme = remember(dark, brand) {
        if (dark) {
            M3Dark(
                primary = brand.primary,
                onPrimary = brand.onPrimary,
                primaryContainer = brand.primaryContainer,
                onPrimaryContainer = brand.onPrimaryContainer,
                secondary = brand.secondary,
                onSecondary = brand.onSecondary,
                secondaryContainer = brand.secondaryContainer,
                onSecondaryContainer = brand.onSecondaryContainer,
                tertiary = brand.tertiary,
                onTertiary = brand.onTertiary,
                background = Color(0xFF0E1116),
                onBackground = Palette.TextPrimaryDark,
                surface = Color(0xFF0E1116),
                onSurface = Palette.TextPrimaryDark,
                surfaceVariant = Color(0xFF1F242C),
                onSurfaceVariant = Palette.TextSecondaryDark,
                surfaceContainerLowest = Color(0xFF090C10),
                surfaceContainerLow = Color(0xFF151920),
                surfaceContainer = Color(0xFF1B1F26),
                surfaceContainerHigh = Color(0xFF232833),
                surfaceContainerHighest = Color(0xFF2C323E),
                outline = Color(0xFF39404C),
                outlineVariant = Color(0xFF262C35),
                error = Color(0xFFFF6B60),
                onError = Color(0xFF2E0906),
                errorContainer = Color(0xFF5C1A16),
                onErrorContainer = Color(0xFFFFDAD6),
            )
        } else {
            M3Light(
                primary = brand.primary,
                onPrimary = brand.onPrimary,
                primaryContainer = brand.primaryContainer,
                onPrimaryContainer = brand.onPrimaryContainer,
                secondary = brand.secondary,
                onSecondary = brand.onSecondary,
                secondaryContainer = brand.secondaryContainer,
                onSecondaryContainer = brand.onSecondaryContainer,
                tertiary = brand.tertiary,
                onTertiary = brand.onTertiary,
                background = Color(0xFFFBFCFF),
                onBackground = Palette.TextPrimary,
                surface = Color(0xFFFBFCFF),
                onSurface = Palette.TextPrimary,
                surfaceVariant = Color(0xFFE7EDF7),
                onSurfaceVariant = Palette.TextSecondary,
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF5F8FD),
                surfaceContainer = Color(0xFFF0F4FB),
                surfaceContainerHigh = Color(0xFFE8EEF9),
                surfaceContainerHighest = Color(0xFFE1E9F6),
                outline = Color(0xFFC7D2E3),
                outlineVariant = Color(0xFFDDE5F1),
                error = Color(0xFFBA1A1A),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFFFDAD6),
                onErrorContainer = Color(0xFF410002),
            )
        }
    }

    val base = androidx.compose.material3.Typography()
    val gs = googleSansFamily
    val typography = remember(base) {
        base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = gs),
            displayMedium = base.displayMedium.copy(fontFamily = gs),
            displaySmall = base.displaySmall.copy(fontFamily = gs),
            headlineLarge = base.headlineLarge.copy(fontFamily = gs),
            headlineMedium = base.headlineMedium.copy(fontFamily = gs),
            headlineSmall = base.headlineSmall.copy(fontFamily = gs),
            titleLarge = base.titleLarge.copy(fontFamily = gs),
            titleMedium = base.titleMedium.copy(fontFamily = gs),
            titleSmall = base.titleSmall.copy(fontFamily = gs),
            bodyLarge = base.bodyLarge.copy(fontFamily = gs),
            bodyMedium = base.bodyMedium.copy(fontFamily = gs),
            bodySmall = base.bodySmall.copy(fontFamily = gs),
            labelLarge = base.labelLarge.copy(fontFamily = gs),
            labelMedium = base.labelMedium.copy(fontFamily = gs),
            labelSmall = base.labelSmall.copy(fontFamily = gs),
        )
    }

    MaterialTheme(colorScheme = scheme, typography = typography) {
        content()
    }
}

/**
 * 供 M3 主题引用的字族。
 *
 * 与 Miuix 主题共用同一个 ttf，这里单独包一层是为了不跨文件暴露私有常量。
 */
internal val googleSansFamily = androidx.compose.ui.text.font.FontFamily(
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
