package com.casanova.espelho

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMirrorService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"
        const val PORT = 8080

        @Volatile var latestJpeg: ByteArray? = null
        @Volatile var lastError: String? = null
    }

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var captureThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (code == -1 || data == null) {
                    lastError = "Falha interna (codigo permissão)."
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForegroundWithNotification()
                } catch (e: Exception) {
                    lastError = "Sem permissão de notificação/serviço: ${e.message}"
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startStreaming(code, data)
                } catch (t: Throwable) {
                    lastError = "Falha ao iniciar captura: ${t.message}"
                    stopStreaming()
                    stopSelf()
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val chId = "espelho"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(chId, "Espelho", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, chId)
            .setContentTitle("Espelho Casanova")
            .setContentText("Transmitindo em :$PORT")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun startStreaming(resultCode: Int, data: Intent) {
        if (running.getAndSet(true)) return

        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(resultCode, data)
        try {
            projection?.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { }

        val metrics = resources.displayMetrics
        val width = 720
        val height = (width * metrics.heightPixels / metrics.widthPixels.toFloat()).toInt().coerceAtMost(1280)
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "espelho", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        captureThread = Thread {
            while (running.get()) {
                try {
                    val img = imageReader?.acquireLatestImage()
                    if (img != null) {
                        try {
                            latestJpeg = imageToJpeg(img)
                        } catch (_: Exception) {
                        } finally {
                            img.close()
                        }
                    } else {
                        Thread.sleep(50)
                    }
                } catch (_: Exception) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(PORT)
                while (running.get()) {
                    try {
                        val client = serverSocket!!.accept()
                        Thread { handleClient(client) }.also { it.isDaemon = true; it.start() }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            } catch (_: Exception) { }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun imageToJpeg(img: android.media.Image): ByteArray {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val w = img.width + rowPadding / pixelStride
        val bmp = Bitmap.createBitmap(w, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        val cropped = if (w != img.width) {
            Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        } else bmp
        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 60, out)
        if (cropped !== bmp) cropped.recycle()
        bmp.recycle()
        return out.toByteArray()
    }

    private fun handleClient(sock: Socket) {
        try {
            sock.soTimeout = 5000
            val input = sock.getInputStream().bufferedReader()
            val out: OutputStream = sock.getOutputStream()
            val request = input.readLine() ?: ""
            // consome headers
            while (true) {
                val l = input.readLine() ?: break
                if (l.isEmpty()) break
            }
            if (request.contains("/stream")) {
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray())
                out.flush()
                while (running.get() && !sock.isClosed) {
                    val frame = latestJpeg
                    if (frame != null) {
                        val part = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                        out.write(part.toByteArray())
                        out.write(frame)
                        out.write("\r\n".toByteArray())
                        out.flush()
                    }
                    Thread.sleep(100) // ~10 fps
                }
            } else {
                val html = "<html><head><meta charset=utf-8><title>Espelho Casanova</title></head>" +
                    "<body style='background:#111;color:#fff;text-align:center;font-family:sans-serif'>" +
                    "<h2>Espelho Casanova - ao vivo</h2>" +
                    "<img src='/stream' style='max-width:100%;border:2px solid #444'/>" +
                    "<p>Abra http://SEU-IP:8080 neste PC. Mesma rede/WiFi.</p>" +
                    "</body></html>"
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html"
                out.write(resp.toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) { }
        }
    }

    private fun stopStreaming() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        serverThread?.interrupt()
        captureThread?.interrupt()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}
