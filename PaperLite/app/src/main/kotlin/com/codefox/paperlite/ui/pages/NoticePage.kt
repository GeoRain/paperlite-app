package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.codefox.paperlite.core.Notice
import com.codefox.paperlite.data.Settings
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.EmptyHint
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 公告页。
 *
 * 设计前提：**服务器不是全天候开放的**，所以公告不能依赖服务器在线。
 *
 * - 本地公告始终可用，写一次就在，离线照样看得到
 * - 远程地址是可选增强，能连上就更新，连不上自动回落到本地那份
 * - 页面顶部直接标明当前公告来自哪里，不含糊
 */
@Composable
fun NoticePage(
    settings: Settings,
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var state by remember { mutableStateOf(Notice.read(context, settings)) }
    var urlInput by remember { mutableStateOf(settings.noticeUrl.value) }
    var message by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    val c = LocalStatusColors.current

    // 进入页面时，如果填了地址就尝试刷新一次；拉不到也不影响本地公告
    LaunchedEffect(Unit) {
        if (settings.noticeUrl.value.isNotBlank()) {
            refreshing = true
            state = Notice.refresh(context, settings)
            refreshing = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            if (showHeader) {
                item(key = "header") {
                    PageHeader(
                        title = "公告",
                        kicker = when {
                            refreshing -> "正在更新"
                            state.items.isEmpty() -> "暂无公告"
                            state.source == Notice.Source.Remote -> "已更新 · ${state.items.size} 条"
                            state.source == Notice.Source.LocalFallback -> "离线 · 显示本地公告"
                            else -> "本地公告 · ${state.items.size} 条"
                        },
                        trailing = {
                            ActionButton(
                                text = if (refreshing) "更新中…" else "更新",
                                onClick = {
                                    if (refreshing) return@ActionButton
                                    refreshing = true
                                    scope.launch {
                                        state = Notice.refresh(context, settings)
                                        refreshing = false
                                        message = if (state.source == Notice.Source.Remote) {
                                            "公告已更新"
                                        } else {
                                            "未拉到新公告，继续显示本地内容"
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            } else {
                // 内嵌在设置页时不画页头，但仍要能从这一页手动更新
                item(key = "header-compact") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Metrics.pad, end = Metrics.pad, top = 8.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionButton(
                            text = if (refreshing) "更新中…" else "更新公告",
                            onClick = {
                                if (refreshing) return@ActionButton
                                refreshing = true
                                scope.launch {
                                    state = Notice.refresh(context, settings)
                                    refreshing = false
                                    message = if (state.source == Notice.Source.Remote) {
                                        "公告已更新"
                                    } else {
                                        "未拉到新公告，继续显示本地内容"
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item(key = "source") {
                SectionCard(title = "公告来源", subtitle = "服务器关着也不影响使用") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill(
                            text = when (state.source) {
                                Notice.Source.Remote -> "远程"
                                Notice.Source.LocalFallback -> "本地缓存"
                                Notice.Source.Local -> "本地"
                            },
                            color = when (state.source) {
                                Notice.Source.Remote -> c.ok
                                Notice.Source.LocalFallback -> c.warn
                                Notice.Source.Local -> c.info
                            },
                            onColor = when (state.source) {
                                Notice.Source.Remote -> c.onOk
                                Notice.Source.LocalFallback -> c.onWarn
                                Notice.Source.Local -> c.onInfo
                            },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = when (state.source) {
                                Notice.Source.Remote -> "已从远程地址获取最新公告"
                                Notice.Source.LocalFallback -> "远程不可达，当前显示上次缓存的公告"
                                Notice.Source.Local -> "当前显示本机保存的公告"
                            },
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.updatedAt > 0) {
                        Text(
                            text = "更新时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(state.updatedAt))}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 2.dp),
                        )
                    }
                }
            }

            if (state.items.isEmpty()) {
                item(key = "empty") {
                    EmptyHint("暂无公告内容")
                }
            } else {
                items(state.items.size, key = { "notice-${state.items[it].id}" }) { index ->
                    NoticeCard(state.items[index])
                }
            }

            item(key = "source-config") {
                SectionCard(
                    title = "远程公告地址",
                    subtitle = "可选。留空则完全离线使用本地公告",
                ) {
                    TextInput(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = "https://example.com/notice.json",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 3.dp),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ActionButton(
                            text = "保存地址",
                            onClick = {
                                settings.setNoticeUrl(urlInput)
                                message = if (urlInput.isBlank()) {
                                    "已清空地址，只用本地公告"
                                } else {
                                    "地址已保存"
                                }
                            },
                        )
                        ActionButton(
                            text = "清除本地",
                            onClick = {
                                scope.launch {
                                    val ok = Notice.writeLocal(context, emptyList())
                                    state = Notice.read(context, settings)
                                    message = if (ok) "本地公告已清除" else "清除失败"
                                }
                            },
                        )
                    }
                    BulletRow("接口接受两种格式：{\"items\":[...]} 或直接的数组 [...]。")
                    BulletRow("每条公告字段：id、title、body、date、level（info / warn / important）。")
                    BulletRow("远程拉取失败不会报错，自动回落到本地缓存的公告。")
                }
            }
        }

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

@Composable
private fun NoticeCard(item: Notice.Item) {
    val c = LocalStatusColors.current
    val accent = when (item.level) {
        Notice.Level.Info -> c.info
        Notice.Level.Warn -> c.warn
        Notice.Level.Important -> c.danger
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.pad, vertical = 3.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = Metrics.inset, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.title.isNotBlank()) {
                    Text(
                        text = item.title,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (item.level != Notice.Level.Info) {
                    Spacer(Modifier.width(8.dp))
                    Pill(
                        text = if (item.level == Notice.Level.Important) "重要" else "提醒",
                        color = accent,
                        onColor = when (item.level) {
                            Notice.Level.Warn -> c.onWarn
                            else -> c.onDanger
                        },
                    )
                }
            }
            if (item.date.isNotBlank()) {
                Text(
                    text = item.date,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.body,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
