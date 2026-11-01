package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.clickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.AppManager
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.DeviceOwner
import com.codefox.paperlite.core.Installer
import com.codefox.paperlite.data.AppEntry
import com.codefox.paperlite.data.DeviceInfo
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.ActionSheet
import com.codefox.paperlite.ui.components.ActionSheetEntry
import com.codefox.paperlite.ui.components.ConfirmDialog
import com.codefox.paperlite.ui.components.EmptyHint
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用页。
 *
 * 搜索 → 勾选 → 冻结 / 解冻 / 卸载 / 清数据 / 提取 APK / 防卸载保护 / 打开详情。
 *
 * 交互约定：
 * - **所有按钮始终可点**：没有可用通道时，点击后由底层给出明确提示，
 *   而不是把按钮置灰让人猜。
 * - **所有弹出的东西都带过渡动画**：确认弹窗从轻微缩小 + 上浮进入，
 *   动作面板从底部滑入，操作反馈从底部滑入淡出。
 */
@Composable
fun AppsPage(channels: ChannelManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { Installer(context, channels) }
    val listState = rememberLazyListState()

    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }

    // 待确认的卸载目标：非 null 时弹确认框
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    // 待确认的清数据目标
    var pendingClear by remember { mutableStateOf<String?>(null) }
    // 当前展开动作面板的应用
    var sheetApp by remember { mutableStateOf<AppEntry?>(null) }
    // APK 提取结果
    var extractInfo by remember { mutableStateOf<AppManager.ExtractResult?>(null) }

    // 扫描序号：切页过渡时可能同时存在两份页面实例，
    // 用序号保证只有最新一次扫描的结果会写回状态
    val scanSeq = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    /**
     * 扫描应用列表。
     *
     * 整个过程都在 IO 线程，并且外包一层 runCatching：
     * 某些机型 `getInstalledPackages` 会抛 SecurityException，
     * 某些机型 `blockedPackages` 在 owner 被回收后会抛 DeadObjectException。
     */
    suspend fun scan(includeSys: Boolean): Pair<List<AppEntry>, String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val blocked = DeviceOwner.blockedPackages(context).toSet()
                DeviceInfo.installedApps(context, includeSys) { it in blocked } to null
            }.getOrElse { e ->
                emptyList<AppEntry>() to "读取应用列表失败：${e.message ?: e::class.java.simpleName}"
            }
        }

    LaunchedEffect(includeSystem) {
        val seq = scanSeq.incrementAndGet()
        loading = true
        val (list, err) = scan(includeSystem)
        if (seq != scanSeq.get()) return@LaunchedEffect
        apps = list
        if (err != null) message = err
        loading = false
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
    }

    fun refresh() {
        scope.launch {
            val seq = scanSeq.incrementAndGet()
            loading = true
            val (list, err) = scan(includeSystem)
            if (seq != scanSeq.get()) return@launch
            apps = list
            if (err != null) message = err
            loading = false
        }
    }

    fun toggle(pkg: String) {
        selected = if (pkg in selected) selected - pkg else selected + pkg
    }

    /** 统一包一层：单次操作抛异常时不要在协程里炸掉整个 Activity。 */
    fun guarded(label: String, block: suspend () -> String) {
        scope.launch {
            val msg = runCatching { block() }
                .getOrElse { "$label 失败：${it.message ?: it::class.java.simpleName}" }
            message = msg
            refresh()
        }
    }

    fun doFreeze(app: AppEntry) {
        // enabled 为 true 说明正在运行，此时动作是「冻结」
        guarded("冻结") { installer.freeze(app.packageName, app.enabled).message }
    }

    fun doUninstall(pkg: String) {
        guarded("卸载") { installer.uninstall(pkg, keepData = false).message }
    }

    fun doClearData(pkg: String) {
        guarded("清除数据") { installer.clearData(pkg).message }
    }

    fun doExtract(pkg: String) {
        scope.launch {
            message = "正在提取 $pkg …"
            val r = AppManager.extractApk(context, channels, pkg)
            extractInfo = r
            message = r.message
        }
    }

    fun doToggleProtection(app: AppEntry) {
        scope.launch {
            val msg = AppManager.toggleUninstallProtection(
                context = context,
                channels = channels,
                packageName = app.packageName,
                blocked = !app.uninstallBlocked,
            )
            message = msg
            refresh()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
        ) {
            item(key = "header") {
                PageHeader(
                    title = "应用",
                    kicker = "共 ${filtered.size} 个应用",
                    trailing = {
                        ActionButton(text = "刷新", onClick = { refresh() })
                    },
                )
            }

            item(key = "search") {
                TextInput(
                    value = query,
                    onValueChange = { query = it },
                    label = "输入应用名称或包名",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.pad, vertical = 4.dp),
                    singleLine = true,
                )
            }

            item(key = "opts") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.pad, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "包含系统应用",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "关闭后只显示第三方应用",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                        checked = includeSystem,
                        onCheckedChange = { includeSystem = it },
                        colors = com.codefox.paperlite.ui.theme.paperSwitchColors(),
                    )
                    }
                }
            }

            // 批量操作条
            item(key = "batch") {
                SectionCard(
                    title = "批量选择",
                    subtitle = "已勾选 ${selected.size} 个",
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActionButton(
                            text = "全选当前",
                            onClick = {
                                selected = filtered.map { it.packageName }.toSet()
                                message = "已选中 ${selected.size} 个应用"
                            },
                        )
                        ActionButton(
                            text = "取消勾选",
                            onClick = {
                                selected = emptySet()
                                message = "已取消勾选"
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
                            text = "批量卸载",
                            onClick = {
                                val targets = selected.toList()
                                if (targets.isEmpty()) {
                                    message = "请先勾选要卸载的应用"
                                } else {
                                    scope.launch {
                                        var ok = 0
                                        var fail = 0
                                        for (pkg in targets) {
                                            val r = runCatching { installer.uninstall(pkg, keepData = false) }
                                            if (r.getOrNull()?.success == true) ok++ else fail++
                                        }
                                        message = "卸载完成：成功 $ok，失败 $fail"
                                        selected = emptySet()
                                        refresh()
                                    }
                                }
                            },
                        )
                        ActionButton(
                            text = "批量冻结",
                            onClick = {
                                val targets = selected.toList()
                                if (targets.isEmpty()) {
                                    message = "请先勾选要冻结的应用"
                                } else {
                                    scope.launch {
                                        var ok = 0
                                        for (pkg in targets) {
                                            if (runCatching { installer.freeze(pkg, true) }
                                                    .getOrNull()?.success == true
                                            ) ok++
                                        }
                                        message = "已冻结 $ok 个应用"
                                        refresh()
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (loading) {
                item(key = "loading") { EmptyHint("正在加载应用列表…") }
            } else if (filtered.isEmpty()) {
                item(key = "empty") { EmptyHint("没有匹配的应用") }
            }

            items(filtered, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    checked = app.packageName in selected,
                    onToggle = { toggle(app.packageName) },
                    onFreeze = { doFreeze(app) },
                    onUninstall = { pendingUninstall = app.packageName },
                    onClearData = { pendingClear = app.packageName },
                    onMore = { sheetApp = app },
                )
            }
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

        // 卸载确认：带缩放 + 上浮入场动画
        val uninstallTarget = pendingUninstall
        if (uninstallTarget != null) {
            val label = apps.firstOrNull { it.packageName == uninstallTarget }?.label ?: uninstallTarget
            ConfirmDialog(
                title = "卸载 $label？",
                message = "包名 $uninstallTarget。卸载后应用数据会被清除，此操作不可撤销。",
                confirmText = "卸载",
                danger = true,
                onConfirm = {
                    pendingUninstall = null
                    doUninstall(uninstallTarget)
                },
                onDismiss = { pendingUninstall = null },
            )
        }

        // 清数据确认
        val clearTarget = pendingClear
        if (clearTarget != null) {
            val label = apps.firstOrNull { it.packageName == clearTarget }?.label ?: clearTarget
            ConfirmDialog(
                title = "清除 $label 的数据？",
                message = "包名 $clearTarget。应用的所有本地数据会被清空，账号需要重新登录。",
                confirmText = "清除",
                danger = true,
                onConfirm = {
                    pendingClear = null
                    doClearData(clearTarget)
                },
                onDismiss = { pendingClear = null },
            )
        }

        // 动作面板：更多操作
        val sheet = sheetApp
        if (sheet != null) {
            ActionSheet(
                title = "${sheet.label}\n${sheet.packageName}",
                actions = listOf(
                    ActionSheetEntry(
                        text = if (sheet.enabled) "冻结应用" else "解冻应用",
                        subtitle = "停用 / 恢复运行",
                    ) {
                        doFreeze(sheet)
                    },
                    ActionSheetEntry(
                        text = "清除应用数据",
                        subtitle = "清空本地数据",
                    ) {
                        pendingClear = sheet.packageName
                    },
                    ActionSheetEntry(
                        text = "提取 APK",
                        subtitle = "导出并计算校验值",
                    ) {
                        doExtract(sheet.packageName)
                    },
                    ActionSheetEntry(
                        text = if (sheet.uninstallBlocked) "解除防卸载保护" else "开启防卸载保护",
                        subtitle = "重启后依然有效",
                    ) {
                        doToggleProtection(sheet)
                    },
                    ActionSheetEntry(text = "打开应用详情", subtitle = "系统设置") {
                        DeviceInfo.openAppInfo(context, sheet.packageName)
                    },
                    ActionSheetEntry(
                        text = "卸载应用",
                        subtitle = "不可撤销",
                        danger = true,
                    ) {
                        pendingUninstall = sheet.packageName
                    },
                ),
                onDismiss = { sheetApp = null },
            )
        }

        // APK 提取结果：带动画的详情面板
        val ex = extractInfo
        if (ex != null) {
            ActionSheet(
                title = "APK 提取结果",
                actions = listOf(
                    ActionSheetEntry(
                        text = ex.path ?: "未生成文件",
                        subtitle = "SHA-256 ${ex.sha256?.take(16) ?: "-"}…",
                    ) {},
                    ActionSheetEntry(
                        text = "SHA-1 ${ex.sha1?.take(16) ?: "-"}…",
                        subtitle = "完整校验值",
                    ) {},
                ),
                onDismiss = { extractInfo = null },
            )
        }
    }
}

/** 行级操作只在点击时执行，不参与重组比较。 */
private val Noop: () -> Unit = {}

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    onToggle: () -> Unit = Noop,
    onFreeze: () -> Unit = Noop,
    onUninstall: () -> Unit = Noop,
    onClearData: () -> Unit = Noop,
    onMore: () -> Unit = Noop,
) {
    val c = LocalStatusColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.pad, vertical = 3.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.inset, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    text = if (checked) "已选" else "选择",
                    color = if (checked) MiuixTheme.colorScheme.primary else c.neutral,
                    onColor = if (checked) MiuixTheme.colorScheme.onPrimary else c.onNeutral,
                    modifier = Modifier.clickable { onToggle() },
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.label,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (app.isSystem) {
                            Spacer(Modifier.width(6.dp))
                            Pill(text = "系统", color = c.info, onColor = c.onInfo)
                        }
                        if (app.uninstallBlocked) {
                            Spacer(Modifier.width(6.dp))
                            Pill(text = "已保护", color = c.ok, onColor = c.onOk)
                        }
                        if (app.suspended) {
                            Spacer(Modifier.width(6.dp))
                            Pill(text = "已停用", color = c.warn, onColor = c.onWarn)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${app.packageName} · v${app.versionName} · ${DeviceInfo.formatSize(app.sizeBytes)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.inset, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionButton(
                    text = if (app.enabled) "冻结" else "解冻",
                    onClick = onFreeze,
                )
                ActionButton(text = "卸载", onClick = onUninstall)
                ActionButton(text = "清数据", onClick = onClearData)
                ActionButton(text = "更多", onClick = onMore)
            }
        }
    }
}
