package com.codefox.paperlite.ui.pages

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.channel.PairingCaptureService
import com.codefox.paperlite.data.Settings
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 无线调试配对页。
 *
 * 配对是终端与其他高权限能力的前置条件，所以单独成页。
 * 设备所有者、无障碍这些在本机权限下跑不通的入口已整体移除。
 */
@Composable
fun PairingPage(
    channels: ChannelManager,
    settings: Settings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val states by channels.states.collectAsStateWithLifecycle()
    val busy by channels.busy.collectAsStateWithLifecycle()
    val pairPort by settings.pairPort.collectAsStateWithLifecycle()
    val connectPort by settings.connectPort.collectAsStateWithLifecycle()

    var pairCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    val captureResult by PairingCaptureService.result.collectAsStateWithLifecycle()

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            PairingCaptureService.start(context, result.resultCode, result.data!!)
            message = "正在识别配对码，请停留在配对页面"
        } else {
            message = "用户拒绝屏幕读取授权"
        }
    }

    LaunchedEffect(captureResult) {
        captureResult?.let {
            if (it.code != null) {
                pairCode = it.code
                message = "已识别配对码：${it.code}"
            } else if (it.error != null) {
                message = it.error
            }
            PairingCaptureService.consume()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = "配对",
                kicker = if (busy != null) busy!! else "无线调试 · TLS-PSK",
            )
        }

        item(key = "status") {
            val c = LocalStatusColors.current
            SectionCard(
                title = "会话状态",
                subtitle = states[Channel.Wireless]?.detail ?: "未配对",
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Pill(
                        text = if (states[Channel.Wireless]?.available == true) "已连接" else "未连接",
                        color = if (states[Channel.Wireless]?.available == true) c.ok else c.neutral,
                        onColor = if (states[Channel.Wireless]?.available == true) c.onOk else c.onNeutral,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "配对端口 $pairPort · 连接端口 $connectPort",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item(key = "input") {
            SectionCard(title = "配对码", subtitle = "系统配对对话框里显示的 6 位数字") {
                TextInput(
                    value = pairCode,
                    onValueChange = { pairCode = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = "6 位配对码",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        text = if (busy != null) "处理中…" else "开始配对",
                        onClick = {
                            scope.launch {
                                val r = channels.pair(pairCode)
                                message = r.message
                            }
                        },
                    )
                    ActionButton(
                        text = "识别配对码",
                        onClick = {
                            val mpm = context.getSystemService(
                                android.content.Context.MEDIA_PROJECTION_SERVICE,
                            ) as MediaProjectionManager
                            runCatching { projectionLauncher.launch(mpm.createScreenCaptureIntent()) }
                                .onFailure { message = "无法请求屏幕读取授权：${it.message}" }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        text = "搜索端口",
                        onClick = {
                            scope.launch {
                                val (p, cp) = channels.discoverPorts()
                                message = "配对端口 $p，连接端口 $cp"
                            }
                        },
                    )
                    ActionButton(
                        text = "断开",
                        onClick = {
                            scope.launch {
                                channels.disconnect()
                                message = "已断开无线调试会话"
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        text = "打开无线调试页面",
                        onClick = {
                            val intents = listOf(
                                Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
                                Intent(android.provider.Settings.ACTION_SETTINGS),
                            )
                            var opened = false
                            for (i in intents) {
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (runCatching { context.startActivity(i) }.isSuccess) {
                                    opened = true
                                    break
                                }
                            }
                            if (!opened) message = "无法打开系统设置页"
                        },
                    )
                    ActionButton(
                        text = "清理配对",
                        onClick = {
                            scope.launch {
                                channels.identityStore().clear()
                                channels.cleanupSystemPairings()
                                message = "配对记录已清理，请重新配对"
                            }
                        },
                    )
                }
            }
        }

        item(key = "notes") {
            SectionCard(title = "说明") {
                BulletRow("配对需要在系统设置里开启无线调试，并停留在配对对话框页面。")
                BulletRow("识别配对码需要一次性屏幕读取授权，识别完成即停止，不保存截图。")
                BulletRow("配对成功后，终端页与安装页即可使用高权限命令。")
            }
        }

        item(key = "sp") { Spacer(Modifier.height(8.dp)) }
    }
        // 反馈消息：从底部滑入淡出
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ToastMessage(text = message)
        }
    }
}
