package com.aitrader.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var inputServer: EditText
    private lateinit var inputDevice: EditText
    private lateinit var inputCode: EditText
    private lateinit var inputAsset: EditText
    private lateinit var textStatus: TextView
    private lateinit var buttonCapture: Button

    private val network = Executors.newSingleThreadExecutor()
    private var statusMessage = ""

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, CaptureService::class.java).apply {
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(this, intent)
                renderState("Captura iniciada.")
            } else {
                renderState("Captura não autorizada pelo Android.")
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)

        inputServer = findViewById(R.id.inputServer)
        inputDevice = findViewById(R.id.inputDevice)
        inputCode = findViewById(R.id.inputCode)
        inputAsset = findViewById(R.id.inputAsset)
        textStatus = findViewById(R.id.textStatus)
        buttonCapture = findViewById(R.id.buttonCapture)

        inputServer.setText(prefs.baseUrl)
        inputDevice.setText(prefs.deviceName.ifBlank { Build.MODEL ?: "Android" })
        inputAsset.setText(prefs.asset)

        findViewById<Button>(R.id.buttonPair).setOnClickListener { pairDevice() }
        findViewById<Button>(R.id.buttonOverlay).setOnClickListener { requestOverlayPermission() }
        buttonCapture.setOnClickListener { toggleCapture() }
        findViewById<Button>(R.id.buttonCalibrate).setOnClickListener { openCalibration() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        renderState(if (prefs.isPaired()) "Pareado. Token salvo neste aparelho." else "")
    }

    override fun onResume() {
        super.onResume()
        renderState(statusMessage)
    }

    private fun renderState(message: String) {
        statusMessage = message
        val paired = prefs.isPaired()
        val running = CaptureService.isRunning
        val header = when {
            !paired -> getString(R.string.state_disconnected)
            running -> "Captura ativa — painel flutuante disponível."
            else -> getString(R.string.state_ready)
        }
        textStatus.text = if (message.isBlank()) header else "$header\n$message"
        buttonCapture.text = if (running) getString(R.string.action_stop) else getString(R.string.action_start)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        renderState(message)
    }

    private fun pairDevice() {
        val baseUrl = inputServer.text.toString().trim()
        val deviceName = inputDevice.text.toString().trim().ifBlank { Build.MODEL ?: "Android" }
        val code = inputCode.text.toString().trim().uppercase()
        val asset = inputAsset.text.toString().trim()

        if (baseUrl.isBlank()) {
            toast("Informe a URL do painel.")
            return
        }
        if (code.isBlank()) {
            toast("Informe o código gerado no painel.")
            return
        }

        prefs.baseUrl = baseUrl
        prefs.deviceName = deviceName
        prefs.asset = asset
        renderState("Pareando…")

        val payload = JSONObject()
            .put("code", code)
            .put("device_name", deviceName)
            .put("android_version", Build.VERSION.RELEASE ?: "")
            .put("app_version", "1.0.0")

        network.execute {
            try {
                val response = BridgeApi.post(baseUrl, "/api/bridge/pair", null, payload)
                if (response.status in 200..299) {
                    val json = response.json()
                    prefs.token = json.optString("token")
                    prefs.deviceId = json.optString("device_id")
                    prefs.sequence = 0L
                    runOnUiThread {
                        inputCode.setText("")
                        renderState("Pareado. Token guardado neste aparelho.")
                    }
                } else {
                    runOnUiThread { renderState("Falha no pareamento: " + response.errorMessage()) }
                }
            } catch (e: Exception) {
                runOnUiThread { renderState("Sem conexão com o painel: ${e.message}") }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("Sobreposição já liberada.")
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun toggleCapture() {
        if (CaptureService.isRunning) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            renderState("Captura encerrada.")
            return
        }
        if (!prefs.isPaired()) {
            toast("Faça o pareamento antes de capturar.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Libere a sobreposição para ver o painel sobre a IQ Option.")
            requestOverlayPermission()
            return
        }
        if (prefs.asset.isBlank()) {
            toast("Defina o ativo monitorado.")
            return
        }

        val manager = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun openCalibration() {
        val frame = CaptureService.lastFrame
        if (frame == null) {
            toast(getString(R.string.calibration_no_frame))
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_calibration, null)
        val priceA = view.findViewById<EditText>(R.id.priceA)
        val priceB = view.findViewById<EditText>(R.id.priceB)
        val calibrationView = view.findViewById<CalibrationView>(R.id.calibrationView)
        calibrationView.setFrame(frame)

        prefs.calibration()?.let { current ->
            priceA.setText(current.priceA.toString())
            priceB.setText(current.priceB.toString())
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.calibration_title)
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(R.string.calibration_save) { _, _ ->
                val priceAValue = priceA.text.toString().replace(',', '.').toDoubleOrNull()
                val priceBValue = priceB.text.toString().replace(',', '.').toDoubleOrNull()
                if (priceAValue == null || priceBValue == null) {
                    toast("Preços inválidos.")
                    return@setPositiveButton
                }
                if (!calibrationView.fractionsReady()) {
                    toast("Toque nos 4 pontos da imagem.")
                    return@setPositiveButton
                }
                val calibration = Calibration(
                    priceA = priceAValue,
                    priceB = priceBValue,
                    yA = calibrationView.yA,
                    yB = calibrationView.yB,
                    xLeft = calibrationView.xLeft,
                    xRight = calibrationView.xRight
                )
                if (!calibration.isValid()) {
                    toast("Calibração inválida: repita os toques.")
                    return@setPositiveButton
                }
                prefs.calibrationJson = calibration.toJson()
                toast("Calibração salva.")
            }
            .show()
    }

    override fun onDestroy() {
        network.shutdown()
        super.onDestroy()
    }
}