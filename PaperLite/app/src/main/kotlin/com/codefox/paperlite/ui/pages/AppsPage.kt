package com.codefox.paperlite.ui.pages

import com.codefox.paperlite.ui.components.PageTabs
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.codefox.paperlite.ui.theme.Motion
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codefox.paperlite.App
import com.codefox.paperlite.core.AppManager
import com.codefox.paperlite.core.ApkLibrary
import com.codefox.paperlite.core.ChannelManager
import com.codefox.paperlite.core.DeviceOwner
import com.codefox.paperlite.core.Installer
import com.codefox.paperlite.core.ZybosApi
import com.codefox.paperlite.data.AppEntry
import com.codefox.paperlite.data.DeviceInfo
import com.codefox.paperlite.data.InstallOptions
import com.codefox.paperlite.ui.components.AnimatedGroup
import com.codefox.paperlite.ui.components.ActionButton
import com.codefox.paperlite.ui.components.ActionSheet
import com.codefox.paperlite.ui.components.ActionSheetEntry
import com.codefox.paperlite.ui.components.ConfirmDialog
import com.codefox.paperlite.ui.components.EmptyHint
import com.codefox.paperlite.ui.components.HamburgerMenu
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.MenuEntry
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.Pill
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.SegmentedTabs
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToggleRow
import com.codefox.paperlite.ui.components.ToastMessage
import com.codefox.paperlite.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用页的视图。 */
/**
 * 应用页的高频分组（页内 Tab 栏）。
 *
 * 「已安装 / 安装包 / 安装 / 卸载」是日常要用的，摆在 Tab 栏上一眼可见；
 * 「应用商店」是另一件事（在线下载），收进汉堡菜单。
 */
private val APP_TABS = listOf("已安装", "安装包", "安装", "卸载")

/**
 * 排序方式。
 *
 * 对齐 InstallerX 的 `sort_by_*`：
 * 安装时间 / 包名 / 逆向排序，另外这里多一个「应用名」，
 * 因为中文设备上按名字排最常用。
 */
private enum class AppSort(val title: String) {
    InstallTime("安装时间"),
    Name("名称"),
    PackageName("包名"),
    Reverse("逆向"),
}

/**
 * 应用列表的进程级缓存。
 *
 * 扫一遍已安装应用要遍历几百个包、每个包都发一次 IPC，
 * 在一台几百个应用的 Pad 上要 1～3 秒。旧版把这活放在每次进入页面时做，
 * 于是「点应用 → 卡住 → 白屏」就成了常态。
 *
 * 现在列表只在内存里留一份快照：切回来先秒出旧数据，再在后台刷新。
 */
private object AppListCache {
    private data class Snapshot(val list: List<AppEntry>, val at: Long)

    private val map = HashMap<Boolean, Snapshot>()

    @Synchronized
    fun get(includeSystem: Boolean): List<AppEntry> = map[includeSystem]?.list ?: emptyList()

    @Synchronized
    fun isFresh(includeSystem: Boolean, ttlMs: Long = 60_000L): Boolean {
        val s = map[includeSystem] ?: return false
        return System.currentTimeMillis() - s.at <= ttlMs
    }

    @Synchronized
    fun put(includeSystem: Boolean, list: List<AppEntry>) {
        map[includeSystem] = Snapshot(list, System.currentTimeMillis())
    }
}

/**
 * 应用页。
 *
 * 侧边栏「应用」是一个大功能，内部的四个子功能（已安装 / 安装包 / 安装 / 卸载）
 * 用**汉堡菜单**切换——Android 16 的做法是导航项只表达大功能，
 * 页内子功能收进 overflow 菜单，而不是在页面上压一排分段标签。
 *
 * 形态对齐 InstallerX：
 * - 列表上方是排序菜单与显示开关（安装时间 / 包名 / 逆向、显示系统应用、显示包名）；
 * - 安装包页列出下载目录里的 APK，并标注「未安装 / 可更新 / 已安装 / 需降级」；
 * - 安装选项（允许降级、测试包、多用户、自动删除、声明安装者）直接摆在安装包页顶部。
 *
 * 交互约定：
 * - **所有按钮始终可点**：没有可用通道时，点击后由底层给出明确提示，
 *   而不是把按钮置灰让人猜。
 * - **列表先出缓存再刷新**：切页不白屏。
 * - **同屏只有一个汉堡菜单**：下钻到「应用商店」时，本页的顶栏整体隐藏，
 *   交给商店页自己的标题栏（带返回），否则两个长得一样的汉堡并排出现，
 *   用户分不清哪个管什么。
 */
@Composable
fun AppsPage(channels: ChannelManager) {
    var tab by rememberSaveable { mutableStateOf(APP_TABS[0]) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏：当前子功能 + 汉堡菜单（子功能都在菜单里）
        //
        // **进入「应用商店」时整条顶栏都不画**：
        // 商店页自带完整的标题栏（标题 + 返回 + 汉堡），
        // 如果这里再画一条，同屏就会出现两个汉堡菜单——
        // 一个是「回应用列表」，一个是「源设置 / 刷新」，
        // 长得一模一样却管着完全不同的事，用户根本分不清哪个是哪个。
        // 所以下钻页面时上层的顶栏整体让位。
        if (tab != "应用商店") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Metrics.pad + 4.dp, end = Metrics.pad + 4.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "应用",
                        style = MiuixTheme.textStyles.title3,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = tab,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    )
                }
                HamburgerMenu(
                    items = listOf(
                        // 应用商店是独立的一件事，收在菜单里
                        MenuEntry(title = "应用商店", summary = "在线下载并自动安装") {
                            tab = "应用商店"
                        },
                    ),
                )
            }
        }
        // 页内 Tab 栏：只在本页显示，且在 AnimatedContent 之外
        // （放里面会跟着内容一起淡入淡出，Tab 本身就该是稳的）
        //
        // 「应用商店」是独立页面（从汉堡菜单进的），不属于 Tab 组，
        // 所以这时候整个 Tab 栏隐藏，不让它露出一个没有选中项的样子。
        if (tab in APP_TABS) {
            PageTabs(
                options = APP_TABS,
                selected = tab,
                label = { it },
                onSelect = { tab = it },
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 分组切换：淡入 + 轻微上移，避免「啪」地换掉整块内容
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (fadeIn(tween(Motion.LONG, easing = Motion.emphasized)) +
                        slideInVertically(tween(Motion.LONG, easing = Motion.emphasized)) {
                            Motion.switchOffset
                        })
                        .togetherWith(fadeOut(tween(Motion.QUICK, easing = Motion.exitEasing)))
                },
            label = "apps-tab",
            ) { t ->
                when (t) {
                    // 商店页自带标题栏与返回按钮，上层顶栏此时已隐藏，
                    // 所以同屏只有一个汉堡菜单。
                    "应用商店" -> AppStorePage(
                        channels = channels,
                        onBack = { tab = APP_TABS[0] },
                    )
                    "安装包" -> ApkLibrarySection(channels = channels)
                    "安装" -> InstallPage(channels = channels, showHeader = false)
                    "卸载" -> UninstallPage(channels = channels, showHeader = false)
                    else -> AppListSection(channels = channels)
                }
            }
        }
    }
}

/**
 * 已安装应用列表。
 *
 * 搜索 → 勾选 → 冻结 / 解冻 / 卸载 / 清数据 / 提取 APK / 防卸载保护 / 打开详情。
 */
@Composable
private fun AppListSection(channels: ChannelManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { Installer(context, channels) }
    val listState = rememberLazyListState()

    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var showPackageName by remember { mutableStateOf(true) }
    var sort by remember { mutableStateOf(AppSort.InstallTime) }
    var apps by remember { mutableStateOf(AppListCache.get(false)) }
    var loading by remember { mutableStateOf(apps.isEmpty()) }
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

    // 扫描序号：后台刷新与手动刷新可能同时进行，
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
        val cached = AppListCache.get(includeSystem)
        if (cached.isNotEmpty()) {
            // 有缓存先出内容，页面立刻可用
            apps = cached
            loading = false
            if (AppListCache.isFresh(includeSystem)) return@LaunchedEffect
        } else {
            loading = true
        }
        val seq = scanSeq.incrementAndGet()
        val (list, err) = scan(includeSystem)
        if (seq != scanSeq.get()) return@LaunchedEffect
        apps = list
        if (err != null) message = err
        if (err == null) AppListCache.put(includeSystem, list)
        loading = false
    }

    val filtered = remember(apps, query, sort) {
        val base = if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
        // 排序（对齐 InstallerX 的 sort_by_*）
        val sorted = when (sort) {
            AppSort.InstallTime -> base.sortedByDescending { it.installTime }
            AppSort.Name -> base.sortedBy { it.label }
            AppSort.PackageName -> base.sortedBy { it.packageName }
            AppSort.Reverse -> base.sortedBy { it.label }
        }
        if (sort == AppSort.Reverse) sorted.reversed() else sorted
    }

    fun refresh() {
        scope.launch {
            val seq = scanSeq.incrementAndGet()
            loading = true
            val (list, err) = scan(includeSystem)
            if (seq != scanSeq.get()) return@launch
            apps = list
            if (err != null) message = err
            if (err == null) AppListCache.put(includeSystem, list)
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
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item(key = "header") {
                PageHeader(
                    title = "已安装",
                    kicker = "共 ${filtered.size} 个应用",
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
                    Column {
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Metrics.inset, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "显示包名",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "列表项下方额外显示包名",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = showPackageName,
                                onCheckedChange = { showPackageName = it },
                                colors = com.codefox.paperlite.ui.theme.paperSwitchColors(),
                            )
                        }
                    }
                }
            }

            // 排序菜单（InstallerX 的 sort_by_*）
            item(key = "sort") {
                SectionCard(title = "排序", subtitle = "当前：${sort.title}") {
                    SegmentedTabs(
                        options = AppSort.entries,
                        selected = sort,
                        label = { it.title },
                        onSelect = { sort = it },
                        modifier = Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                    )
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
                // 列表项入场：淡入 + 轻微上移，筛掉几个应用后剩下的不会「啪」地出现
                AnimatedGroup {
                AppRow(
                    app = app,
                    showPackageName = showPackageName,
                    checked = app.packageName in selected,
                    onToggle = { toggle(app.packageName) },
                    onFreeze = { doFreeze(app) },
                    onUninstall = { pendingUninstall = app.packageName },
                    onClearData = { pendingClear = app.packageName },
                    onMore = { sheetApp = app },
                )
                }
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
            val actions = buildList {
                add(
                    ActionSheetEntry(
                        text = if (sheet.enabled) "冻结应用" else "解冻应用",
                        subtitle = "停用 / 恢复运行",
                    ) {
                        doFreeze(sheet)
                    },
                )
                add(
                    ActionSheetEntry(
                        text = "清除应用数据",
                        subtitle = "清空本地数据",
                    ) {
                        pendingClear = sheet.packageName
                    },
                )
                add(
                    ActionSheetEntry(
                        text = "提取 APK",
                        subtitle = "导出并计算校验值",
                    ) {
                        doExtract(sheet.packageName)
                    },
                )
                add(
                    ActionSheetEntry(
                        text = if (sheet.uninstallBlocked) "解除防卸载保护" else "开启防卸载保护",
                        subtitle = "重启后依然有效",
                    ) {
                        doToggleProtection(sheet)
                    },
                )
                add(
                    ActionSheetEntry(text = "打开应用详情", subtitle = "系统设置") {
                        DeviceInfo.openAppInfo(context, sheet.packageName)
                    },
                )
                // 作业帮设备上多一条真·静默卸载（InstallerX 的 Birchua 同款）
                if (ZybosApi.isAvailable(context)) {
                    add(
                        ActionSheetEntry(
                            text = "静默卸载（系统服务）",
                            subtitle = "作业帮 ZybosSysApiManager，无需确认",
                        ) {
                            scope.launch {
                                val r = ZybosApi.silentUninstall(context, sheet.packageName)
                                message = r.message
                                refresh()
                            }
                        },
                    )
                }
                add(
                    ActionSheetEntry(
                        text = "卸载应用",
                        subtitle = "不可撤销",
                        danger = true,
                    ) {
                        pendingUninstall = sheet.packageName
                    },
                )
            }
            ActionSheet(
                title = "${sheet.label}\n${sheet.packageName}",
                actions = actions,
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

/**
 * 本地安装包库。
 *
 * 对应 InstallerX 首页：**把下载目录里的 APK 列出来，标注安装状态，点一下就装**。
 *
 * 顶部还挂着 InstallerX 的安装选项（允许降级 / 允许测试包 / 多用户 /
 * 自动删除 / 声明安装者），这些开关会真的改写 `pm install` 的参数。
 */
@Composable
private fun ApkLibrarySection(channels: ChannelManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installer = remember { Installer(context, channels) }
    val listState = rememberLazyListState()

    var items by remember { mutableStateOf<List<ApkLibrary.Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 安装选项与设置页共用同一份数据源
    val app = context.applicationContext as? App
    val optionsFlow = app?.settings?.installOptions ?: remember { flowOf(InstallOptions.DEFAULT) }
    val options by optionsFlow.collectAsStateWithLifecycle(initialValue = InstallOptions.DEFAULT)

    LaunchedEffect(Unit) {
        loading = true
        items = ApkLibrary.scan(context)
        loading = false
    }

    val filtered = remember(items, query) {
        if (query.isBlank()) items
        else items.filter {
            it.label.contains(query, true) ||
                it.packageName.contains(query, true) ||
                it.file.name.contains(query, true)
        }
    }

    // 按状态分组，和 InstallerX 的分组列表观感一致
    val grouped = remember(filtered) {
        listOf(
            ApkLibrary.Item.State.UPDATABLE to filtered.filter { it.state == ApkLibrary.Item.State.UPDATABLE },
            ApkLibrary.Item.State.NEW to filtered.filter { it.state == ApkLibrary.Item.State.NEW },
            ApkLibrary.Item.State.DOWNGRADE to filtered.filter { it.state == ApkLibrary.Item.State.DOWNGRADE },
            ApkLibrary.Item.State.INSTALLED to filtered.filter { it.state == ApkLibrary.Item.State.INSTALLED },
        ).filter { it.second.isNotEmpty() }
    }

    fun doInstall(item: ApkLibrary.Item) {
        busy = true
        scope.launch {
            val r = installer.install(item.file)
            message = "${r.via}：${r.message}"
            busy = false
            items = ApkLibrary.scan(context)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            item(key = "header") {
                PageHeader(
                    title = "安装包",
                    kicker = if (loading) "正在扫描下载目录…" else "共 ${filtered.size} 个安装包",
                )
            }

            item(key = "search") {
                TextInput(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索应用名 / 包名 / 文件名",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Metrics.pad, vertical = 4.dp),
                    singleLine = true,
                )
            }

            // 安装选项（InstallerX 的 config_*）
            item(key = "options") {
                SectionCard(title = "安装选项", subtitle = "直接改写 pm install 的参数") {
                    ToggleRow(
                        title = "允许降级",
                        subtitle = "允许安装低版本的安装包",
                        checked = options.allowDowngrade,
                        onCheckedChange = {
                            app?.settings?.setInstallOptions(options.copy(allowDowngrade = it))
                        },
                    )
                    ToggleRow(
                        title = "允许测试包",
                        subtitle = "允许安装带 TestOnly 标签的安装包",
                        checked = options.allowTestOnly,
                        onCheckedChange = {
                            app?.settings?.setInstallOptions(options.copy(allowTestOnly = it))
                        },
                    )
                    ToggleRow(
                        title = "多用户安装",
                        subtitle = "为所有用户安装（--user all）",
                        checked = options.allUsers,
                        onCheckedChange = {
                            app?.settings?.setInstallOptions(options.copy(allUsers = it))
                        },
                    )
                    ToggleRow(
                        title = "安装后自动删除",
                        subtitle = "安装成功后删除安装包",
                        checked = options.autoDelete,
                        onCheckedChange = {
                            app?.settings?.setInstallOptions(options.copy(autoDelete = it))
                        },
                    )
                    TextInput(
                        value = options.installerPackage,
                        onValueChange = {
                            app?.settings?.setInstallOptions(options.copy(installerPackage = it.trim()))
                        },
                        label = "声明安装者（包名，可留空）",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Metrics.inset, vertical = 4.dp),
                        singleLine = true,
                    )
                    InfoRow("实际命令", "pm install${options.pmFlags()} <包>")
                }
            }

            if (loading) {
                item(key = "loading") { EmptyHint("正在扫描下载目录…") }
            } else if (filtered.isEmpty()) {
                item(key = "empty") { EmptyHint("下载目录里没有找到 APK") }
            }

            grouped.forEach { (state, list) ->
                item(key = "group-${state.name}") {
                    Text(
                        text = when (state) {
                            ApkLibrary.Item.State.UPDATABLE -> "可更新"
                            ApkLibrary.Item.State.NEW -> "未安装"
                            ApkLibrary.Item.State.DOWNGRADE -> "需先卸载"
                            ApkLibrary.Item.State.INSTALLED -> "已安装"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(
                            start = Metrics.pad + 4.dp,
                            end = Metrics.pad,
                            top = 12.dp,
                            bottom = 4.dp,
                        ),
                    )
                }
                items(list, key = { it.file.absolutePath }) { item ->
                    AnimatedGroup {
                        ApkLibraryRow(
                            item = item,
                            busy = busy,
                            onInstall = { doInstall(item) },
                        )
                    }
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

/** 安装包列表的一行。 */
@Composable
private fun ApkLibraryRow(
    item: ApkLibrary.Item,
    busy: Boolean,
    onInstall: () -> Unit,
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
                    .padding(horizontal = Metrics.inset, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.label,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${item.packageName} · v${item.versionName} · ${DeviceInfo.formatSize(item.sizeBytes)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.file.absolutePath,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Pill(
                    text = when (item.state) {
                        ApkLibrary.Item.State.UPDATABLE -> "可更新"
                        ApkLibrary.Item.State.NEW -> "未安装"
                        ApkLibrary.Item.State.DOWNGRADE -> "需卸载"
                        ApkLibrary.Item.State.INSTALLED -> "已安装"
                    },
                    color = when (item.state) {
                        ApkLibrary.Item.State.UPDATABLE -> c.info
                        ApkLibrary.Item.State.NEW -> c.neutral
                        ApkLibrary.Item.State.DOWNGRADE -> c.warn
                        ApkLibrary.Item.State.INSTALLED -> c.ok
                    },
                    onColor = when (item.state) {
                        ApkLibrary.Item.State.UPDATABLE -> c.onInfo
                        ApkLibrary.Item.State.NEW -> c.onNeutral
                        ApkLibrary.Item.State.DOWNGRADE -> c.onWarn
                        ApkLibrary.Item.State.INSTALLED -> c.onOk
                    },
                )
            }
            Row(
            modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.inset, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionButton(
                    text = if (busy) "安装中…" else when (item.state) {
                        ApkLibrary.Item.State.UPDATABLE -> "更新"
                        ApkLibrary.Item.State.INSTALLED -> "重装"
                        else -> "安装"
                    },
                    onClick = onInstall,
                )
            }
        }
    }
}

/** 行级操作只在点击时执行，不参与重组比较。 */
private val Noop: () -> Unit = {}

@Composable
private fun AppRow(
    app: AppEntry,
    showPackageName: Boolean = true,
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
                        text = buildString {
                            if (showPackageName) {
                                append(app.packageName).append(" · ")
                            }
                            append("v").append(app.versionName).append(" · ")
                            append(DeviceInfo.formatSize(app.sizeBytes))
                        },
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
