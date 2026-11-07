package com.codefox.paperlite.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.LocalDesignStyle
import com.codefox.paperlite.ui.theme.LocalStatusColors

/**
 * 状态大卡片。
 *
 * 形态照搬 SukiSU 首页那张信息卡：大标题（36sp）→ 版本副标题 → 状态芯片
 * → 键值信息列表。文案沿用 SukiSU 的说法：工作中 / 未安装 / 未运行。
 */
@Composable
fun StatusHero(
    title: String,
    subtitle: String,
    hint: String? = null,
    chips: List<Chip> = emptyList(),
    rows: List<Pair<String, String>> = emptyList(),
    showFullStatus: Boolean = false,
    onToggleFullStatus: ((Boolean) -> Unit)? = null,
    accent: Color? = null,
    modifier: Modifier = Modifier,
) {
    val c = LocalStatusColors.current
    val tone = accent ?: c.ok

    SkinCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = Metrics.inset)) {
            // 大标题：M3 风格用 display 级别（更粗更大），澎湃风格沿用 30sp
            if (LocalDesignStyle.current == DesignStyle.Material3) {
                androidx.compose.material3.Text(
                    text = title,
                    style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                    color = tone,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = title,
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                    style = MiuixTheme.textStyles.title1,
                    color = tone,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = hint,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    textAlign = TextAlign.Start,
                )
            }

            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { chip ->
                        Pill(
                            text = chip.text,
                            color = chip.color,
                            onColor = chip.onColor,
                        )
                    }
                }
            }

            if (onToggleFullStatus != null) {
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    title = "显示完整状态",
                    subtitle = "展开内核与设备的详细信息",
                    checked = showFullStatus,
                    onCheckedChange = onToggleFullStatus,
                )
            }

            // 折叠展开：高度撑开 + 淡入，收起时收回 + 淡出
            ExpandableSection(
                visible = showFullStatus && rows.isNotEmpty(),
                expandFromTop = false,
            ) {
                Spacer(Modifier.height(6.dp))
                rows.forEach { (k, v) ->
                    InfoRow(k, v)
                }
            }
        }
    }
}

/** 状态芯片。 */
data class Chip(
    val text: String,
    val color: Color,
    val onColor: Color,
)
