package com.codefox.paperlite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.LocalDesignStyle
import com.codefox.paperlite.ui.theme.LocalShapes
import com.codefox.paperlite.ui.theme.LocalStatusColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 风险提示条。
 *
 * ## 用在哪
 *
 * 应用商店这种**替用户引入第三方代码**的地方必须挂一条。
 * 商店里的 APK 来自 F-Droid 索引（国内走镜像），PaperLite 只做了下载与安装，
 * 没有对任何一个包做安全审查——这是必须让用户知道的事实，
 * 不能藏在一句「点一下就能装」里。
 *
 * ## 视觉
 *
 * 用警告色（[LocalStatusColors.warn]）做底 + 左侧一个提示图标，
 * 比正文里的普通说明句重得多，用户扫一眼就能看到。
 * 不用红色：红色在这个应用里代表「执行失败 / 出错了」，
 * 这里是长期存在的固定声明，用警告色更贴切。
 *
 * 两套风格共用，形状阶梯随风格切换。
 */
@Composable
fun WarnBanner(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalStatusColors.current
    val shapes = LocalShapes.current
    val m3 = LocalDesignStyle.current == DesignStyle.Material3

    val container = c.warn.copy(alpha = if (m3) 0.12f else 0.14f)
    val onContainer = if (m3) {
        androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    } else {
        MiuixTheme.colorScheme.onSurfaceContainer
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.pad, vertical = Metrics.gap / 2)
            .clip(RoundedCornerShape(shapes.cornerMedium))
            .background(container)
            .padding(horizontal = Metrics.inset, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.material3.Icon(
            imageVector = MiuixIcons.Info,
            contentDescription = null,
            tint = c.warn,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (m3) {
                androidx.compose.material3.Text(
                    text = title,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.footnote1,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            if (m3) {
                androidx.compose.material3.Text(
                    text = message,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.82f),
                )
            } else {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote2,
                    color = onContainer.copy(alpha = 0.82f),
                )
            }
        }
    }
}
