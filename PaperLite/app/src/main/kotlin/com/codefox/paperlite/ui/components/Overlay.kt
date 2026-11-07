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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.LocalDesignStyle
import com.codefox.paperlite.ui.theme.LocalShapes
import com.codefox.paperlite.ui.theme.LocalStatusColors
import com.codefox.paperlite.ui.theme.Motion
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浮层组件。
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
            enter = fadeIn(tween(Motion.SCRIM, easing = LinearOutSlowInEasing)),
            exit = fadeOut(tween(Motion.SCRIM, easing = FastOutSlowInEasing)),
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
            enter = fadeIn(tween(Motion.MEDIUM, easing = LinearOutSlowInEasing)) +
                scaleIn(tween(Motion.LONG, easing = FastOutSlowInEasing), initialScale = 0.92f) +
                slideInVertically(tween(Motion.LONG, easing = FastOutSlowInEasing)) { it / 8 },
            exit = fadeOut(tween(Motion.QUICK, easing = FastOutSlowInEasing)) +
                scaleOut(tween(Motion.MEDIUM, easing = FastOutSlowInEasing), targetScale = 0.95f) +
                slideOutVertically(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { it / 8 },
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
 *
 * Material 3 风格下走真正的 [androidx.compose.material3.AlertDialog]，
 * 澎湃风格保持自绘面板。
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
    val shapes = LocalShapes.current
    val m3 = LocalDesignStyle.current == DesignStyle.Material3

    if (m3) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(shapes.cornerLarge),
            title = {
                androidx.compose.material3.Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                androidx.compose.material3.Text(
                    text = message,
                    style = MiuixTheme.textStyles.body2,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onConfirm) {
                    androidx.compose.material3.Text(
                        text = confirmText,
                        color = if (danger) c.danger
                        else androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Text(
                        text = dismissText,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        return
    }

    AnimatedOverlay(
        visible = true,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .clip(RoundedCornerShape(shapes.cornerLarge))
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
 * 知情确认弹窗：**必须勾选复选框**才能继续，点遮罩与返回键都不关闭。
 *
 * 用在「超级用户」这类高危页面进门处：每次进入都弹一次，不做持久化
 * ——风险提示一旦被记住就再也没人看，等于没有。
 *
 * 与 [ConfirmDialog] 一样走两套皮肤：Material 3 用官方 AlertDialog，
 * 澎湃风格用自绘面板。复选框是自绘的（画一条勾线），不依赖任何主题的
 * Checkbox，避免两套皮肤下尺寸与配色对不齐。
 */
@Composable
fun ConsentDialog(
    title: String,
    message: String,
    consent: String,
    confirmText: String = "我已了解",
    onConfirm: () -> Unit,
) {
    val shapes = LocalShapes.current
    val m3 = LocalDesignStyle.current == DesignStyle.Material3
    val c = LocalStatusColors.current
    var checked by remember { mutableStateOf(false) }

    if (m3) {
        val cs = androidx.compose.material3.MaterialTheme.colorScheme
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            shape = RoundedCornerShape(shapes.cornerLarge),
            title = {
                androidx.compose.material3.Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    color = cs.onSurface,
                )
            },
            text = {
                Column {
                    androidx.compose.material3.Text(
                        text = message,
                        style = MiuixTheme.textStyles.body2,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    ConsentCheck(
                        checked = checked,
                        onCheckedChange = { checked = it },
                        text = consent,
                        textColor = cs.onSurface,
                        accent = cs.primary,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = onConfirm,
                    enabled = checked,
                ) {
                    androidx.compose.material3.Text(
                        text = confirmText,
                        color = if (checked) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                }
            },
        )
        return
    }

    AnimatedOverlay(visible = true, onDismiss = null) {
        Column(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .clip(RoundedCornerShape(shapes.cornerLarge))
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
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            ConsentCheck(
                checked = checked,
                onCheckedChange = { checked = it },
                text = consent,
                textColor = MiuixTheme.colorScheme.onSurfaceContainer,
                accent = c.danger,
            )
            Spacer(Modifier.height(16.dp))
            ActionButton(
                text = if (checked) confirmText else "请先勾选上方确认项",
                modifier = Modifier.fillMaxWidth(),
                enabled = checked,
                tint = MiuixTheme.colorScheme.primary,
                onClick = onConfirm,
            )
        }
    }
}

/**
 * 自绘复选框 + 说明文字。整行可点，不用精确点到那个小方块。
 */
@Composable
private fun ConsentCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    textColor: Color,
    accent: Color,
) {
    val boxColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) accent else Color.Transparent,
        animationSpec = tween(Motion.QUICK),
        label = "consent-box",
    )
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) accent else textColor.copy(alpha = 0.45f),
        animationSpec = tween(Motion.QUICK),
        label = "consent-border",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(boxColor)
                .border(1.5.dp, borderColor, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Canvas(modifier = Modifier.size(13.dp)) {
                    val w = size.width
                    val h = size.height
                    val p = Path().apply {
                        moveTo(w * 0.12f, h * 0.52f)
                        lineTo(w * 0.40f, h * 0.80f)
                        lineTo(w * 0.90f, h * 0.18f)
                    }
                    drawPath(
                        path = p,
                        color = Color.White,
                        style = Stroke(
                            width = w * 0.16f,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 底部动作面板。
 *
 * 用于「一个操作对应多个选项」的场景（例如应用详情里选操作）。
 * 从底部滑入，手感跟澎湃 OS 的动作面板一致。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    title: String?,
    actions: List<ActionSheetEntry>,
    onDismiss: () -> Unit,
) {
    val shapes = LocalShapes.current
    val m3 = LocalDesignStyle.current == DesignStyle.Material3

    // Material 3 风格：用官方 ModalBottomSheet，自带拖拽条与滑入动画
    if (m3) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 8.dp)) {
                if (title != null) {
                    androidx.compose.material3.Text(
                        text = title,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                actions.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(shapes.cornerSmall))
                            .clickable {
                                onDismiss()
                                entry.onClick()
                            }
                            .padding(horizontal = 10.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            androidx.compose.material3.Text(
                                text = entry.text,
                                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                                color = if (entry.danger) {
                                    LocalStatusColors.current.danger
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.subtitle != null) {
                                androidx.compose.material3.Text(
                                    text = entry.subtitle,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(shapes.cornerSmall))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    androidx.compose.material3.Text(
                        text = "取消",
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        return
    }

    AnimatedOverlay(
        visible = true,
        onDismiss = onDismiss,
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(shapes.cornerLarge))
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
                        .clip(RoundedCornerShape(shapes.cornerSmall))
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
                    .clip(RoundedCornerShape(shapes.cornerSmall))
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
    val shapes = LocalShapes.current
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        enter = fadeIn(tween(Motion.MEDIUM, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(Motion.LONG, easing = LinearOutSlowInEasing)) { it / 3 },
        exit = fadeOut(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) +
            slideOutVertically(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { it / 3 },
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(shapes.cornerMedium))
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
private const val EXIT_DURATION_MS = 210L
