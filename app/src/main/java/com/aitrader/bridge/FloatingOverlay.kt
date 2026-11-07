package com.aitrader.bridge

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/** Painel flutuante desenhado por cima da IQ Option. */
class FloatingOverlay(
    private val context: Context,
    private val onTimeframe: (String) -> Unit,
    private val onClose: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var directionView: TextView? = null
    private var confidenceView: TextView? = null
    private var metaView: TextView? = null
    private var reasonView: TextView? = null

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    fun show() {
        if (root != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_panel, null)
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = 24
        layoutParams.y = 180

        directionView = view.findViewById(R.id.overlayDirection)
        confidenceView = view.findViewById(R.id.overlayConfidence)
        metaView = view.findViewById(R.id.overlayMeta)
        reasonView = view.findViewById(R.id.overlayReason)

        view.findViewById<View>(R.id.overlayM15).setOnClickListener { onTimeframe("M15") }
        view.findViewById<View>(R.id.overlayM5).setOnClickListener { onTimeframe("M5") }
        view.findViewById<View>(R.id.overlayM1).setOnClickListener { onTimeframe("M1") }
        view.findViewById<View>(R.id.overlayClose).setOnClickListener { onClose() }

        directionView?.let { attachDrag(view, it, layoutParams) }

        try {
            windowManager.addView(view, layoutParams)
            root = view
            params = layoutParams
        } catch (e: Exception) {
            root = null
        }
    }

    private fun attachDrag(container: View, handle: View, layoutParams: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (event.rawX - startTouchX).toInt()
                    layoutParams.y = startY + (event.rawY - startTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(container, layoutParams)
                    } catch (e: Exception) {
                        // view já removida
                    }
                    true
                }

                else -> false
            }
        }
    }

    fun update(direction: String, confidence: Double, reason: String, meta: String) {
        val color = when (direction) {
            "CALL" -> 0xFF21BA71.toInt()
            "PUT" -> 0xFFE84B45.toInt()
            else -> 0xFFF0B429.toInt()
        }
        directionView?.text = if (direction == "WAIT") "AGUARDAR" else direction
        directionView?.setTextColor(color)
        confidenceView?.text = "${(confidence * 100).roundToInt()}%"
        reasonView?.text = reason
        metaView?.text = meta
        params?.let { root?.let { view -> try { windowManager.updateViewLayout(view, it) } catch (e: Exception) { } } }
    }

    fun remove() {
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // já removida
        }
        root = null
        params = null
    }
}