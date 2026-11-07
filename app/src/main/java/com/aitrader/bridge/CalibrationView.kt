package com.aitrader.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Mostra o frame capturado e coleta 4 toques, nesta ordem:
 * preço A, preço B, borda esquerda do gráfico, borda direita.
 * As coordenadas são guardadas como fração da imagem.
 */
class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frame: Bitmap? = null
    private val drawRect = RectF()
    private var step = 0

    var yA = -1f
        private set
    var yB = -1f
        private set
    var xLeft = -1f
        private set
    var xRight = -1f
        private set

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setFrame(bitmap: Bitmap) {
        frame = bitmap
        updateRect()
        invalidate()
    }

    fun fractionsReady(): Boolean = step >= 4

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateRect()
    }

    private fun updateRect() {
        val bitmap = frame ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val scale = minOf(viewWidth / bitmap.width, viewHeight / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (viewWidth - drawWidth) / 2f
        val top = (viewHeight - drawHeight) / 2f
        drawRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateRect()
        val bitmap = frame ?: return
        canvas.drawBitmap(bitmap, null, drawRect, null)

        if (yA >= 0f) {
            val y = drawRect.top + yA * drawRect.height()
            linePaint.color = Color.parseColor("#F0B429")
            canvas.drawLine(drawRect.left, y, drawRect.right, y, linePaint)
            markerPaint.color = Color.parseColor("#F0B429")
            canvas.drawCircle(drawRect.left + 8f, y, 6f, markerPaint)
        }
        if (yB >= 0f) {
            val y = drawRect.top + yB * drawRect.height()
            linePaint.color = Color.parseColor("#41B3FF")
            canvas.drawLine(drawRect.left, y, drawRect.right, y, linePaint)
            markerPaint.color = Color.parseColor("#41B3FF")
            canvas.drawCircle(drawRect.left + 8f, y, 6f, markerPaint)
        }
        if (xLeft >= 0f) {
            val x = drawRect.left + xLeft * drawRect.width()
            linePaint.color = Color.parseColor("#8B9AAC")
            canvas.drawLine(x, drawRect.top, x, drawRect.bottom, linePaint)
        }
        if (xRight >= 0f) {
            val x = drawRect.left + xRight * drawRect.width()
            linePaint.color = Color.parseColor("#8B9AAC")
            canvas.drawLine(x, drawRect.top, x, drawRect.bottom, linePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        if (frame == null || drawRect.width() <= 0f) return false
        if (!drawRect.contains(event.x, event.y)) return true

        val fractionX = (event.x - drawRect.left) / drawRect.width()
        val fractionY = (event.y - drawRect.top) / drawRect.height()

        when (step) {
            0 -> {
                yA = fractionY
                step = 1
            }

            1 -> {
                yB = fractionY
                step = 2
            }

            2 -> {
                xLeft = fractionX
                step = 3
            }

            else -> {
                if (fractionX > xLeft + 0.05f) {
                    xRight = fractionX
                    step = 4
                }
            }
        }
        invalidate()
        return true
    }
}