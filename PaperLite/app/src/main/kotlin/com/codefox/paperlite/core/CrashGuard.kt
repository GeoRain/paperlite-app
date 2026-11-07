package com.codefox.paperlite.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃落盘。
 *
 * 用户反馈的「用一半停止运行」「打开就崩」在开发机上复现不了，所以先保证
 * 下一次闪退一定留下证据，且证据要在**不进应用**的情况下也能拿到：
 *
 * 1. 应用私有目录 `files/crash/last.txt`（一定可写，adb 可 pull）；
 * 2. 系统「下载」目录 `PaperLite-crash-<时间>.txt`（用户在文件管理器里直接可见，
 *    崩溃发生时就写进去了，不需要先打开应用）；
 * 3. 运行日志里追加一行摘要（去「设置 → 运行日志」即可看到）。
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
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("时间：$stamp")
            appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL} (Android SDK ${Build.VERSION.SDK_INT})")
            appendLine("线程：${thread.name}")
            appendLine("异常：${error::class.java.name}")
            appendLine("信息：${error.message}")
            appendLine()
            appendLine(error.stackTraceToString())
        }
        // 1) 应用私有目录，一定可写
        runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            File(dir, FILE).writeText(text)
        }
        // 2) 系统「下载」目录：用户在文件管理器里直接能看到，无需 adb 进应用
        writeToDownloads(context, text)
        // 3) 运行日志里追加一行摘要
        reportToLog(context, error)
    }

    /** 用 MediaStore 落到系统下载目录；任何一步失败都静默放弃，不影响主流程。 */
    private fun writeToDownloads(context: Context, text: String) {
        runCatching {
            val name =
                "PaperLite-crash-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                },
            ) ?: return
            context.contentResolver.openOutputStream(uri)
                ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    private fun reportToLog(context: Context, error: Throwable) {
        runCatching {
            (context.applicationContext as? com.codefox.paperlite.App)
                ?.settings
                ?.appendLog("上次异常退出：${error::class.java.simpleName} | ${error.message ?: ""}")
        }
    }

    /** 上次闪退的摘要，已读则清掉，避免每次启动都提示。 */
    private fun reportPrevious(context: Context): String? {
        val f = File(File(context.filesDir, DIR), FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        if (text.isNullOrBlank()) return null
        val head = text.lineSequence().take(5).joinToString(" | ")
        // 保留 last.txt 全文（不删除），只把摘要写进运行日志方便查看
        reportToLog(context, RuntimeException(head))
        return head
    }

    /** 供设置页展示完整堆栈。 */
    fun readLast(context: Context): String? {
        val f = File(File(context.filesDir, DIR), FILE)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }
}
