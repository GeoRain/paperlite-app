package com.codefox.paperlite.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内背景壁纸仓库。
 *
 * 只管应用**自己**的背景，不动系统壁纸：
 * - 默认图随包放在 `assets/wallpaper/default.webp`；
 * - 用户选的图复制一份到应用私有目录（不能直接存 Uri——
 *   系统给的临时读权限在进程重启后就没了，重启会白屏）；
 * - 取色也从这张图来，保证背景与主题色永远一致。
 */
object WallpaperStore {

    private const val ASSET = "wallpaper/default.webp"
    private const val CUSTOM = "wallpaper/custom.png"

    /** 自定义图在私有目录里的位置。 */
    fun customFile(context: Context): File = File(context.filesDir, CUSTOM)

    /** 默认图是否随包可用。 */
    fun hasDefault(context: Context): Boolean = runCatching {
        context.assets.open(ASSET).use { true }
    }.getOrDefault(false)

    /**
     * 读当前壁纸位图。
     *
     * @param path 自定义图路径（来自 [com.codefox.paperlite.data.Settings.wallpaperPath]），
     *   null 表示用内置默认图。
     */
    suspend fun load(context: Context, path: String?): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (path != null) {
                val f = File(path)
                if (f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
            } else {
                context.assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
    }

    /** 转成 Compose 可直接画的 ImageBitmap。 */
    suspend fun loadImageBitmap(context: Context, path: String?): ImageBitmap? =
        load(context, path)?.asImageBitmap()

    /**
     * 把用户在相册里选的图存成私有 PNG。
     *
     * 走 PNG 是为了规避各种 HEIC / WebP 动图在旧解码器上的差异；
     * 顺便缩到最长边 1920，避免 4K 图常驻内存把应用拖垮。
     */
    suspend fun saveFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext null
            val max = raw.width.coerceAtLeast(raw.height)
            val scaled = if (max > 1920) {
                val k = 1920f / max
                Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * k).toInt().coerceAtLeast(1),
                    (raw.height * k).toInt().coerceAtLeast(1),
                    true,
                )
            } else raw
            val out = customFile(context)
            out.parentFile?.mkdirs()
            out.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 92, it) }
            out.absolutePath
        }.getOrNull()
    }
}
