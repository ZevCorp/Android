package com.zevcorp.graph.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.WindowManager

/**
 * Overlay que dibuja un recuadro brillante alrededor de un elemento del árbol de UI durante la
 * enseñanza ("mira cómo lo haría"). La secuencia (tin·tin·tin) la orquesta el servicio con delays.
 */
class HighlightOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: BoxView? = null

    fun show(rect: Rect) {
        val v = view ?: BoxView(service).also {
            view = it
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { wm.addView(it, p) }
        }
        v.box = RectF(rect)
        v.invalidate()
    }

    fun hide() {
        view?.let { it.box = null; it.invalidate() }
    }

    fun destroy() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private class BoxView(c: Context) : View(c) {
        var box: RectF? = null
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 9f; color = Color.parseColor("#2F8CFF")
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.parseColor("#332F8CFF")
        }

        override fun onDraw(canvas: Canvas) {
            box?.let {
                canvas.drawRoundRect(it, 22f, 22f, glow)
                canvas.drawRoundRect(it, 22f, 22f, stroke)
            }
        }
    }
}
