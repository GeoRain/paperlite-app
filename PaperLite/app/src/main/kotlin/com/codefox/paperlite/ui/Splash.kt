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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codefox.paperlite.ui.theme.LocalDarkTheme
import com.codefox.paperlite.ui.theme.Palette
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/**
 * 启动过场：纯白（夜间纯黑）背景上先浮出 `PaperLite` 大字，
 * 停留片刻后整块渐隐、同时主界面淡入由远及近，两段动画交叉完成。
 *
 * 动画全部用 [Animatable] 驱动，只读一个变量，不会引起多余的层重组。
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val dark = LocalDarkTheme.current
    val bg = if (dark) Palette.Black else Palette.White
    val fg = if (dark) Palette.TextPrimaryDark else Palette.TextPrimary

    // 0 → 1：文字浮出
    val appear = remember { Animatable(0f) }
    // 0 → 1：整层渐隐
    val fade = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        appear.animateTo(1f, tween(durationMillis = 700, easing = LinearOutSlowInEasing))
        delay(420)
        fade.animateTo(1f, tween(durationMillis = 560, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - fade.value }
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "PaperLite",
                modifier = Modifier
                    .graphicsLayer {
                        alpha = appear.value
                        val s = 0.90f + 0.10f * appear.value
                        scaleX = s
                        scaleY = s
                        translationY = (1f - appear.value) * 26f
                        // 轻微失焦 → 清晰，模拟澎湃 OS 的「浮出」质感
                    },
                color = fg,
                fontSize = 46.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "面向个人设备的系统能力工具",
                modifier = Modifier.alpha(appear.value * 0.9f),
                color = if (dark) Palette.TextSecondaryDark else Palette.TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(26.dp))
            Box(
                modifier = Modifier
                    .height(3.dp)
                    .graphicsLayer { alpha = appear.value }
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color.Transparent, Palette.Orange, Color.Transparent),
                        ),
                    )
                    .padding(horizontal = 96.dp),
            )
        }
    }
}

/**
 * 主界面入场：从略小、略透明推近到原始大小。
 *
 * @param visible 外部完成启动过场后置 true，触发入场动画
 */
@Composable
fun EntranceContainer(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    LaunchedEffect(visible) {
        if (visible) {
            progress.animateTo(1f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
        }
    }
    var first by remember { mutableStateOf(true) }
    if (visible) first = false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (progress.value < 1f) {
                    val s = 0.965f + 0.035f * progress.value
                    scaleX = s
                    scaleY = s
                    alpha = progress.value
                }
            },
    ) {
        content()
    }
}
