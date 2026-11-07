package com.aitrader.bridge

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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Serviço de captura: MediaProjection → frames → detector → ponte HTTP,
 * mantendo o painel flutuante atualizado com a decisão do motor.
 */
class CaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "capture_channel"
        const val NOTIFICATION_ID = 4711
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.aitrader.bridge.action.STOP"

        @Volatile
        var lastFrame: Bitmap? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var prefs: Prefs
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val main = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: FloatingOverlay? = null
    private var lastAnalysis: JSONObject? = null
    private var lastStatusAt = 0L
    private var lastAutoCalibrationAt = 0L
    private var autoCalibrationDone = false

    private val captureLoop = object : Runnable {
        override fun run() {
            captureOnce()
            if (projection != null) worker.postDelayed(this, 1600L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        workerThread = HandlerThread("capture-worker")
        workerThread.start()
        worker = Handler(workerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || data == null) {
            postStatus("ERROR", "NONE", "Sem autorização de captura de tela.")
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, data)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (projection != null) return

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val newProjection = manager.getMediaProjection(resultCode, data)
        if (newProjection == null) {
            postStatus("ERROR", "NONE", "MediaProjection recusado pelo sistema.")
            stopSelf()
            return
        }
        projection = newProjection

        val metrics = realMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                shutdown()
            }
        }, worker)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader = reader

        virtualDisplay = newProjection.createVirtualDisplay(
            "ai-trader-capture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            worker
        )

        showOverlay()
        isRunning = true
        postStatus("CONNECTED_IDLE", "NONE", "Captura iniciada.")
        worker.postDelayed(captureLoop, 600L)
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                densityDpi = resources.displayMetrics.densityDpi
            }
        } else {
            DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        }
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return
        val created = FloatingOverlay(
            context = this,
            onTimeframe = { timeframe ->
                prefs.timeframe = timeframe
                postStatus("CAPTURING", "VALID", "Timeframe $timeframe selecionado.")
            },
            onClose = { stopCapture() }
        )
        created.show()
        overlay = created
        overlay?.update(
            lastAnalysis?.optString("direction") ?: "WAIT",
            lastAnalysis?.optDouble("confidence") ?: 0.0,
            "Análise local pronta. Abra qualquer gráfico.",
            "Gráfico automático · ${prefs.timeframe}"
        )
    }

    private fun captureOnce() {
        val reader = imageReader ?: return
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            val frame = toBitmap(image)
            lastFrame = frame

            val now = System.currentTimeMillis()
            if (!autoCalibrationDone && now - lastAutoCalibrationAt >= 5000L) {
                lastAutoCalibrationAt = now
                AutoCalibrator.detect(frame) { detected ->
                    if (detected != null) {
                        prefs.calibrationJson = detected.toJson()
                        autoCalibrationDone = true
                        postStatusThrottled("CAPTURING", "CALIBRATED", "Calibração automática concluída pela escala de preços.")
                        main.post {
                            overlay?.update("WAIT", 0.0, "Calibração automática concluída. Lendo candles…", "Gráfico automático · ${prefs.timeframe}")
                        }
                    } else if (prefs.calibration() == null) {
                        postStatusThrottled("CALIBRATION_REQUIRED", "NONE", "Procurando a escala de preços da IQ Option para calibrar automaticamente…")
                    }
                }
            }

            val calibration = prefs.calibration()
            if (calibration == null) {
                postStatusThrottled("CALIBRATION_REQUIRED", "NONE", "Aguardando a escala de preços da IQ Option para calibrar.")
                return
            }

            val asset = "AUTO"
            val timeframe = prefs.timeframe
            val candles = CandleDetector.detect(
                frame = frame,
                calibration = calibration,
                timeframe = timeframe,
                nowSec = System.currentTimeMillis() / 1000L
            )

            if (candles.isEmpty()) {
                postStatusThrottled("CAPTURING", "INVALID", "Nenhum candle reconhecido. Verifique zoom, cores e área do gráfico.")
                main.post { overlay?.update("WAIT", 0.0, "Não reconheci candles neste frame. Deixe o gráfico visível e sem menus.", "AUTO · $timeframe · 0 candles") }
                return
            }

            val result = StrategyEngine.analyze(candles, prefs.strategies)
            if (result.direction == "COMPRA" || result.direction == "VENDA") {
                TradeAccessibilityService.requestTrade(result.direction, result.confidence)
            }
            lastAnalysis = JSONObject().put("direction", result.direction).put("confidence", result.confidence).put("reason", result.reason)
            main.post {
                overlay?.update(result.direction, result.confidence, result.reason, "AUTO · $timeframe · ${result.count} candles")
            }
        } catch (e: Exception) {
            postStatusThrottled("ERROR", "INVALID", "Falha na captura: ${e.message}")
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                // imagem já liberada
            }
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val rawWidth = image.width + rowPadding / pixelStride

        val raw = Bitmap.createBitmap(rawWidth, image.height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)

        if (rawWidth == image.width) return raw

        val cropped = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
        raw.recycle()
        return cropped
    }

    private fun uploadCandles(asset: String, timeframe: String, candles: List<DetectedCandle>) {
        val sequence = prefs.sequence + 1
        val array = JSONArray()
        for (candle in candles) array.put(candle.toJson())

        val payload = JSONObject()
            .put("asset", asset)
            .put("timeframe", timeframe)
            .put("sequence", sequence)
            .put("price_mode", "ABSOLUTE")
            .put("detector_version", CandleDetector.VERSION)
            .put("session_id", "android-" + prefs.deviceId.take(8))
            .put("candles", array)

        val baseUrl = prefs.baseUrl
        val token = prefs.token

        network.execute {
            val response = try {
                BridgeApi.post(baseUrl, "/api/bridge/candles", token, payload)
            } catch (e: Exception) {
                postStatusThrottled("ERROR", "STALE", "Sem conexão: ${e.message}")
                return@execute
            }

            when {
                response.status in 200..299 -> {
                    prefs.sequence = sequence
                    response.json().optJSONObject("analysis")?.let { applyAnalysis(it, asset, timeframe) }
                    postStatusThrottled("CAPTURING", "VALID", "Candles enviados: ${candles.size}.")
                }

                response.status == 409 -> {
                    val serverSequence = response.json().optLong("sequence", prefs.sequence)
                    prefs.sequence = maxOf(serverSequence, prefs.sequence)
                    postStatusThrottled("CAPTURING", "VALID", "Sequência sincronizada com a ponte.")
                }

                response.status == 401 -> postStatusThrottled("ERROR", "INVALID", "Token inválido — pareie novamente.")

                else -> postStatusThrottled("DATA_INVALID", "INVALID", response.errorMessage())
            }
        }
    }

    private fun applyAnalysis(analysis: JSONObject, asset: String, timeframe: String) {
        lastAnalysis = analysis
        val direction = analysis.optString("direction", "WAIT")
        val confidence = analysis.optDouble("confidence", 0.0)
        val reason = analysis.optString("reason", "")
        main.post {
            overlay?.update(direction, confidence, reason, "$asset · $timeframe")
        }
    }

    private fun postStatusThrottled(captureState: String, dataState: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatusAt < 8000L) return
        lastStatusAt = now
        postStatus(captureState, dataState, reason)
    }

    private fun postStatus(captureState: String, dataState: String, reason: String) {
        // Operação offline: nenhum endpoint ou servidor é necessário.
        main.post { overlay?.update("WAIT", 0.0, reason, "LOCAL · ${prefs.timeframe}") }
    }

    private fun stopCapture() {
        try {
            projection?.stop()
        } catch (e: Exception) {
            // já parada
        }
        shutdown()
    }

    private fun shutdown() {
        isRunning = false
        worker.removeCallbacksAndMessages(null)

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            // já liberado
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (e: Exception) {
            // já fechado
        }
        imageReader = null
        projection = null

        main.post {
            overlay?.remove()
            overlay = null
        }

        @Suppress("DEPRECATION")
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        worker.removeCallbacksAndMessages(null)
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            // já liberado
        }
        try {
            imageReader?.close()
        } catch (e: Exception) {
            // já fechado
        }
        projection = null
        overlay?.remove()
        overlay = null
        workerThread.quitSafely()
        network.shutdown()
        super.onDestroy()
    }
}