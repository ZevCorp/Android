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
            // Ventana a PANTALLA COMPLETA real (sin insets de barras ni cutout): los bounds del
            // árbol vienen en coordenadas de pantalla y cualquier inset desalinea los recuadros.
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
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
        private val screenLoc = IntArray(2)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 7f
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            if (boxes.isEmpty()) return
            // Contorno en el acento del tema (negro en claro, blanco en oscuro): nunca azul.
            val a = Palette.accent
            stroke.color = a
            glow.color = Color.argb(38, Color.red(a), Color.green(a), Color.blue(a))
            // Los bounds son coordenadas de PANTALLA (getBoundsInScreen); la ventana puede no
            // empezar en (0,0), así que se resta su posición real — igual que hace TalkBack.
            getLocationOnScreen(screenLoc)
            canvas.save()
            canvas.translate(-screenLoc[0].toFloat(), -screenLoc[1].toFloat())
            for (box in boxes) {
                canvas.drawRoundRect(box, 18f, 18f, glow)
                canvas.drawRoundRect(box, 18f, 18f, stroke)
            }
            canvas.restore()
        }
    }
}
