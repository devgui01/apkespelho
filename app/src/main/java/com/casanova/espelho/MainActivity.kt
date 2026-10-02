package com.casanova.espelho

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.net.NetworkInterface
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val REQ_CAPTURE = 1001
        const val REQ_PERMS = 1002
        const val LOG = "crash.log"

        fun log(ctx: Context, msg: String) {
            try {
                ctx.openFileOutput(LOG, Context.MODE_APPEND).use {
                    it.write(("[" + System.currentTimeMillis() + "] " + msg + "\n").toByteArray())
                }
            } catch (_: Exception) { }
        }

        fun readLog(ctx: Context): String {
            return try {
                ctx.openFileInput(LOG).bufferedReader().readText()
            } catch (_: Exception) { "" }
        }

        fun clearLog(ctx: Context) {
            try { ctx.deleteFile(LOG) } catch (_: Exception) { }
        }

        fun installHandler(ctx: Context) {
            val appCtx = ctx.applicationContext
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    val sw = java.io.StringWriter()
                    e.printStackTrace(java.io.PrintWriter(sw))
                    log(appCtx, "FATAL thread=" + t.name + " " + e.toString() + "\n" + sw.toString().take(2000))
                } catch (_: Exception) { }
                prev?.uncaughtException(t, e)
            }
        }
    }

    private lateinit var statusText: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnCam: Button
    private var streaming = false
    private var camMode = 0 // 0=off 1=tras 2=frente

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun tickText(): String {
        val ip = deviceIp()
        val f = ScreenMirrorService.frames
        val v = ScreenMirrorService.requests
        val cf = ScreenMirrorService.camFrames
        val err = ScreenMirrorService.lastError
        val lat = ScreenMirrorService.lastLat
        val lon = ScreenMirrorService.lastLon
        val loc = if (lat != null && lon != null) "GPS $lat,$lon" else "GPS buscando..."
        val cam = when {
            !ScreenMirrorService.camOn && camMode == 0 -> "Cam OFF"
            ScreenMirrorService.camOn && ScreenMirrorService.camFacing ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "Cam FRONTAL"
            ScreenMirrorService.camOn -> "Cam TRASEIRA"
            else -> "Cam ligando..."
        }
        return "No PC abra:\nhttp://${ip}:8080\nframes=$f cam=$cf visitas=$v\n$loc | $cam" +
            (if (err != null) "\nErro: $err" else "")
    }

    private val uiTick = object : Runnable {
        override fun run() {
            if (streaming || ScreenMirrorService.camOn) {
                statusText.text = tickText()
                uiHandler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installHandler(this)
        statusText = TextView(this).apply {
            textSize = 15f
            text = "Carregando..."
        }
        btnToggle = Button(this).apply {
            text = "Iniciar espelho"
            setOnClickListener { toggle() }
        }
        btnCam = Button(this).apply {
            text = "Câmera: OFF"
            setOnClickListener { toggleCam() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(statusText)
            addView(btnToggle)
            addView(btnCam)
        }
        setContentView(layout)
        val saved = readLog(this)
        if (saved.isNotEmpty()) {
            statusText.text = "LOG da última sessão:\n" + saved.take(1500)
            btnToggle.text = "Limpar log e continuar"
            streaming = false
        } else {
            updateUi()
        }
    }

    private fun toggle() {
        if (readLog(this).isNotEmpty() && !streaming) {
            clearLog(this)
            updateUi()
            return
        }
        if (!streaming) {
            clearLog(this)
            val need = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) need.add(android.Manifest.permission.POST_NOTIFICATIONS)
            if (checkSelfPermission(android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) need.add(android.Manifest.permission.CAMERA)
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) need.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), REQ_PERMS)
                statusText.text = "Permita câmera e localização para continuar."
                return
            }
            beginCapture()
        } else {
            try {
                val svc = Intent(this, ScreenMirrorService::class.java).apply {
                    action = ScreenMirrorService.ACTION_STOP
                }
                startService(svc)
            } catch (_: Exception) { }
            streaming = false
            camMode = 0
            updateUi()
        }
    }

    private fun beginCapture() {
        // 1) servico vira foreground AGORA (exigencia do Android 12+)
        try {
            val prep = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_PREPARE
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(prep) else startService(prep)
        } catch (e: Exception) {
            statusText.text = "Erro ao iniciar serviço: ${e.message}"
            return
        }
        // 2) pede permissao de captura
        log(this, "app: pedir permissao")
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
        } catch (e: Exception) {
            statusText.text = "Erro ao pedir permissão: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) beginCapture()
    }

    private fun toggleCam() {
        camMode = (camMode + 1) % 3
        try {
            // garante foreground antes (Android 12+)
            val prep = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_PREPARE
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(prep) else startService(prep)
            if (camMode == 0) {
                val stop = Intent(this, ScreenMirrorService::class.java).apply {
                    action = ScreenMirrorService.ACTION_CAM_STOP
                }
                startService(stop)
            } else {
                val facing = if (camMode == 2)
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                else
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                val start = Intent(this, ScreenMirrorService::class.java).apply {
                    action = ScreenMirrorService.ACTION_CAM
                    putExtra(ScreenMirrorService.EXTRA_FACING, facing)
                }
                startService(start)
            }
        } catch (e: Exception) {
            statusText.text = "Erro câmera: ${e.message}"
            camMode = 0
        }
        updateCamBtn()
    }

    private fun updateCamBtn() {
        btnCam.text = when (camMode) {
            1 -> "Câmera: TRASEIRA"
            2 -> "Câmera: FRONTAL"
            else -> "Câmera: OFF"
        }
    }

    @Deprecated("compat universal")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, ScreenMirrorService::class.java).apply {
                    action = ScreenMirrorService.ACTION_START
                    putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenMirrorService.EXTRA_DATA, data)
                }
                try {
                    startService(svc) // servico ja esta em foreground (PREPARE)
                    streaming = true
                } catch (e: Exception) {
                    statusText.text = "Erro ao iniciar: ${e.message}"
                    streaming = false
                }
                updateUi()
            } else {
                statusText.text = "Permissão negada. Toque em Iniciar de novo."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val err = ScreenMirrorService.lastError
        if (err != null && !streaming) {
            statusText.text = "Erro: $err\nToque em Iniciar de novo."
            ScreenMirrorService.lastError = null
        } else {
            updateUi()
        }
    }

    private fun updateUi() {
        val ip = deviceIp()
        if (streaming || ScreenMirrorService.camOn) {
            statusText.text = tickText()
            btnToggle.text = if (streaming) "Parar" else "Iniciar espelho"
            uiHandler.removeCallbacks(uiTick)
            uiHandler.post(uiTick)
        } else {
            uiHandler.removeCallbacks(uiTick)
            statusText.text = String.format(
                Locale.US,
                "Pronto.\nIP do celular: %s\nToque em Iniciar e abra http://%s:8080 no PC.",
                ip, ip
            )
            btnToggle.text = "Iniciar espelho"
        }
        updateCamBtn()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(uiTick)
        super.onDestroy()
    }

    private fun deviceIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a.hostAddress != null && a.hostAddress!!.contains(".")) {
                        val ip = a.hostAddress!!
                        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("172.")) return ip
                    }
                }
            }
        } catch (_: Exception) { }
        return "ver-no-wifi"
    }
}
