package com.pro.remotecontrol

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: android.widget.TextView
    private lateinit var tvSessionCode: android.widget.TextView
    private lateinit var etCode: android.widget.EditText
    private lateinit var remoteView: io.getstream.webrtc.android.ui.VideoTextureViewRenderer

    private var webRtcClient: WebRtcClient? = null
    private var currentCode: String? = null

    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, ScreenCaptureService::class.java))
            webRtcClient?.startAsHost(result.data!!)
            tvStatus.text = "Compartiendo pantalla. Código generado abajo."
        } else {
            tvStatus.text = "Permiso de captura de pantalla denegado."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSessionCode = findViewById(R.id.tvSessionCode)
        etCode = findViewById(R.id.etCode)
        remoteView = findViewById(R.id.remoteView)

        findViewById<android.widget.Button>(R.id.btnHost).setOnClickListener { confirmHostConsent() }
        findViewById<android.widget.Button>(R.id.btnJoin).setOnClickListener { joinSession() }
    }

    /** Paso de consentimiento explícito ANTES de pedir el permiso del sistema. */
    private fun confirmHostConsent() {
        AlertDialog.Builder(this)
            .setTitle("Permitir control remoto")
            .setMessage(
                "Vas a compartir tu pantalla y permitir que otra persona la toque, " +
                "solo durante esta sesión. Necesitarás también activar el servicio " +
                "de Accesibilidad para esta app en el siguiente paso. Puedes detener " +
                "la sesión en cualquier momento desde la notificación."
            )
            .setPositiveButton("Entiendo, continuar") { _, _ -> startHostFlow() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startHostFlow() {
        if (TouchAccessibilityService.instance == null) {
            AlertDialog.Builder(this)
                .setTitle("Falta activar Accesibilidad")
                .setMessage("Para que el otro teléfono pueda tocar tu pantalla, activa el servicio en Ajustes > Accesibilidad > Control Remoto.")
                .setPositiveButton("Ir a Ajustes") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        currentCode = SignalingClient.generateCode()
        val signaling = SignalingClient(currentCode!!)
        webRtcClient = WebRtcClient(
            context = this,
            signaling = signaling,
            isHost = true,
            onRemoteVideoTrack = {},
            onTouchCommand = { cmd -> TouchAccessibilityService.instance?.dispatchTouch(cmd) }
        ).also { it.init() }

        tvSessionCode.text = currentCode
        tvSessionCode.visibility = android.view.View.VISIBLE

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun joinSession() {
        val code = etCode.text.toString().trim()
        if (code.length != 6) {
            tvStatus.text = "Ingresa un código de 6 dígitos."
            return
        }
        tvStatus.text = "Conectando..."
        val signaling = SignalingClient(code)
        signaling.fetchOffer { offerSdp ->
            if (offerSdp == null) {
                runOnUiThread { tvStatus.text = "Código no encontrado." }
                return@fetchOffer
            }
            runOnUiThread {
                remoteView.init(webRtcClient?.eglContext(), null)
                webRtcClient = WebRtcClient(
                    context = this,
                    signaling = signaling,
                    isHost = false,
                    onRemoteVideoTrack = { track -> attachRemoteTrack(track) },
                    onTouchCommand = {}
                ).also { it.init() }
                remoteView.init(webRtcClient!!.eglContext(), null)
                webRtcClient?.startAsJoiner(offerSdp)
                tvStatus.text = "Conectado. Toca la pantalla para controlar."
                setupRemoteTouchForwarding()
            }
        }
    }

    private fun attachRemoteTrack(track: VideoTrack) {
        runOnUiThread {
            remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            track.addSink(remoteView)
        }
    }

    /** Traduce los toques del controlador sobre remoteView a coordenadas normalizadas. */
    private fun setupRemoteTouchForwarding() {
        remoteView.setOnTouchListener { v, event ->
            val x = event.x / v.width
            val y = event.y / v.height
            val action = when (event.action) {
                MotionEvent.ACTION_DOWN -> "down"
                MotionEvent.ACTION_MOVE -> "move"
                MotionEvent.ACTION_UP -> "up"
                else -> return@setOnTouchListener false
            }
            webRtcClient?.sendTouch(TouchCommand(action, x, y))
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcClient?.release()
    }
}
