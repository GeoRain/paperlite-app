package com.codefox.paperlite.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃落盘。
 *
 * 用户反馈的「用一半停止运行」在开发机上复现不了，所以先保证
 * 下一次闪退一定留下证据：未捕获异常写到 `files/crash/last.txt`，
 * 时间、线程、完整堆栈都在里面，重启后可在「设置 → 运行日志」里看到摘要。
 *
 * 这里只记录，不吞异常：记完继续交给系统默认处理器，行为不变。
 */
object CrashGuard {

    private const val DIR = "crash"
    private const val FILE = "last.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            runCatching { previous?.uncaughtException(thread, error) }
        }
        runCatching { reportPrevious(app) }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("时间：$stamp")
            appendLine("线程：${thread.name}")
            appendLine("异常：${error::class.java.name}")
            appendLine("信息：${error.message}")
            appendLine()
            appendLine(error.stackTraceToString())
        }
        File(dir, FILE).writeText(text)
    }

    /** 上次闪退的摘要，已读则清掉，避免每次启动都提示。 */
    private fun reportPrevious(context: Context): String? {
        val f = File(File(context.filesDir, DIR), FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        if (text.isNullOrBlank()) return null
        val head = text.lineSequence().take(4).joinToString(" | ")
        (context.applicationContext as? com.codefox.paperlite.App)
            ?.settings
            ?.appendLog("上次异常退出：$head")
        return head
    }

    /** 供设置页展示完整堆栈。 */
    fun readLast(context: Context): String? {
        val f = File(File(context.filesDir, DIR), FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }
}
