package com.remotecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "remote_control_channel"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_CODE = "pairing_code"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        private const val TARGET_FPS = 10
        private const val JPEG_QUALITY = 50
        private const val MAX_DIMENSION = 1280
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private var captureWidth = 0
    private var captureHeight = 0
    private var dpi = 1
    @Volatile
    private var lastFrameTime = 0L
    private val frameInterval = 1000L / TARGET_FPS

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("ScreenCapture").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: run {
            Log.e(TAG, "缺少服务器地址")
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent.getStringExtra(EXTRA_CODE) ?: run {
            Log.e(TAG, "缺少配对码")
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            Log.e(TAG, "缺少 MediaProjection Intent")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        ServiceManager.isRunning = true
        ServiceManager.onStatusChange?.let { }

        // 设置屏幕尺寸
        val (realWidth, realHeight) = getScreenDimensions()
        dpi = resources.displayMetrics.densityDpi

        val scale = if (maxOf(realWidth, realHeight) > MAX_DIMENSION) {
            MAX_DIMENSION.toFloat() / maxOf(realWidth, realHeight)
        } else {
            1f
        }
        captureWidth = (realWidth * scale).toInt()
        captureHeight = (realHeight * scale).toInt()
        ServiceManager.scaleX = realWidth.toFloat() / captureWidth
        ServiceManager.scaleY = realHeight.toFloat() / captureHeight

        Log.i(TAG, "屏幕: ${realWidth}x${realHeight}, 捕获: ${captureWidth}x${captureHeight}, 缩放: $scale")

        // 初始化 MediaProjection
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped")
                cleanupExceptWebSocket()
                ServiceManager.updateStatus("已连接服务器，等待控制端...")
            }
        }, handler)

        // 设置 WebSocket
        val deviceInfo = JSONObject().apply {
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("androidVersion", Build.VERSION.RELEASE)
        }
        WebSocketManager.connect(serverUrl, code, deviceInfo, this)

        // 设置画面捕获
        setupImageReader()
        setupVirtualDisplay()

        return START_NOT_STICKY
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight,
            android.graphics.PixelFormat.RGBA_8888, 2
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            // 未配对（含排队中）时不处理帧，节省带宽和 CPU
            if (!ServiceManager.isPaired) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < frameInterval) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                sendFrame(image)
            } catch (e: Exception) {
                Log.e(TAG, "帧处理失败", e)
            } finally {
                image.close()
            }
        }, handler)
    }

    private fun setupVirtualDisplay() {
        val surface = imageReader?.surface ?: run {
            Log.e(TAG, "ImageReader surface 为空")
            return
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "RemoteControl",
            captureWidth, captureHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler
        )
    }

    private fun sendFrame(image: Image) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * captureWidth

        val bitmapWidth = captureWidth + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, captureHeight, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        val cropped = if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
        } else {
            bitmap
        }

        val baos = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        val jpegData = baos.toByteArray()

        WebSocketManager.sendFrame(jpegData)

        bitmap.recycle()
        if (cropped !== bitmap) cropped.recycle()
    }

    private fun getScreenDimensions(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val point = Point()
        display.getRealSize(point)
        return Pair(point.x, point.y)
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "远程控制", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "远程控制屏幕共享服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("远程控制")
            .setContentText("正在共享屏幕...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun cleanupExceptWebSocket() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "释放 VirtualDisplay 失败", e)
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 ImageReader 失败", e)
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止 MediaProjection 失败", e)
        }
        mediaProjection = null
    }

    private fun cleanup() {
        cleanupExceptWebSocket()
        WebSocketManager.disconnect()
        ServiceManager.isRunning = false
        ServiceManager.accessibilityService?.let { }
    }

    override fun onDestroy() {
        cleanup()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
