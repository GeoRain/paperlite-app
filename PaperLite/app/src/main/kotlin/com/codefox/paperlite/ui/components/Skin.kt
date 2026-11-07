package com.codefox.paperlite.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.LocalDesignStyle
import com.codefox.paperlite.ui.theme.LocalShapes
import com.codefox.paperlite.ui.theme.LocalStatusColors
import com.codefox.paperlite.ui.theme.Motion
import com.codefox.paperlite.ui.theme.readableOn
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 风格感知组件。
 *
 * ## 为什么要单独一层
 *
 * 页面代码里到处直接写 `MiuixTheme.textStyles.footnote1`、`Card(cornerRadius = 28.dp)`，
 * 这些写法把「用哪套设计语言」写死了。要支持切换风格，只有两条路：
 *
 * 1. 改遍所有页面 —— 5000 行代码里塞 `if (isMaterial3())`，不可维护；
 * 2. **收敛到组件层** —— 页面只调用语义化的 `SkinCard` / `SkinButton`，
 *    由组件层根据当前风格决定用 Miuix 还是 Material 3 的实现。
 *
 * 走第二条。所以页面代码几乎不用动，风格切换是纯视觉行为。
 *
 * ## 两套实现的差异
 *
 * | 组件 | 澎湃风格 | Material 3 |
 * |---|---|---|
 * | 卡片 | 无描边大圆角、靠底色分层 | 描边 + 色调面、M3 形状阶梯 |
 * | 按钮 | 胶囊、实心主色 | M3 按钮（圆角 20dp，带按下涟漪） |
 * | 开关 | Miuix 开关 | M3 开关（拇指与轨道比例不同） |
 * | 输入框 | Miuix 圆角输入框 | M3 OutlinedTextField |
 * | 分段器 | 胶囊滑动 | M3 SegmentedButton |
 * | 菜单 | 自绘圆角面板 | M3 DropdownMenu |
 */

/** 当前形状规范。 */
@Composable
private fun shapes() = LocalShapes.current

/** 是否 Material 3 风格。 */
@Composable
private fun m3(): Boolean = LocalDesignStyle.current == DesignStyle.Material3

/**
 * 风格卡片。
 *
 * 澎湃风格走 Miuix Card（大圆角、无描边）；
 * M3 风格走 OutlinedCard（描边 + 色调面），这是 M3 的典型观感。
 */
@Composable
fun SkinCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val s = shapes()
    if (m3()) {
        androidx.compose.material3.OutlinedCard(
            onClick = { onClick?.invoke() },
            enabled = onClick != null,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.pad, vertical = Metrics.gap / 2),
            shape = RoundedCornerShape(s.cornerLarge),
            colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            SkinCardBody(title = title, subtitle = subtitle, content = content)
        }
    } else {
        top.yukonga.miuix.kmp.basic.Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.pad, vertical = Metrics.gap / 2)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
            cornerRadius = s.cornerLarge,
            insideMargin = PaddingValues(vertical = 10.dp),
            colors = top.yukonga.miuix.kmp.basic.CardColors(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            ),
        ) {
            SkinCardBody(title = title, subtitle = subtitle, content = content)
        }
    }
}

/** 卡片的标题区，两套风格共用。 */
@Composable
private fun SkinCardBody(
    title: String?,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    Column {
        if (title != null) {
            Column(modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 3.dp)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
        }
        content()
    }
}

/**
 * 风格按钮。
 *
 * 澎湃风格是胶囊实心；M3 风格用 M3 Button（圆角 20dp + 涟漪反馈）。
 */
@Composable
fun SkinButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val s = shapes()
    val bg = tint ?: MiuixTheme.colorScheme.primary
    val content = readableOn(bg)
    if (m3()) {
        androidx.compose.material3.Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(s.cornerButton),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = bg,
                contentColor = content,
            ),
        ) {
            Text(
                text = text,
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        top.yukonga.miuix.kmp.basic.Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = s.cornerButton,
            colors = com.codefox.paperlite.ui.theme.paperButtonColors(tint),
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.footnote1,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 风格开关。
 *
 * 两套都保留「拇指白、轨道主色」的语义，差别在比例与动画曲线。
 */
@Composable
fun SkinSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (m3()) {
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        top.yukonga.miuix.kmp.basic.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = com.codefox.paperlite.ui.theme.paperSwitchColors(),
        )
    }
}

/**
 * 风格输入框。
 *
 * 两套都用 Miuix 的 `TextField`：它的参数形态是 `TextFieldState`（Compose
 * Foundation 的可变文本状态），而 Material 3 在这个 Compose 版本里的
 * `OutlinedTextField` 参数列表已经换过一轮，跟着它的签名走只会写出
 * 明天就编译不过的代码。
 *
 * 观感差异不靠换控件实现，而靠**配色与形状**：
 * M3 风格下主题色板是 M3 的色调面阶梯，输入框的底色、描边、圆角
 * 随之改变，从「MIUI 填充式」变成「M3 描边式」。
 */
@Composable
fun SkinTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    top.yukonga.miuix.kmp.basic.TextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        label = label.orEmpty(),
        enabled = enabled,
        lineLimits = if (singleLine) {
            androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine
        } else {
            androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(
                minHeightInLines = 4,
                maxHeightInLines = 12,
            )
        },
        keyboardOptions = keyboardOptions,
    )
}

/**
 * 风格分段选择器。
 *
 * 澎湃风格是滑动胶囊；M3 风格是 M3 官方的 SegmentedButton（带指示器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SkinSegmentedTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = shapes()
    if (m3()) {
        SingleChoiceSegmentedButtonRow(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.pad, vertical = Metrics.gap / 2),
        ) {
            options.forEach { option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = options.indexOf(option),
                        count = options.size,
                    ),
                ) {
                    Text(
                        text = label(option),
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.pad, vertical = Metrics.gap / 2)
                .clip(RoundedCornerShape(s.cornerMedium))
                .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEach { option ->
                val active = option == selected
                val container by animateColorAsState(
                    targetValue = if (active) MiuixTheme.colorScheme.primary else Color.Transparent,
                    animationSpec = tween(Motion.MEDIUM, easing = Motion.enterEasing),
                    label = "seg-container",
                )
                val content by animateColorAsState(
                    targetValue = if (active) {
                        MiuixTheme.colorScheme.onPrimary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceContainerVariant
                    },
                    animationSpec = tween(Motion.MEDIUM, easing = Motion.enterEasing),
                    label = "seg-content",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(s.cornerSmall))
                        .background(container)
                        .clickable { onSelect(option) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MiuixTheme.textStyles.footnote1,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 风格菜单项的排版（标题 + 说明），两套菜单共用。 */
@Composable
fun SkinMenuItemText(title: String, summary: String?) {
    Column {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 状态色（供组件内部做危险操作等判断）。 */
@Composable
internal fun statusDanger(): Color = LocalStatusColors.current.danger
