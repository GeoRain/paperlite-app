package com.codefox.paperlite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codefox.paperlite.ui.theme.LocalDarkTheme
import com.codefox.paperlite.ui.theme.Palette
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 启动过场：白（夜间黑）底上浮出 `PaperLite` 大字，随后整块渐隐。
 *
 * ## 时长为什么缩短了
 *
 * 旧版 700 + 420 + 560 ≈ 1.7 秒，用户在慢设备上感受到的是「点了图标没反应」，
 * 反馈里「打开很卡」有一半来自这里。现在压到 240 + 140 + 220 ≈ 0.6 秒，
 * 只保留「浮出 + 渐隐」两个动作，节奏仍然完整。
 *
 * ## 为什么不用 graphicsLayer
 *
 * 整屏 `graphicsLayer` 会强制为窗口内容建一个离屏图层，之后每一帧都要多一次
 * 合成。这里改成直接插值背景色 alpha 与文字 alpha，效果一样，开销接近零。
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val dark = LocalDarkTheme.current
    val bg = if (dark) Palette.Black else Palette.White
    val fg = if (dark) Palette.TextPrimaryDark else Palette.TextPrimary
    val sub = if (dark) Palette.TextSecondaryDark else Palette.TextSecondary

    // 0 → 1：文字浮出；0 → 1：整层渐隐
    val appear = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 240, easing = LinearOutSlowInEasing))
        delay(140)
        fade.animateTo(1f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
        onFinished()
    }

    val visible = 1f - fade.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg.copy(alpha = visible)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(visible),
        ) {
            Text(
                text = "PaperLite",
                modifier = Modifier.alpha(appear.value),
                color = fg,
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "面向个人设备的系统能力工具",
                modifier = Modifier.alpha(appear.value * 0.9f),
                color = sub,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .height(3.dp)
                    .width(120.dp)
                    .alpha(appear.value)
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Palette.Blue, Color.Transparent),
                        ),
                    ),
            )
        }
    }
}
