package com.codefox.paperlite.ui

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.Tune

/**
 * 侧边栏目的地：**六项，按权限来源分开**。
 *
 * 早期版本有 10 项，图标多、每一项都要重建 ImageVector，低端 Pad 上
 * 侧边栏本身就是个持续的开销；更重要的是 10 项里一半是「同一件事的另一半」
 * （安装 / 卸载、设备 / 配对），用户反而找不到。
 *
 * 现在按「做什么」分成六类，具体功能收进页内分段标签：
 * - 概览：通道状态、一键探测、常用入口
 * - 应用：应用管理（已安装 / 安装 / 卸载）
 * - 超级用户：root 工具（对齐 SukiSU 的管理面）+ 通道状态 + 设备所有者
 * - Shizuku：Shizuku 服务检测、官方启动流程、以 Shizuku 身份执行命令
 * - 命令行：任意 shell 命令与常用命令（对齐 Termux 的使用习惯）
 * - 设置：外观、通道参数、日志、系统信息、公告、关于
 *
 * root 与 Shizuku 之所以分成两页：它们是**两种不同来源的高权限身份**，
 * 混在一起用户分不清「这次的权限到底是 root 给的还是 Shizuku 给的」。
 */
enum class Destination(val label: String) {
    Home("概览"),
    Apps("应用"),
    Superuser("超级用户"),
    Shizuku("Shizuku"),
    Accessibility("无障碍"),
    Terminal("命令行"),
    Settings("设置"),
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
    RailEntry(Destination.Superuser, "超级用户", MiuixIcons.Lock),
    RailEntry(Destination.Shizuku, "Shizuku", MiuixIcons.Link),
    RailEntry(Destination.Accessibility, "无障碍", MiuixIcons.GridView),
    RailEntry(Destination.Terminal, "命令行", MiuixIcons.Tune),
    RailEntry(Destination.Settings, "设置", MiuixIcons.Settings),
)
