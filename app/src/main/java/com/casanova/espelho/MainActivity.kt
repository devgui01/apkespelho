package com.casanova.espelho

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnToggle: Button
    private var streaming = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_DATA, result.data!!)
            }
            ContextCompat.startForegroundService(this, svc)
            streaming = true
            updateUi()
        } else {
            statusText.text = "Permissão negada. Tente de novo."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            textSize = 16f
            text = "Toque em Iniciar para espelhar."
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
        updateUi()
    }

    private fun toggle() {
        if (!streaming) {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            val svc = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_STOP
            }
            startService(svc)
            streaming = false
            updateUi()
        }
    }

    private fun updateUi() {
        if (streaming) {
            val ip = wifiIp()
            statusText.text = "Espelhando!\nNo PC (mesma rede/WiFi) abra:\nhttp://${ip}:8080"
            btnToggle.text = "Parar"
        } else {
            val ip = wifiIp()
            statusText.text = String.format(
                Locale.US,
                "Parado.\nSeu IP atual: %s\nToque em Iniciar e abra http://%s:8080 no PC.",
                ip, ip
            )
            btnToggle.text = "Iniciar espelho"
        }
    }

    private fun wifiIp(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val i = wm.connectionInfo.ipAddress
        return String.format(
            Locale.US, "%d.%d.%d.%d",
            i and 0xff, i shr 8 and 0xff, i shr 16 and 0xff, i shr 24 and 0xff
        )
    }
}
