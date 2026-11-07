package com.codefox.paperlite.core.channel

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 截屏识别无线调试配对码。
 *
 * 做法：MediaProjection 起一个 VirtualDisplay，周期性抓帧，
 * 在配对对话框区域做「数字字形行扫描」——把每个数字切成 7 段网格判断点亮情况，
 * 拼出 6 位数字。不做整屏 OCR，避免引入几十 MB 的模型。
 *
 * 隐私：只在用户点击「自动识别」后的 N 秒内抓帧，识别到或超时立刻停止，
 * 截图不落盘、不上传，内存里只保留当前一帧。
 */
class PairingCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("pair-capture").also { it.start() }
        handler = Handler(thread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Android 13+ 的 getParcelableExtra(String) 会返回 null，必须走带 class 的重载
                val data = intent.parcelableExtra<Intent>(EXTRA_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                if (data == null || code == Activity.RESULT_CANCELED) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Android 14+ 的两条硬约束，顺序不能反：
                // 1. `startForegroundService()` 之后必须在 5 秒内调用 `startForeground()`，
                //    否则系统抛 ForegroundServiceDidNotStartInTimeException 直接杀进程；
                // 2. 以 mediaProjection 类型 `startForeground()` 之前，
                //    必须已经拿到 MediaProjection 并注册好回调，
                //    否则抛 IllegalStateException。
                //
                // 所以拆成两步：先 startForegroundInternal() 满足 (1)，
                // 再 beginProjection() 满足 (2)。
                // 之前把 beginProjection 放在前面，createVirtualDisplay
                // 在没有前台服务的情况下执行，Android 14+ 直接判定非法，
                // 服务没能走到 startForeground → 进程被系统杀掉。
                startForegroundInternal()
                if (!beginProjection(code, data)) {
                    // 拿不到投影也要收尾：stopForeground + stopSelf，
                    // 否则系统会一直等前台服务，超时后同样杀进程。
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    /** 兼容新旧 SDK 的 Parcelable 读取。 */
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(
        key: String,
    ): T? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

    private fun startForegroundInternal() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "配对码识别",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            nm?.createNotificationChannel(ch)
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在识别配对码")
            .setContentText("只读取当前屏幕上的配对码，识别完成即停止")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 第一步先用「无类型」起前台——此时还没有 MediaProjection，
            // 声明 mediaProjection 类型会被系统拒绝。
            // 拿到投影后（见 upgradeToMediaProjectionType）再升级类型。
            startForeground(NOTIF_ID, notif)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /**
     * 升级前台服务类型为 mediaProjection。
     *
     * 必须等 MediaProjection 拿到、回调注册好之后才能调，
     * 否则 Android 14+ 抛 `IllegalStateException`。
     */
    private fun upgradeToMediaProjectionType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val nm = getSystemService(NotificationManager::class.java)
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在识别配对码")
            .setContentText("只读取当前屏幕上的配对码，识别完成即停止")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        runCatching {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
    }

    private fun beginProjection(resultCode: Int, data: Intent): Boolean {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (mp == null) {
            finish("屏幕读取授权失败")
            return false
        }
        projection = mp
        startedAt = System.currentTimeMillis()

        // Android 14+ 强制：MediaProjection 必须先注册回调，
        // 否则 startForeground(mediaProjection) 会抛 IllegalStateException。
        runCatching {
            mp.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() {
                    finish("屏幕读取已结束")
                }
            }, handler)
        }

        // 投影已就绪、回调已注册 → 现在可以把前台服务类型升级为 mediaProjection
        upgradeToMediaProjectionType()

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "pair-capture",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler,
        )

        reader!!.setOnImageAvailableListener({ r ->
            if (System.currentTimeMillis() - startedAt > CAPTURE_WINDOW_MS) {
                finish("识别超时，请手动输入配对码")
                return@setOnImageAvailableListener
            }
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                if (pixelStride <= 0 || rowStride < pixelStride) return@setOnImageAvailableListener
                val buf = plane.buffer

                /**
                 * ImageReader 的每一行都带 padding，真实一行的像素数是 rowStride/pixelStride，
                 * 而 [Bitmap.createBitmap] 的 width 必须与 buffer 里一行的 byte 数严格对应，
                 * 否则 [Bitmap.copyPixelsFromBuffer] 会越界读——这是 native crash，
                 * java 层 try/catch 完全拦不住，进程直接消失。
                 *
                 * 所以这里按 buffer 实际剩余容量反推能安全拷贝的宽度，
                 * 并且宽度必须是 4 的整数倍（ARGB_8888 每像素 4 字节）。
                 */
                val stridePx = rowStride / pixelStride
                val h = image.height
                if (stridePx <= 0 || h <= 0) return@setOnImageAvailableListener

                // 可用的最大像素宽度：容量 / (每像素字节 × 行数)
                val maxByCapacity = buf.capacity() / (4 * h)
                // Bitmap 宽度必须向下取到 4 的整数倍，copyPixelsFromBuffer 才安全
                val w = (minOf(stridePx, maxByCapacity) / 4) * 4
                if (w <= 0) return@setOnImageAvailableListener
                if (buf.capacity() < w * h * 4) return@setOnImageAvailableListener

                buf.rewind()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                val code = runCatching { OcrDigits.scanForPairingCode(bmp) }.getOrNull()
                bmp.recycle()
                if (code != null) {
                    finish(null, code)
                }
            } catch (_: Throwable) {
                // 单帧失败不影响后续帧
            } finally {
                runCatching { image.close() }
            }
        }, handler)
        return true
    }

    private fun finish(error: String?, code: String? = null) {
        if (code != null) {
            _result.value = CaptureResult(code, null)
            // 顺手投递给无障碍服务自动填
            PairingAccessibilityService.arm(code)
        } else if (error != null) {
            _result.value = CaptureResult(null, error)
        }
        stopCapture()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun stopCapture() {
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        reader = null
        projection = null
    }

    override fun onDestroy() {
        stopCapture()
        thread?.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.codefox.paperlite.START_CAPTURE"
        const val ACTION_STOP = "com.codefox.paperlite.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "result_data"
        private const val CHANNEL_ID = "pair_capture"
        private const val NOTIF_ID = 0x7102
        private const val CAPTURE_WINDOW_MS = 20_000L

        data class CaptureResult(val code: String?, val error: String?)

        private val _result = MutableStateFlow<CaptureResult?>(null)
        val result: StateFlow<CaptureResult?> = _result.asStateFlow()

        fun consume() {
            _result.value = null
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, PairingCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PairingCaptureService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}

/**
 * 纯数字识别。
 *
 * 思路：配对码是 6 个等宽的大号数字，在对话框里位于同一基线上。
 * 先找出屏幕下方区域里连续的高对比度数字行，切成 6 等份，
 * 再用「7 段式」网格（上/中/下横 + 左右上/下竖）判断每段是否点亮，
 * 映射到 0-9。
 */
internal object OcrDigits {

    /** 7 段编码：bit0=a(上横) bit1=b(右上) bit2=c(右下) bit3=d(下横) bit4=e(左下) bit5=f(左上) bit6=g(中横) */
    private val SEGMENTS = mapOf(
        0b0111111 to '0',
        0b0000110 to '1',
        0b1011011 to '2',
        0b1001111 to '3',
        0b1100110 to '4',
        0b1101101 to '5',
        0b1111101 to '6',
        0b0000111 to '7',
        0b1111111 to '8',
        0b1101111 to '9',
    )

    /**
     * 在整屏位图中寻找配对码。
     *
     * 性能要点：整屏 2000×1200 时逐像素 `getPixel` 等于 240 万次 JNI 调用，
     * 单次可能要几十秒，直接把后台线程占满、ANR。这里先用 `getPixels`
     * 一次拷进 IntArray，之后全部在数组上算。
     *
     * @return 6 位数字串，未识别到返回 null
     */
    fun scanForPairingCode(bmp: Bitmap): String? {
        val w = bmp.width
        val h = bmp.height
        if (w < 200 || h < 200) return null

        // 一次性取像素；整屏 ARGB 最多约 10MB，识别窗口很短，可以接受
        val pixels = runCatching { IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) } }
            .getOrNull() ?: return null

        // 配对对话框通常在屏幕中下部，取 30%~85% 高度区间
        val top = (h * 0.30f).toInt()
        val bottom = (h * 0.85f).toInt().coerceAtMost(h)
        if (bottom <= top) return null

        // 逐行统计「亮像素」分布，找出文字行
        val rowInk = IntArray(bottom - top)
        val stepX = (w / 120).coerceAtLeast(1)
        for (y in top until bottom) {
            var count = 0
            val rowBase = y * w
            var x = 0
            while (x < w) {
                if (luminance(pixels[rowBase + x]) > 200) count++
                x += stepX
            }
            rowInk[y - top] = count
        }

        // 找连续的高墨水行块
        val blocks = mutableListOf<IntRange>()
        var startIdx = -1
        val bg = rowInk.average().coerceAtLeast(1.0)
        val inked = rowInk.map { it > bg * 1.6 }
        for (i in inked.indices) {
            if (inked[i] && startIdx < 0) startIdx = i
            if ((!inked[i] || i == inked.lastIndex) && startIdx >= 0) {
                val end = if (inked[i]) i else i - 1
                if (end - startIdx >= (h * 0.018f).toInt()) blocks += startIdx..end
                startIdx = -1
            }
        }
        if (blocks.isEmpty()) return null

        // 最宽的文字块最可能是配对码
        val band = blocks.maxByOrNull { it.last - it.first } ?: return null
        val bandTop = top + band.first
        val bandBottom = (top + band.last).coerceAtMost(h - 1)
        if (bandBottom - bandTop < 12) return null

        // 在该行内找文字的横向范围
        var minX = w
        var maxX = 0
        for (y in bandTop..bandBottom) {
            val rowBase = y * w
            for (x in 0 until w) {
                if (luminance(pixels[rowBase + x]) > 200) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
        }
        if (maxX <= minX) return null
        val spanX = maxX - minX
        if (spanX < 60) return null

        // 按 6 等分切数字
        val digitW = spanX / 6
        if (digitW < 8) return null

        val sb = StringBuilder()
        for (i in 0 until 6) {
            val x0 = minX + i * digitW
            val x1 = x0 + digitW
            val seg = readDigit(pixels, w, h, x0, bandTop, x1, bandBottom)
            sb.append(SEGMENTS[seg] ?: return null)
        }
        return sb.toString()
    }

    private fun readDigit(
        pixels: IntArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Int {
        val padX = ((x1 - x0) * 0.12f).toInt()
        val padY = ((y1 - y0) * 0.10f).toInt()
        val l = (x0 + padX).coerceIn(0, width - 1)
        val r = (x1 - padX).coerceIn(0, width - 1)
        val t = (y0 + padY).coerceIn(0, height - 1)
        val b = (y1 - padY).coerceIn(0, height - 1)
        if (r - l < 4 || b - t < 4) return -1

        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val qy1 = t + (b - t) / 3
        val qy2 = t + (b - t) * 2 / 3

        fun lit(x: Int, y: Int): Boolean {
            // 3x3 邻域平均，抗锯齿
            var sum = 0
            for (dx in -1..1) for (dy in -1..1) {
                val px = (x + dx).coerceIn(0, width - 1)
                val py = (y + dy).coerceIn(0, height - 1)
                if (luminance(pixels[py * width + px]) > 190) sum++
            }
            return sum * 2 > 9
        }

        var seg = 0
        if (lit(cx, t + 1)) seg = seg or 0b0000001           // a
        if (lit(r - 1, t + (qy1 - t) / 2)) seg = seg or 0b0000010  // b
        if (lit(r - 1, qy2 + (b - qy2) / 2)) seg = seg or 0b0000100 // c
        if (lit(cx, b - 1)) seg = seg or 0b0001000           // d
        if (lit(l + 1, qy2 + (b - qy2) / 2)) seg = seg or 0b0010000 // e
        if (lit(l + 1, t + (qy1 - t) / 2)) seg = seg or 0b0100000  // f
        if (lit(cx, cy)) seg = seg or 0b1000000              // g
        return seg
    }

    private fun luminance(p: Int): Int {
        val r = (p shr 16) and 0xff
        val g = (p shr 8) and 0xff
        val b = p and 0xff
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
