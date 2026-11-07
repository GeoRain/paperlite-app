package com.codefox.paperlite.ui.pages

import com.codefox.paperlite.ui.components.PageTabs
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.lazy.items
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
import com.codefox.paperlite.core.AppStore
import com.codefox.paperlite.core.ApkLibrary
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.Installer
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.BulletRow
import com.codefox.paperlite.ui.components.EmptyHint
import com.codefox.paperlite.ui.components.HamburgerMenu
import com.codefox.paperlite.ui.components.IconButton
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.MenuEntry
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.components.WarnBanner
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用商店页内的两个主标签。 */
private val STORE_TABS = listOf("商店", "已下载")

/**
 * 应用商店页（抄「应用商店」）。
 *
 * 那个应用的关键行为是：**下载完立刻静默安装**——它是系统应用，
 * 拿得到 `INSTALL_PACKAGES`，所以 `PackageInstaller` 会话不需要用户确认。
 *
 * PaperLite 没有那个权限，但静默链路更宽（root / 作业帮系统服务 / 设备所有者），
 * 所以这里的流程是：**下载 → 自动静默安装 → 刷新已安装列表**，
 * 用户只需要点一下「下载并安装」。
 *
 * ## 风险提示为什么放在最上面
 *
 * 商店里的 APK 来自第三方开源仓库（国内走镜像），PaperLite **没有**对任何一个包
 * 做安全审查，只负责下载与安装。这是必须让用户在下载前就知道的事实，
 * 不能藏在页面底部或者一句小字里——所以做成一条警告色的横幅，
 * 紧跟在标题下面、列表上面，进页面第一眼就能看到。
 *
 * @param onBack 返回上一级（应用页的「已安装」列表）。
 *   这个页面是从「应用」页下钻进来的，没有返回就回不去了。
 */
@Composable
fun AppStorePage(
    channels: ChannelManager,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val installer = remember { Installer(context, channels) }
    val c = LocalStatusColors.current

    var entries by remember { mutableStateOf<List<AppStore.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var sourceUrl by remember { mutableStateOf(AppStore.DEFAULT_SOURCES.first().indexUrl) }
    var tab by rememberSaveable { mutableStateOf(STORE_TABS[0]) }
    var showSource by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf<List<ApkLibrary.Item>>(emptyList()) }

    LaunchedEffect(Unit) {
        loading = true
        val r = AppStore.fetch(AppStore.Source("默认源", sourceUrl))
        if (r.isSuccess) {
            entries = r.getOrDefault(emptyList())
        } else {
            message = "拉取列表失败：${r.exceptionOrNull()?.message}"
        }
        loading = false
    }

    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter {
            it.name.contains(query, true) || it.packageName.contains(query, true)
        }
    }

    fun downloadAndInstall(entry: AppStore.Entry) {
        loading = true
        scope.launch {
            message = "正在下载 ${entry.name}…"
            val d = AppStore.download(context, entry.apkUrl)
            if (d.isFailure) {
                message = "下载失败：${d.exceptionOrNull()?.message}"
                loading = false
                return@launch
            }
            val file = d.getOrThrow()
            message = "下载完成，正在静默安装 ${entry.name}…"
            val r = installer.install(file)
            message = "${entry.name}：${r.via} · ${r.message}"
            loading = false
            installed = ApkLibrary.scan(context)
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
                    title = "应用商店",
                    kicker = if (loading) "处理中…" else "${filtered.size} 个应用",
                    leading = if (onBack != null) {
                        {
                            IconButton(
                                icon = MiuixIcons.Back,
                                contentDescription = "返回",
                                onClick = onBack,
                            )
                        }
                    } else {
                        null
                    },
                    trailing = {
                        // 低频功能收进汉堡菜单：源设置配一次就不动了，
                        // 不该占着页面顶部跟高频的 Tab 抢位置
                        HamburgerMenu(
                            items = listOf(
                                MenuEntry("源设置", "自定义列表接口") { showSource = true },
                                MenuEntry("刷新列表", "重新拉取应用列表") {
                                    scope.launch {
                                        loading = true
                                        val r = AppStore.fetch(AppStore.Source("默认源", sourceUrl))
                                        if (r.isSuccess) {
                                            entries = r.getOrDefault(emptyList())
                                            message = "拉到 ${entries.size} 个应用"
                                        } else {
                                            message = "拉取失败：${r.exceptionOrNull()?.message}"
                                        }
                                        loading = false
                                    }
                                },
                            ),
                        )
                    },
                )
            }

            // ---- 风险提示横幅 ----
            // 放在列表正上方：用户准备点「下载并安装」之前，
            // 一定会先扫过这一条。
            item(key = "risk") {
                WarnBanner(
                    title = "风险提示",
                    message = "应用原应用未经过开发者审查！出事故不能找我负责。" +
                        "商店里的 APK 来自第三方开源仓库，PaperLite 只做下载与安装，" +
                        "不保证其安全性，请自行确认来源后再安装。",
                )
            }

            // 页内 Tab 栏：只在这个页面显示，不污染其它页
            item(key = "tabs") {
                PageTabs(
                    options = STORE_TABS,
                    selected = tab,
                    label = { it },
                    onSelect = { tab = it },
                )
            }

            if (tab == "商店") {
                item(key = "search") {
                    TextInput(
                        value = query,
                        onValueChange = { query = it },
                        label = "搜索应用",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.pad, vertical = 4.dp),
                        singleLine = true,
                    )
                }
                if (loading && entries.isEmpty()) {
                    item(key = "loading") { EmptyHint("正在拉取应用列表…") }
                } else if (filtered.isEmpty()) {
                    item(key = "empty") { EmptyHint("没有拉到应用，右上角菜单里可以换列表源") }
                }
                items(filtered, key = { it.packageName + it.versionName }) { entry ->
                    StoreRow(
                        entry = entry,
                        busy = loading,
                        onInstall = { downloadAndInstall(entry) },
                    )
                }
                item(key = "tip") {
                    SectionCard(title = "说明") {
                        BulletRow(
                            "下载完成后会自动走静默安装链路装上，不需要再点系统安装器。",
                        )
                        BulletRow("静默安装优先用作业帮系统服务，其次 root，最后才回退设备所有者会话。")
                        BulletRow(
                            "本软件使用外部链接获取到的应用未经过开发者审查！出事故不能找我负责。",
                            accent = c.warn,
                        )
                    }
                }
            }

            if (tab == "已下载") {
                item(key = "local-title") {
                    SectionCard(title = "本机已下载的安装包", subtitle = "点一下就能装") {
                        if (installed.isEmpty()) {
                            InfoRow("安装包", "没有找到")
                        } else {
                            installed.take(40).forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Metrics.inset, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.label,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${item.packageName} · v${item.versionName}",
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    ActionButton(
                                        text = "安装",
                                        onClick = {
                                            scope.launch {
                                                val r = installer.install(item.file)
                                                message = "${r.via} · ${r.message}"
                                                installed = ApkLibrary.scan(context)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showSource) {
                item(key = "source") {
                    SectionCard(title = "列表源", subtitle = "换一个能用的接口") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = "关闭",
                                onClick = { showSource = false },
                            )
                        }
                        TextInput(
                            value = sourceUrl,
                            onValueChange = { sourceUrl = it },
                            label = "列表接口地址",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 4.dp),
                            singleLine = true,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                text = "拉取",
                                onClick = {
                                    loading = true
                                    scope.launch {
                                        val r = AppStore.fetch(AppStore.Source("自定义", sourceUrl))
                                        if (r.isSuccess) {
                                            entries = r.getOrDefault(emptyList())
                                            message = "拉到 ${entries.size} 个应用"
                                            tab = "商店"
                                            showSource = false
                                        } else {
                                            message = "拉取失败：${r.exceptionOrNull()?.message}"
                                        }
                                        loading = false
                                    }
                                },
                            )
                            ActionButton(
                                text = "恢复默认",
                                onClick = {
                                    sourceUrl = AppStore.DEFAULT_SOURCES.first().indexUrl
                                    message = "已恢复默认源"
                                },
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppStore.DEFAULT_SOURCES.forEach { src ->
                                ActionButton(
                                    text = src.name.substringBefore("（"),
                                    onClick = {
                                        sourceUrl = src.indexUrl
                                        message = "已切到 ${src.name}"
                                    },
                                )
                            }
                        }
                        BulletRow("默认源是开源应用仓库的 index-v2 索引（国内走镜像），公开无签名。")
                        BulletRow("索引里带官方社区翻译的中文名与简介，没有中文的会显示英文原名。")
                    }
                }
            }

            item(key = "sp") { Spacer(Modifier.height(8.dp)) }
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

/** 商店列表的一行。 */
@Composable
private fun StoreRow(
    entry: AppStore.Entry,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    val c = LocalStatusColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.pad, vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.inset, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.packageName} · v${entry.versionName} · ${entry.sizeText}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Pill(text = "免费", color = c.info, onColor = c.onInfo)
            Spacer(Modifier.width(8.dp))
            ActionButton(
                text = if (busy) "处理中…" else "下载并安装",
                onClick = onInstall,
            )
        }
    }
}
