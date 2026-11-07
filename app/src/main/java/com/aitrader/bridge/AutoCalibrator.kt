package com.aitrader.bridge

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Lê os preços do eixo direito da IQ Option para calibrar automaticamente o eixo Y.
 * Os limites horizontais são proporcionais ao layout IQ Option em paisagem.
 */
object AutoCalibrator {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val running = AtomicBoolean(false)
    private val number = Regex("(?<![0-9])([0-9]{1,4}[.,][0-9]{1,8})(?![0-9])")

    fun detect(frame: Bitmap, onResult: (Calibration?) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val image = InputImage.fromBitmap(frame, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val w = frame.width.toFloat()
                val h = frame.height.toFloat()
                val labels = result.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    if (box.centerX() < w * 0.70f || box.centerX() > w * 0.88f || box.centerY() < h * 0.12f || box.centerY() > h * 0.90f) return@mapNotNull null
                    val match = number.find(line.text.replace(" ", "")) ?: return@mapNotNull null
                    val price = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
                    Triple(price, box.centerY() / h, box.left / w)
                }.distinctBy { String.format(Locale.US, "%.8f", it.first) }

                val pair = if (labels.size >= 2) {
                    val sorted = labels.sortedBy { it.second }
                    val top = sorted.first()
                    val bottom = sorted.last()
                    if (abs(top.first - bottom.first) > 0.0 && bottom.second - top.second >= 0.18f) top to bottom else null
                } else null

                val calibration = pair?.let { (top, bottom) ->
                    Calibration(
                        priceA = top.first,
                        priceB = bottom.first,
                        yA = top.second,
                        yB = bottom.second,
                        xLeft = 0.06f,
                        xRight = 0.85f
                    ).takeIf { it.isValid() }
                }
                onResult(calibration)
            }
            .addOnFailureListener { onResult(null) }
            .addOnCompleteListener { running.set(false) }
    }
}
