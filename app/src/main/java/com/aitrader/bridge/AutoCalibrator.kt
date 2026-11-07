package com.aitrader.bridge

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** OCR focado no eixo de preços à direita; amplia a faixa antes de reconhecer. */
object AutoCalibrator {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val running = AtomicBoolean(false)
    private val number = Regex("(?<![0-9])([0-9]{1,6}[.,][0-9]{2,8})(?![0-9])")

    fun detect(frame: Bitmap, onResult: (Calibration?) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val cropLeft = (frame.width * 0.66f).toInt().coerceIn(0, frame.width - 2)
        val cropTop = (frame.height * 0.07f).toInt().coerceIn(0, frame.height - 2)
        val cropWidth = (frame.width * 0.33f).toInt().coerceAtLeast(1).coerceAtMost(frame.width - cropLeft)
        val cropHeight = (frame.height * 0.88f).toInt().coerceAtLeast(1).coerceAtMost(frame.height - cropTop)
        val crop = Bitmap.createBitmap(frame, cropLeft, cropTop, cropWidth, cropHeight)
        val scale = 3
        val enlarged = Bitmap.createScaledBitmap(crop, crop.width * scale, crop.height * scale, true)
        if (crop !== frame) crop.recycle()

        recognizer.process(InputImage.fromBitmap(enlarged, 0))
            .addOnSuccessListener { result ->
                val h = frame.height.toFloat()
                val labels = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val raw = line.text.replace(" ", "").replace("O", "0").replace("o", "0")
                    val match = number.find(raw) ?: return@mapNotNull null
                    val price = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
                    val y = (cropTop + box.centerY().toFloat() / scale) / h
                    if (y !in 0.08f..0.96f || !price.isFinite()) return@mapNotNull null
                    price to y
                }.distinctBy { String.format(Locale.US, "%.8f", it.first) }

                val sorted = labels.sortedBy { it.second }
                val pair = if (sorted.size >= 2) {
                    val top = sorted.first()
                    val bottom = sorted.last()
                    if (abs(top.first - bottom.first) > 0.0 && bottom.second - top.second >= 0.12f) top to bottom else null
                } else null

                val calibration = pair?.let { (top, bottom) ->
                    Calibration(top.first, bottom.first, top.second, bottom.second, 0.06f, 0.85f)
                        .takeIf { it.isValid() }
                }
                onResult(calibration)
            }
            .addOnFailureListener { onResult(null) }
            .addOnCompleteListener {
                enlarged.recycle()
                running.set(false)
            }
    }
}
