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
    private var streaming = false

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiTick = object : Runnable {
        override fun run() {
            if (streaming) {
                val ip = deviceIp()
                val f = ScreenMirrorService.frames
                val v = ScreenMirrorService.requests
                val err = ScreenMirrorService.lastError
                statusText.text = "Espelhando!\nNo PC (mesma rede/WiFi) abra:\nhttp://${ip}:8080\nframes=$f visitas=$v" +
                    (if (err != null) "\nErro: $err" else "")
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(statusText)
            addView(btnToggle)
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
            log(this, "app: pedir permissao")
            try {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE)
            } catch (e: Exception) {
                statusText.text = "Erro ao pedir permissão: ${e.message}"
            }
        } else {
            try {
                val svc = Intent(this, ScreenMirrorService::class.java).apply {
                    action = ScreenMirrorService.ACTION_STOP
                }
                startService(svc)
            } catch (_: Exception) { }
            streaming = false
            updateUi()
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
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
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
        if (streaming) {
            statusText.text = "Espelhando!\nNo PC (mesma rede/WiFi) abra:\nhttp://${ip}:8080"
            btnToggle.text = "Parar"
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
