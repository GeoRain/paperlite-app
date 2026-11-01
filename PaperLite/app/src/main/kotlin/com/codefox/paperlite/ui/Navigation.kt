package com.codefox.paperlite.ui

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Promotions
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.Tune

/**
 * 侧边栏目的地：**一个功能一个页面**。
 *
 * Lite 版只保留真正能用的能力：
 * - 概览：通道状态 + 服务器开关
 * - 应用：已安装应用列表、打开详情
 * - 安装：选包 → 按通道安装（内置文件选择器）
 * - 卸载：按包名卸载 / 冻结 / 清数据
 * - 设备：系统信息与进程
 * - 终端：无线调试配对后执行任意命令
 * - 配对：无线调试配对（终端的前置条件，单独成页）
 * - 设置：外观、服务器、通道参数、日志
 * - 关于：版本、风险提示、开发者名片
 *
 * 已删除的能力（要么在本机权限下根本跑不通，要么已被终端命令覆盖）：
 * - 网络代理：写系统代理需要高权限，已由终端里的 `settings put global http_proxy` 覆盖
 * - 设备所有者防卸载：需要设备所有者资格，普通安装无法激活
 * - 无障碍自动填配对码：改由「配对」页的识别按钮 + 手动输入完成
 */
enum class Destination(val label: String) {
    Home("概览"),
    Apps("应用"),
    Install("安装"),
    Uninstall("卸载"),
    Device("设备"),
    Terminal("终端"),
    Pairing("配对"),
    Notice("公告"),
    Settings("设置"),
    About("关于"),
    ;

    companion object {
        fun of(raw: String?): Destination =
            entries.firstOrNull { it.name == raw } ?: Home
    }
}

/** 侧边栏条目：目的地 + 显示名 + 图标。 */
data class RailEntry(
    val destination: Destination,
    val label: String,
    val icon: ImageVector,
)

/**
 * 侧边栏条目列表。
 *
 * 用 `remember` 在调用侧缓存，避免每次重组都重新构造 ImageVector 列表
 * ——这是 Miuix 图标在低端 Pad 上卡顿的主因之一。
 */
fun buildRailEntries(): List<RailEntry> = listOf(
    RailEntry(Destination.Home, "概览", MiuixIcons.Home),
    RailEntry(Destination.Apps, "应用", MiuixIcons.Store),
    RailEntry(Destination.Install, "安装", MiuixIcons.Import),
    RailEntry(Destination.Uninstall, "卸载", MiuixIcons.Delete),
    RailEntry(Destination.Device, "设备", MiuixIcons.GridView),
    RailEntry(Destination.Terminal, "终端", MiuixIcons.Tune),
    RailEntry(Destination.Pairing, "配对", MiuixIcons.Scan),
    RailEntry(Destination.Notice, "公告", MiuixIcons.Promotions),
    RailEntry(Destination.Settings, "设置", MiuixIcons.Settings),
    RailEntry(Destination.About, "关于", MiuixIcons.Info),
)
