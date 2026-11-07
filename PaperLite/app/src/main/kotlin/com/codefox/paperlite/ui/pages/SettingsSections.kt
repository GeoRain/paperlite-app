package com.codefox.paperlite.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codefox.paperlite.core.AutoRoot
import com.codefox.paperlite.core.BundledBinaries
import com.codefox.paperlite.core.DirtyFrag
import com.codefox.paperlite.data.InstallAuthorizer
import com.codefox.paperlite.data.InstallOptions
import com.codefox.paperlite.data.Settings
import com.codefox.paperlite.ui.components.InfoRow
import com.codefox.paperlite.ui.components.Metrics
import com.codefox.paperlite.ui.components.PageHeader
import com.codefox.paperlite.ui.components.SectionCard
import com.codefox.paperlite.ui.components.SegmentedTabs
import com.codefox.paperlite.ui.components.TextInput
import com.codefox.paperlite.ui.components.ToggleRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 安装设置：授权器 + 安装选项（InstallerX 的 config 全部收在这里）。
 */
@Composable
fun InstallSettingsSection(settings: Settings) {
    val listState = rememberLazyListState()
    val authorizer by settings.installAuthorizer.collectAsStateWithLifecycle()
    val options by settings.installOptions.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            PageHeader(title = "安装设置", kicker = "授权器与安装选项")
        }
        item(key = "authorizer") {
            SectionCard(title = "安装授权器", subtitle = "安装以谁的身份执行") {
                SegmentedTabs(
                    options = InstallAuthorizer.entries,
                    selected = authorizer,
                    label = { it.title },
                    onSelect = { settings.setInstallAuthorizer(it) },
                    modifier = Modifier.padding(horizontal = 0.dp, vertical = 2.dp),
                )
                InfoRow("当前", "${authorizer.title} · ${authorizer.desc}")
            }
        }
        item(key = "options") {
            SectionCard(title = "安装选项", subtitle = "直接改写 pm install 的参数") {
                ToggleRow(
                    title = "允许降级",
                    subtitle = "允许安装低版本的安装包（-d）",
                    checked = options.allowDowngrade,
                    onCheckedChange = {
                        settings.setInstallOptions(options.copy(allowDowngrade = it))
                    },
                )
                ToggleRow(
                    title = "允许测试包",
                    subtitle = "允许安装带 TestOnly 标签的安装包（-t）",
                    checked = options.allowTestOnly,
                    onCheckedChange = {
                        settings.setInstallOptions(options.copy(allowTestOnly = it))
                    },
                )
                ToggleRow(
                    title = "多用户安装",
                    subtitle = "为所有用户安装（--user all）",
                    checked = options.allUsers,
                    onCheckedChange = {
                        settings.setInstallOptions(options.copy(allUsers = it))
                    },
                )
                ToggleRow(
                    title = "安装后自动删除",
                    subtitle = "安装成功后删除安装包",
                    checked = options.autoDelete,
                    onCheckedChange = {
                        settings.setInstallOptions(options.copy(autoDelete = it))
                    },
                )
                TextInput(
                    value = options.installerPackage,
                    onValueChange = {
                        settings.setInstallOptions(options.copy(installerPackage = it.trim()))
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
    }
}

/**
 * 提权设置：自动提权与 DirtyFrag 开关（抄「无障碍管理器」的自动提权模型）。
 */
@Composable
fun RootSettingsSection(settings: Settings) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var unlock by remember { mutableStateOf(AutoRoot.autoRootOnUnlock(context)) }
    var boot by remember { mutableStateOf(AutoRoot.autoRootOnBoot(context)) }
    var keepAlive by remember { mutableStateOf(AutoRoot.keepAliveEnabled(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "header") {
            PageHeader(title = "提权设置", kicker = "自动获取 root")
        }
        item(key = "auto") {
            SectionCard(title = "自动提权", subtitle = "解锁 / 开机自动跑漏洞利用") {
                ToggleRow(
                    title = "解锁后自动获取 root",
                    subtitle = "设备一解锁就自动尝试提权",
                    checked = unlock,
                    onCheckedChange = {
                        unlock = it
                        AutoRoot.setAutoRootOnUnlock(context, it)
                    },
                )
                ToggleRow(
                    title = "开机后自动获取 root",
                    subtitle = "重启后开机自启时自动提权",
                    checked = boot,
                    onCheckedChange = {
                        boot = it
                        AutoRoot.setAutoRootOnBoot(context, it)
                    },
                )
                ToggleRow(
                    title = "无障碍服务保活",
                    subtitle = "避免解锁事件收不到",
                    checked = keepAlive,
                    onCheckedChange = {
                        keepAlive = it
                        AutoRoot.setKeepAlive(context, it)
                    },
                )
            }
        }
        item(key = "state") {
            SectionCard(title = "当前状态") {
                InfoRow(
                    "无障碍服务",
                    if (AutoRoot.isAccessibilityEnabled(context)) "已开启" else "未开启",
                )
                InfoRow(
                    "随包组件",
                    BundledBinaries.integrity(context).lineSequence().firstOrNull().orEmpty(),
                )
                InfoRow("一键 Root", DirtyFrag.supportInfo(context))
            }
        }
    }
}
