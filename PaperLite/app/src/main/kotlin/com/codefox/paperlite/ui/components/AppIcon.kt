package com.codefox.paperlite.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.codefox.paperlite.ui.theme.DesignStyle
import com.codefox.paperlite.ui.theme.LocalDesignStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 本应用自己的图标（APK 图标）。
 *
 * ## 为什么不用 `painterResource(R.mipmap.ic_launcher)`
 *
 * `mipmap-anydpi-v26/ic_launcher.xml` 是**自适应图标**（adaptive-icon），
 * 它不是位图也不是矢量图，`painterResource` 解不出来；
 * 而 `mipmap-*dpi/ic_launcher.png` 是给 API 25 及更早用的旧版方形图标，
 * 在 API 26+ 上会被 anydpi 覆盖掉，永远取不到。
 *
 * 直接问 PackageManager 要 `getApplicationIcon()` 拿到的是**系统正在用的那个图标**
 * （自适应图标的完整渲染结果，含背景层与前景层），
 * 这才是真正意义上「这个 APK 的图标」——桌面上看到的那个，一模一样。
 *
 * 拿不到时（极少数定制 ROM 会给应用塞一个坏图标）退回 "PL" 字样，
 * 不能因为一个图标把整个界面画崩。
 */
@Composable
fun rememberAppIconBitmap(): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(context) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                // 固定尺寸解码：自适应图标的 intrinsicWidth 有可能是 -1，
                // 交给 toBitmap 自己量会拿到 0×0 的空图。
                drawable.toBitmap(width = 192, height = 192).asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * 应用图标（带圆角裁切）。
 *
 * 用于侧边栏左上角的品牌位：之前那里是一块写着 "PL" 的色块，
 * 现在换成真正的 APK 图标。
 *
 * 两套风格共用同一个组件，容器色与回退文字的样式随风格切换。
 */
@Composable
fun AppIcon(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    corner: Dp = 20.dp,
) {
    val bitmap = rememberAppIconBitmap()
    val m3 = LocalDesignStyle.current == DesignStyle.Material3

    val container = if (m3) {
        androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
    } else {
        MiuixTheme.colorScheme.primaryContainer
    }
    val onContainer = if (m3) {
        androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MiuixTheme.colorScheme.onPrimaryContainer
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            if (m3) {
                androidx.compose.material3.Text(
                    text = "PL",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    color = onContainer,
                )
            } else {
                Text(
                    text = "PL",
                    style = MiuixTheme.textStyles.footnote1,
                    color = onContainer,
                )
            }
        }
    }
}
