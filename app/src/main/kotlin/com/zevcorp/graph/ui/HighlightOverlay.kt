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
 * Overlay que dibuja recuadros brillantes alrededor de elementos del árbol de UI. Lo usa el modo
 * "ver lo aprendido" (mantener oprimido el 🎓): ilumina a la vez el contorno de TODOS los elementos
 * que ya están trackeados en MCPs dentro de la app visible, y se refresca al navegar.
 */
class HighlightOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: BoxView? = null

    fun show(rects: List<Rect>) {
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
        v.boxes = rects.map { RectF(it) }
        v.invalidate()
    }

    fun hide() {
        view?.let { it.boxes = emptyList(); it.invalidate() }
    }

    fun destroy() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private class BoxView(c: Context) : View(c) {
        var boxes: List<RectF> = emptyList()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.parseColor("#2F8CFF")
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.parseColor("#262F8CFF")
        }

        override fun onDraw(canvas: Canvas) {
            for (box in boxes) {
                canvas.drawRoundRect(box, 18f, 18f, glow)
                canvas.drawRoundRect(box, 18f, 18f, stroke)
            }
        }
    }
}
