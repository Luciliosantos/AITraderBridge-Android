package com.aitrader.bridge

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Detector visual de candles.
 *
 * Estratégia: varre a região do gráfico coluna por coluna procurando pixels com cor de
 * candle (verde/vermelho por matiz). Colunas vizinhas da mesma cor formam um candle;
 * a "cobertura de linha" separa corpo e pavios. O eixo Y é convertido em preço pelas
 * duas âncoras da calibração e os candles recebem timestamps alinhados ao timeframe.
 */
object CandleDetector {

    const val VERSION = "detector-1.0.0"

    private const val COLOR_NONE = 0
    private const val COLOR_GREEN = 1
    private const val COLOR_RED = 2

    fun tfSeconds(timeframe: String): Long = when (timeframe) {
        "M5" -> 300L
        "M15" -> 900L
        else -> 60L
    }

    private fun isGreen(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return g > 55 && g > r + 22 && g > b + 8
    }

    private fun isRed(p: Int): Boolean {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return r > 65 && r > g + 30 && r > b + 22
    }

    fun detect(
        frame: Bitmap,
        calibration: Calibration,
        timeframe: String,
        nowSec: Long,
        minConfidence: Double = 0.45,
        maxCandles: Int = 40
    ): List<DetectedCandle> {
        val w = frame.width
        val h = frame.height
        if (w < 60 || h < 60 || !calibration.isValid()) return emptyList()

        val xStart = (calibration.xLeft * w).toInt().coerceIn(0, w - 3)
        val xEnd = (calibration.xRight * w).toInt().coerceIn(xStart + 3, w)
        val step = if (w.toLong() * h > 4_000_000L) 2 else 1
        val regionWidth = xEnd - xStart

        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1) classificação de cada coluna
        val colors = IntArray(regionWidth)
        val colTop = IntArray(regionWidth) { Int.MAX_VALUE }
        val colBottom = IntArray(regionWidth) { -1 }
        val minPixels = max(2, h / 220)

        var ci = 0
        var x = xStart
        while (x < xEnd) {
            var top = Int.MAX_VALUE
            var bottom = -1
            var green = 0
            var red = 0
            var y = 0
            while (y < h) {
                val p = pixels[y * w + x]
                if (isGreen(p)) {
                    green++
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                } else if (isRed(p)) {
                    red++
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                y += step
            }
            colors[ci] = when {
                green >= minPixels && green > red -> COLOR_GREEN
                red >= minPixels && red > green -> COLOR_RED
                else -> COLOR_NONE
            }
            colTop[ci] = top
            colBottom[ci] = bottom
            ci++
            x++
        }

        // 2) agrupa colunas vizinhas da mesma cor em candles (tolerando 1 coluna de falha)
        val segments = mutableListOf<IntArray>() // [start, end, color]
        var i = 0
        while (i < regionWidth) {
            if (colors[i] == COLOR_NONE) {
                i++
                continue
            }
            val color = colors[i]
            var start = i
            var end = i
            var probe = i + 1
            var gap = 0
            while (probe < regionWidth) {
                if (colors[probe] == color) {
                    end = probe
                    gap = 0
                } else if (colors[probe] == COLOR_NONE) {
                    gap++
                    if (gap > 1) break
                } else {
                    break
                }
                probe++
            }
            if (end > start) segments.add(intArrayOf(start, end, color))
            i = end + 1
        }

        // 3) corpo (cobertura de linha) e pavios (extremos da coluna)
        val raw = mutableListOf<DetectedCandle>()
        val maxWidth = max(4, (regionWidth * 0.2).toInt())

        for (seg in segments) {
            val start = seg[0]
            val end = seg[1]
            val color = seg[2]
            val width = end - start + 1
            if (width < 2 || width > maxWidth) continue

            var segTop = Int.MAX_VALUE
            var segBottom = -1
            for (c in start..end) {
                if (colTop[c] < segTop) segTop = colTop[c]
                if (colBottom[c] > segBottom) segBottom = colBottom[c]
            }
            if (segTop == Int.MAX_VALUE || segBottom - segTop < 5) continue

            val rowCover = IntArray(h)
            for (c in start..end) {
                val xPixel = xStart + c
                var y = segTop
                while (y <= segBottom) {
                    val p = pixels[y * w + xPixel]
                    if (isGreen(p) || isRed(p)) rowCover[y]++
                    y++
                }
            }

            val threshold = max(1, (width * 0.6).toInt())
            var bodyTop = -1
            var bodyBottom = -1
            var y = segTop
            while (y <= segBottom) {
                if (rowCover[y] >= threshold) {
                    if (bodyTop < 0) bodyTop = y
                    bodyBottom = y
                }
                y++
            }
            if (bodyTop < 0 || bodyBottom - bodyTop < 1) continue

            val priceHigh = calibration.priceAt(segTop.toFloat() / h)
            val priceLow = calibration.priceAt(segBottom.toFloat() / h)
            val priceTop = calibration.priceAt(bodyTop.toFloat() / h)
            val priceBottom = calibration.priceAt(bodyBottom.toFloat() / h)

            val open: Double
            val close: Double
            if (color == COLOR_GREEN) {
                open = priceBottom
                close = priceTop
            } else {
                open = priceTop
                close = priceBottom
            }

            val high = max(priceHigh, max(open, close))
            val low = min(priceLow, min(open, close))
            if (high <= low) continue

            val fill = (bodyBottom - bodyTop).toDouble() / max(1, segBottom - segTop).toDouble()
            val confidence = (0.45 + 0.35 * fill.coerceIn(0.0, 1.0) + 0.2 * (min(width, 12) / 12.0))
                .coerceIn(0.0, 1.0)
            if (confidence < minConfidence) continue

            raw.add(
                DetectedCandle(
                    timestamp = 0L,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    isClosed = "unknown",
                    confidence = confidence,
                    xPos = end
                )
            )
        }

        if (raw.isEmpty()) return emptyList()

        // 4) timestamps: o candle mais à direita é o bucket atual, os anteriores recuam 1 TF
        val ordered = raw.sortedBy { it.xPos }.takeLast(maxCandles)
        val tfSec = tfSeconds(timeframe)
        val bucketNow = Math.floorDiv(nowSec, tfSec) * tfSec
        val first = bucketNow - (ordered.size - 1) * tfSec

        return ordered.mapIndexed { index, candle ->
            candle.copy(
                timestamp = first + index * tfSec,
                isClosed = if (index == ordered.size - 1) "unknown" else "true"
            )
        }
    }
}