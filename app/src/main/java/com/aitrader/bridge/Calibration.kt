package com.aitrader.bridge

import org.json.JSONObject
import kotlin.math.abs

/**
 * Calibração do detector: duas âncoras de preço no eixo Y e os limites horizontais
 * do gráfico. Tudo em fração (0..1) da imagem capturada, para sobreviver a mudanças
 * de resolução e rotação.
 */
data class Calibration(
    val priceA: Double,
    val priceB: Double,
    val yA: Float,
    val yB: Float,
    val xLeft: Float,
    val xRight: Float
) {

    fun priceAt(yFraction: Float): Double {
        val dy = yA - yB
        if (abs(dy) < 0.000001f) return priceA
        val t = (yFraction - yB) / dy
        return priceB + (priceA - priceB) * t
    }

    fun isValid(): Boolean =
        priceA != priceB &&
            abs(yA - yB) > 0.01f &&
            xRight > xLeft + 0.05f

    fun toJson(): String = JSONObject()
        .put("priceA", priceA)
        .put("priceB", priceB)
        .put("yA", yA.toDouble())
        .put("yB", yB.toDouble())
        .put("xLeft", xLeft.toDouble())
        .put("xRight", xRight.toDouble())
        .toString()

    companion object {
        fun fromJson(raw: String?): Calibration? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                Calibration(
                    o.getDouble("priceA"),
                    o.getDouble("priceB"),
                    o.getDouble("yA").toFloat(),
                    o.getDouble("yB").toFloat(),
                    o.getDouble("xLeft").toFloat(),
                    o.getDouble("xRight").toFloat()
                ).takeIf { it.isValid() }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Candle lido da tela, já convertido para preço. */
data class DetectedCandle(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val isClosed: String,
    val confidence: Double,
    val xPos: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestamp)
        .put("open", open)
        .put("high", high)
        .put("low", low)
        .put("close", close)
        .put("is_closed", isClosed)
        .put("confidence", confidence)
}