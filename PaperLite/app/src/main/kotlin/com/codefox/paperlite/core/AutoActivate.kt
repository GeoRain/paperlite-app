package com.codefox.paperlite.core

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * root 拿到之后的自动激活。
 *
 * 拿到 root 只是「有权限」，但 PaperLite 真正值钱的能力
 * （静默安装、单独应用防卸载、临时停用）都挂在**设备所有者**身份上。
 * 所以提权成功后要顺手把本应用提上去。
 *
 * 两条路：
 * 1. **root 直接授予**：用 `dpm set-device-owner <包名>`，
 *    这是 adb 授予 device owner 的正规命令，root 下不需要用户点确认。
 *    前提是设备未初始化过、且没有别的 owner。
 * 2. **系统弹窗**：上面失败时，退回拉起设备管理员激活页，
 *    用户点一下「激活」即可——这一步系统强制要求人工确认，绕不过去。
 *
 * 激活成功后再把已装应用全部加上防卸载保护。
 */
object AutoActivate {

    /** 激活结果。 */
    data class Result(
        val owner: Boolean,
        /** 是否需要用户在系统弹窗里点一下。 */
        val needsUserConfirm: Boolean,
        val message: String,
    )

    /**
     * 提权成功后调用：把本应用提为设备所有者。
     *
     * @param channels 用来跑 `dpm set-device-owner`
     */
    suspend fun activate(context: Context, channels: ChannelManager): Result =
        withContext(Dispatchers.IO) {
            if (DeviceOwner.isSelf(context)) {
                return@withContext Result(true, false, "本应用已经是设备所有者")
            }

            val pkg = context.packageName
            val r = runCatching {
                channels.execAsShell(
                    "dpm set-device-owner '$pkg'",
                    timeoutMs = 45_000L,
                )
            }.getOrNull()

            val out = r?.output?.trim().orEmpty()
            val err = r?.error.orEmpty()
            val ok = r?.ok == true && !out.contains("Error", true) && !err.contains("Error", true)

            if (ok || DeviceOwner.isSelf(context)) {
                val n = DeviceOwner.setUninstallBlockedAll(context, true)
                DeviceOwner.invalidateCache()
                return@withContext Result(
                    true,
                    false,
                    "已激活为设备所有者，并为 $n 个应用开启防卸载保护",
                )
            }

            // root 路线没走通：退回系统弹窗
            val launched = runCatching {
                context.startActivity(DeviceOwner.requestAdmin(context))
                true
            }.getOrDefault(false)

            if (launched) {
                Result(
                    false,
                    true,
                    "设备所有者还不能直接授予，已拉起系统激活页，点一下「激活」即可" +
                        (if (out.isNotBlank()) "（$out）" else ""),
                )
            } else {
                Result(
                    false,
                    false,
                    "自动激活失败${if (out.isNotBlank()) "：$out" else ""}",
                )
            }
        }

    /**
     * 已激活为设备管理员（还没成为 owner）时，直接申请成为 owner。
     *
     * 设备所有者必须在设备未初始化时授予，所以这一步只在
     * 「从未初始化过」的机器上有意义。
     */
    suspend fun requestOwner(context: Context, channels: ChannelManager): Result =
        withContext(Dispatchers.IO) {
            if (DeviceOwner.isSelf(context)) {
                return@withContext Result(true, false, "已经是设备所有者")
            }
            activate(context, channels)
        }

    /** 拉起系统激活页。 */
    fun launchAdmin(context: Context): Boolean = runCatching {
        context.startActivity(
            DeviceOwner.requestAdmin(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
