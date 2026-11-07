package com.codefox.paperlite.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.ui.theme.LocalStatusColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 版式常量。
 *
 * 「界面放小点，不要臃肿」的具体落实：统一收窄外边距、压低行高、
 * 卡片圆角加大但内边距减小，让内容更密、留白更克制。
 */
object Metrics {
    /** 内容左右外边距。 */
    val pad: Dp = 14.dp
    /** 卡片之间的纵向间距。 */
    val gap: Dp = 7.dp
    /** 区块内部左右内边距。 */
    val inset: Dp = 12.dp
}

/** 状态胶囊。 */
@Immutable
data class PillSpec(val text: String, val color: Color, val onColor: Color)

@Composable
fun Pill(
    text: String,
    color: Color,
    onColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = color,
            maxLines = 1,
        )
    }
}

/** 好 / 坏 / 中性三态胶囊。 */
@Composable
fun StatusPill(ok: Boolean, okText: String = "可用", badText: String = "不可用") {
    val c = LocalStatusColors.current
    Pill(
        text = if (ok) okText else badText,
        color = if (ok) c.ok else c.neutral,
        onColor = if (ok) c.onOk else c.onNeutral,
    )
}

/**
 * 页面标题区。
 *
 * 这是「消除顶部白条」的正面替代：系统不再画 ActionBar，
 * 页面自己用这个组件画标题，并带上状态栏高度的手动内边距。
 * 相比旧版压低了字号与上下间距，避免首屏被标题吃掉。
 *
 * @param leading 标题左侧的插槽（返回按钮、图标等）。
 *   加这个插槽是因为有些页面是「从上一级下钻进来的」，
 *   标题左边得有个返回，否则用户不知道自己从哪来、也回不去。
 */
@Composable
fun PageHeader(
    title: String,
    kicker: String? = null,
    subtitle: String? = null,
    topInset: Int = 0,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = (topInset + 8).dp,
                start = Metrics.pad + 4.dp,
                end = Metrics.pad + 4.dp,
                bottom = 6.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
        if (kicker != null || subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOfNotNull(kicker, subtitle).joinToString(" · "),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 圆形图标按钮（返回箭头这类）。
 *
 * 40dp 触控目标 + 半透明圆形底，与汉堡菜单的按钮同一套规格，
 * 两个并排时视觉重量才对得上。
 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 小节标题。 */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = modifier.padding(
            start = Metrics.pad + 4.dp,
            end = Metrics.pad,
            top = 12.dp,
            bottom = 4.dp,
        ),
    )
}

/** 键值行，用于信息展示。 */
@Composable
fun InfoRow(key: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.inset, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = key,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.width(88.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        // 值列必须限行并省略，否则长内容（包名、路径、堆栈）会横向溢出卡片
        Text(
            text = value,
            style = if (mono) MiuixTheme.textStyles.footnote2 else MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 左侧色条的项目符号行。 */
@Composable
fun BulletRow(text: String, accent: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.inset, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(width = 3.dp, height = 10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent ?: MiuixTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            modifier = Modifier.weight(1f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 空态占位。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

/**
 * 分区容器。
 *
 * 澎湃 OS 风格：日间纯白底上不再用「一张白卡 + 阴影」，
 * 而是把容器底色压低一档灰（`surfaceContainerHigh`），内容直接坐在上面，
 * 结构更轻、观感更小。
 */
@Composable
fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    SkinCard(title = title, subtitle = subtitle, content = content)
}

/** 横向等间距排列的操作按钮组。 */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.inset, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 带文字的按钮。
 *
 * Miuix 0.9.4 的 `Button` 只暴露 `content: @Composable RowScope.() -> Unit` 内容槽，
 * 没有 `text: String` 参数。这里补一层文字封装，页面里就能继续按
 * `ActionButton("刷新") { refresh() }` 的语义调用，不必每处手写 Text。
 */
@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 50.dp,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    SkinButton(
        text = text,
        modifier = modifier,
        enabled = enabled,
        tint = tint,
        onClick = onClick,
    )
}

/**
 * 单行输入框。
 *
 * Miuix 0.9.4 的 `TextField` 收的是 `TextFieldState`（Compose Foundation 的可变文本状态），
 * 不是 `value: String` + `onValueChange`。这里用 `remember` 桥接一次。
 *
 * 两条纪律（否则会互相回写、把主线程锁死）：
 * 1. 只在**外部值**真的变了时才回灌 `state`，不能每次重组都写
 * 2. `snapshotFlow` 里只在两者确实不同时才回调，避免 A→B→A 的自激循环
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val state = remember { TextFieldState(value) }
    // 只跟随外部值的变化，且值相同就不动——写 state 会触发下一个 effect，条件必须收敛
    LaunchedEffect(value) {
        if (state.text.toString() != value) {
            state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    val limiter = if (singleLine) {
        TextFieldLineLimits.SingleLine
    } else {
        TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 12)
    }
    SkinTextField(
        state = state,
        modifier = modifier,
        label = label,
        enabled = enabled,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect {
            if (it != value) onValueChange(it)
        }
    }
}

/** 左标题右开关的一行，用于各项布尔设置。 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.inset, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1,
                color = if (enabled) {
                    MiuixTheme.colorScheme.onSurfaceContainer
                } else {
                    MiuixTheme.colorScheme.onSurfaceContainerVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        SkinSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * 横向分段选择器（澎湃 OS 风格胶囊）。
 *
 * 用于替代「一堆各自独立的按钮」，把同类选项压成一行，显著降低视觉重量。
 */
@Composable
fun <T> SegmentedTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SkinSegmentedTabs(
        options = options,
        selected = selected,
        label = label,
        onSelect = onSelect,
        modifier = modifier,
    )
}

/** 一行紧凑的「标签 + 值」，用于首页概览。 */
@Composable
fun MetricTile(
    value: String,
    label: String,
    accent: Color? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            color = accent ?: MiuixTheme.colorScheme.onSurfaceContainer,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            maxLines = 1,
        )
    }
}
