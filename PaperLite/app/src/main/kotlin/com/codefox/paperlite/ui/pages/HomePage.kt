package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.Channel
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.ChannelState
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.GroupTitle
import com.codefox.paperlite.ui.components.MetricTile
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 概览页。
 *
 * 只回答两件事：现在有几条通道可用、怎么把通道打通。
 * 所有具体功能都各自独立成页，这里只放状态与入口。
 */
@Composable
fun HomePage(
    channels: ChannelManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val states by channels.states.collectAsStateWithLifecycle()
    val busy by channels.busy.collectAsStateWithLifecycle()
    val available = remember(states) { states.values.count { it.available } }

    LaunchedEffect(Unit) {
        if (states.values.none { it.detail != "未检测" }) {
            channels.probeAll()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            PageHeader(
                title = "概览",
                kicker = if (busy != null) busy else "$available / ${states.size} 条通道可用",
            )
        }

        item(key = "dashboard") {
            Dashboard(
                available = available,
                total = states.size,
            )
        }

        item(key = "actions") {
            SectionCard(title = "刷新状态") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ActionButton(
                        text = "重新探测通道",
                        onClick = {
                            scope.launch { channels.probeAll() }
                        },
                    )
                }
                BulletRow("探测只读取通道状态，不做任何改动。")
            }
        }

        item(key = "channel-title") { GroupTitle("通道") }

        items(Channel.entries, key = { "ch-${it.name}" }) { channel ->
            ChannelRow(state = states[channel] ?: ChannelState(channel))
        }
    }
}

@Composable
private fun Dashboard(
    available: Int,
    total: Int,
) {
    val c = LocalStatusColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.pad, vertical = Metrics.gap / 2),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricTile(
            value = "$available/$total",
            label = "通道可用",
            accent = if (available > 0) c.ok else c.neutral,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            value = if (available > 0) "就绪" else "未就绪",
            label = "能力状态",
            accent = if (available > 0) c.ok else c.neutral,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            value = "9",
            label = "功能页",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChannelRow(state: ChannelState) {
    val c = LocalStatusColors.current
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.inset, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (state.available) c.ok else c.neutral),
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.channel.title,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.detail,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Pill(
                text = state.statusText,
                color = if (state.available) c.ok else c.neutral,
                onColor = if (state.available) c.onOk else c.onNeutral,
            )
        }
    }
}
