package com.codefox.paperlite.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 配色来源。
 *
 * - [Xiaomi]：固定小米蓝，PaperLite 原本的品牌色。
 * - [Wallpaper]：从**应用内背景图**里取主色再生成整套色调面（莫奈取色）。
 *   取的是我们自己这张壁纸，不是系统壁纸，所以在任何设备上都可控。
 * - [SystemMonet]：Android 12+ 系统动态色，跟随用户设的系统壁纸。
 */
enum class ColorSource(val value: String, val title: String, val desc: String) {
    Xiaomi("xiaomi", "小米蓝", "固定品牌色，最稳"),
    Wallpaper("wallpaper", "壁纸取色", "从当前背景图提取主色"),
    SystemMonet("monet", "系统动态色", "Android 12+ 跟随系统壁纸"),
    ;

    companion object {
        fun of(raw: String?): ColorSource =
            entries.firstOrNull { it.value == raw } ?: Wallpaper

        /** 系统动态色只在 12+ 可用，低版本选项里不出现。 */
        fun available(): List<ColorSource> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) entries.toList()
            else listOf(Xiaomi, Wallpaper)
    }
}

/**
 * 一套可替换的品牌色。
 *
 * [PaperLiteTheme] 原本把 `Palette.Blue*` 写死在四个分支里；
 * 这里把它抽成参数，莫奈取色时只需整组替换，风格分支（明暗 / M3 / 澎湃）
 * 仍然只决定表面分层方式，互不干扰。
 */
@Immutable
data class BrandColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryVariant: Color,
    val onPrimaryVariant: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    /** 状态色里的 info 跟随品牌色，避免蓝底上配一个不相干的蓝。 */
    val info: Color,
) {
    companion object {
        /** 小米蓝：与 [Palette] 原始常量完全一致，保证默认行为不变。 */
        fun xiaomi(dark: Boolean, m3: Boolean): BrandColors = if (dark) {
            BrandColors(
                primary = Palette.BlueDark,
                onPrimary = Color(0xFF001A3D),
                primaryVariant = Palette.Blue,
                onPrimaryVariant = Color(0xFF001A3D),
                primaryContainer = Palette.BlueDarkSoft,
                onPrimaryContainer = Palette.BlueDark,
                secondary = Palette.BlueDark,
                onSecondary = Color(0xFF001A3D),
                secondaryContainer = Palette.BlueDarkSoft,
                onSecondaryContainer = Palette.BlueDark,
                tertiary = Color(0xFF6FD3C4),
                onTertiary = Color(0xFF00382F),
                info = Palette.BlueDark,
            )
        } else {
            BrandColors(
                primary = Palette.Blue,
                onPrimary = Color.White,
                primaryVariant = Palette.BlueDeep,
                onPrimaryVariant = Color.White,
                primaryContainer = if (m3) Color(0xFFD9E6FF) else Palette.BlueSoft,
                onPrimaryContainer = if (m3) Color(0xFF00305F) else Palette.BlueDeep,
                secondary = if (m3) Color(0xFF4A6C9B) else Palette.Blue,
                onSecondary = Color.White,
                secondaryContainer = if (m3) Color(0xFFDCE7F5) else Palette.BlueSoft,
                onSecondaryContainer = if (m3) Color(0xFF1B3352) else Palette.BlueDeep,
                tertiary = Color(0xFF166B5C),
                onTertiary = Color.White,
                info = Palette.Blue,
            )
        }

        /**
         * 从任意一张位图里提取主色，再展开成完整色调面。
         *
         * 这是**真的取色**，不是拿壁纸盖一层蓝色滤镜：
         */
        fun fromBitmap(bitmap: Bitmap, dark: Boolean, m3: Boolean): BrandColors {
            val seed = extractSeed(bitmap) ?: return xiaomi(dark, m3)
            return fromSeed(seed, dark)
        }

        /**
         * 主色提取：缩放 → HSL 分桶 → 挑「既常见又不过灰」的色相桶。
         *
         * 直接取全图平均色会被大面积的白云 / 蓝天冲成灰蓝，
         * 所以按色相分 36 桶统计，用 `数量 × (0.4 + 饱和度)` 打分，
         * 跳过过暗、过亮、低饱和的像素（它们基本是背景与高光）。
         */
        fun extractSeed(bitmap: Bitmap): Color? {
            val small = runCatching {
                Bitmap.createScaledBitmap(bitmap, 64, 64, false)
            }.getOrNull() ?: return null

            val buckets = FloatArray(36) // 每桶累计权重
            val satSum = FloatArray(36)
            val lightSum = FloatArray(36)
            val count = IntArray(36)
            val hsl = FloatArray(3)

            for (y in 0 until small.height) {
                for (x in 0 until small.width) {
                    val pixel = small.getPixel(x, y)
                    val a = android.graphics.Color.alpha(pixel)
                    if (a < 128) continue
                    android.graphics.Color.colorToHSV(pixel, hsl)
                    val s = hsl[1]
                    val v = hsl[2]
                    if (s < 0.18f || v < 0.20f || v > 0.97f) continue
                    val bin = (hsl[0] / 10f).toInt().coerceIn(0, 35)
                    val weight = 0.4f + s
                    buckets[bin] += weight
                    satSum[bin] += s
                    lightSum[bin] += v
                    count[bin]++
                }
            }

            var best = -1
            var bestScore = 0f
            for (i in 0..35) {
                if (buckets[i] > bestScore) {
                    bestScore = buckets[i]
                    best = i
                }
            }
            if (best < 0 || count[best] == 0) return null
            val hue = best * 10f + 5f
            val sat = (satSum[best] / count[best]).coerceIn(0.42f, 0.85f)
            return Color.hsv(hue, sat, 0.90f)
        }

        /**
         * 以 seed 的色相生成一整套色调面。
         *
         * 不用完整的 CAM16/HCT 实现（那要引 material-color-utilities），
         * 而是保持色相、按明度铺开：深色模式把主色提到 80% 明度保证在黑底上
         * 对比度够，浅色模式压到 48% 保证在白底上够。容器色统一降低饱和度，
         * 这样文字始终压得住底。
         */
        fun fromSeed(seed: Color, dark: Boolean): BrandColors {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
            val h = hsv[0]
            val s = hsv[1].coerceIn(0.35f, 0.85f)

            fun c(hue: Float, sat: Float, value: Float) = Color.hsv(hue, sat.coerceIn(0f, 1f), value)
            fun nearBlack(v: Float = 0.12f) = c(h, 0.35f, v)

            return if (dark) {
                BrandColors(
                    primary = c(h, s * 0.65f, 0.86f),
                    onPrimary = nearBlack(0.14f),
                    primaryVariant = c(h, s, 0.72f),
                    onPrimaryVariant = nearBlack(0.12f),
                    primaryContainer = c(h, s * 0.55f, 0.26f),
                    onPrimaryContainer = c(h, s * 0.35f, 0.90f),
                    secondary = c(h + 12f, s * 0.45f, 0.80f),
                    onSecondary = nearBlack(0.14f),
                    secondaryContainer = c(h + 12f, s * 0.40f, 0.28f),
                    onSecondaryContainer = c(h + 12f, s * 0.30f, 0.90f),
                    tertiary = c(h + 60f, s * 0.50f, 0.78f),
                    onTertiary = nearBlack(0.14f),
                    info = c(h, s * 0.60f, 0.82f),
                )
            } else {
                BrandColors(
                    primary = c(h, s, 0.52f),
                    onPrimary = Color.White,
                    primaryVariant = c(h, s, 0.40f),
                    onPrimaryVariant = Color.White,
                    primaryContainer = c(h, s * 0.45f, 0.91f),
                    onPrimaryContainer = c(h, s * 0.70f, 0.22f),
                    secondary = c(h + 12f, s * 0.55f, 0.48f),
                    onSecondary = Color.White,
                    secondaryContainer = c(h + 12f, s * 0.35f, 0.92f),
                    onSecondaryContainer = c(h + 12f, s * 0.60f, 0.24f),
                    tertiary = c(h + 60f, s * 0.60f, 0.42f),
                    onTertiary = Color.White,
                    info = c(h, s, 0.52f),
                )
            }
        }

        /**
         * Android 12+ 系统动态色：直接读 material3 生成的 ColorScheme 的品牌字段。
         * 低版本返回 null，调用方回退到小米蓝。
         */
        fun fromSystem(context: Context, dark: Boolean, m3: Boolean): BrandColors? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val scheme = if (dark) {
                androidx.compose.material3.dynamicDarkColorScheme(context)
            } else {
                androidx.compose.material3.dynamicLightColorScheme(context)
            }
            return BrandColors(
                primary = scheme.primary,
                onPrimary = scheme.onPrimary,
                primaryVariant = scheme.primary,
                onPrimaryVariant = scheme.onPrimary,
                primaryContainer = scheme.primaryContainer,
                onPrimaryContainer = scheme.onPrimaryContainer,
                secondary = scheme.secondary,
                onSecondary = scheme.onSecondary,
                secondaryContainer = scheme.secondaryContainer,
                onSecondaryContainer = scheme.onSecondaryContainer,
                tertiary = scheme.tertiary,
                onTertiary = scheme.onTertiary,
                info = scheme.primary,
            )
        }
    }
}

/** HSV → Compose Color（保留 alpha=255）。 */
private fun Color.Companion.hsv(hue: Float, saturation: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue % 360f, saturation, value)))
