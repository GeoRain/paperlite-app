package com.codefox.paperlite.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.ui.theme.LocalStatusColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浮层组件。
 *
 * ## 为什么要自己写
 *
 * Miuix 0.9.4 自带的 `OverlayDialog` / `OverlayBottomSheet` 用的是 value class
 * 包装过的颜色与尺寸参数，从 Kotlin 侧调用要按它的内部约定拼参数，
 * 而且退出动画的节奏不好控。这里改用标准 Compose 动画原语自己实现，
 * 好处是三件事都能做到：
 *
 * 1. **入场**：淡入 + 轻微缩放 + 向上位移（缓出，跟手）
 * 2. **退场**：淡出 + 轻微缩小 + 向下位移（加速，收得干脆）
 * 3. **遮罩**：独立的淡入淡出，不会跟着内容一起闪
 *
 * 统一由 [AnimatedOverlay] 承载，任何页面只要把内容塞进来就自带过渡动画。
 */
@Composable
fun AnimatedOverlay(
    visible: Boolean,
    onDismiss: (() -> Unit)? = null,
    scrimColor: Color = Color.Black.copy(alpha = 0.42f),
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    // 退场需要时间，不能在 visible 变 false 的瞬间就把内容从树上摘掉，
    // 否则退场动画根本来不及播。这里用一层「还想显示吗」的状态做延迟卸载。
    var mounted by remember { mutableStateOf(visible) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            leaving = false
        } else if (mounted) {
            leaving = true
        }
    }

    // 退场动画播完再真正卸载
    LaunchedEffect(leaving) {
        if (leaving) {
            kotlinx.coroutines.delay(EXIT_DURATION_MS)
            mounted = false
            leaving = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩单独一层：只有 visible 时才可点，退场时不拦截触摸
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180, easing = LinearOutSlowInEasing)),
            exit = fadeOut(tween(160, easing = FastOutSlowInEasing)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onDismiss?.invoke() },
                    ),
            )
        }

        AnimatedVisibility(
            visible = mounted,
            enter = fadeIn(tween(200, easing = LinearOutSlowInEasing)) +
                scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.92f) +
                slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 8 },
            exit = fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                scaleOut(tween(180, easing = FastOutSlowInEasing), targetScale = 0.95f) +
                slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 8 },
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = contentAlignment,
            ) {
                content()
            }
        }
    }
}

/**
 * 确认弹窗。
 *
 * 用于「卸载」「清数据」这类不可逆操作的二次确认。
 * 危险操作用红色强调按钮，一眼能看出后果。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalStatusColors.current
    AnimatedOverlay(
        visible = true,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = message,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    text = dismissText,
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                ActionButton(
                    text = confirmText,
                    modifier = Modifier.weight(1f),
                    onClick = onConfirm,
                    tint = if (danger) c.danger else MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 底部动作面板。
 *
 * 用于「一个操作对应多个选项」的场景（例如应用详情里选操作）。
 * 从底部滑入，手感跟澎湃 OS 的动作面板一致。
 */
@Composable
fun ActionSheet(
    title: String?,
    actions: List<ActionSheetEntry>,
    onDismiss: () -> Unit,
) {
    AnimatedOverlay(
        visible = true,
        onDismiss = onDismiss,
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = Metrics.inset, vertical = 10.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
            }
            actions.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            onDismiss()
                            entry.onClick()
                        }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.text,
                            style = MiuixTheme.textStyles.body1,
                            color = if (entry.danger) {
                                LocalStatusColors.current.danger
                            } else {
                                MiuixTheme.colorScheme.onSurfaceContainer
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (entry.subtitle != null) {
                            Text(
                                text = entry.subtitle,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "取消",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 动作面板里的一项。 */
data class ActionSheetEntry(
    val text: String,
    val subtitle: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 轻量消息条。
 *
 * 取代页面上直接插一段 Text 的做法：从底部滑入 + 淡入，
 * 一段时间后自动淡出。所有「点了按钮之后的反馈」都走它。
 */
@Composable
fun ToastMessage(
    text: String?,
    modifier: Modifier = Modifier,
    durationMs: Long = 2_600L,
) {
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        enter = fadeIn(tween(200, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(260, easing = LinearOutSlowInEasing)) { it / 3 },
        exit = fadeOut(tween(220, easing = FastOutSlowInEasing)) +
            slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it / 3 },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            Text(
                text = text.orEmpty(),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // 到时自动消失
    LaunchedEffect(text) {
        if (!text.isNullOrBlank()) {
            kotlinx.coroutines.delay(durationMs)
        }
    }
}

/** 退场动画时长，与 [AnimatedOverlay] 的 exit 保持一致。 */
private const val EXIT_DURATION_MS = 190L
