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
 * accionables que el sistema DETECTA en la app visible, y se refresca al navegar. Distingue por color:
 * los que ya están trackeados en MCPs (aprendidos) se pintan en VERDE; el resto de lo detectado, en el
 * acento del tema (negro/blanco). Es puramente visual: no cambia qué elementos son usables.
 */
class HighlightOverlay(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: BoxView? = null

    /** Aprendidos (verde) y el resto de lo detectado (acento). Ambos son recuadros a dibujar a la vez. */
    fun show(learned: List<Rect>, detected: List<Rect>) {
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
        v.learnedBoxes = learned.map { RectF(it) }
        v.detectedBoxes = detected.map { RectF(it) }
        v.invalidate()
    }

    /**
     * Destello de DIAGNÓSTICO: ilumina en naranja, encima de todo, el recuadro del elemento que el
     * agente resolvería al tocar (ver GraphAccessibilityService.probeResolved). `null` lo apaga. No
     * afecta a los recuadros estáticos (aprendido/detectado): es una capa separada y momentánea.
     */
    fun probe(rect: Rect?) {
        view?.let { it.probeBox = rect?.let { r -> RectF(r) }; it.invalidate() }
    }

    fun hide() {
        view?.let {
            it.learnedBoxes = emptyList(); it.detectedBoxes = emptyList(); it.probeBox = null; it.invalidate()
        }
    }

    fun destroy() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private class BoxView(c: Context) : View(c) {
        var learnedBoxes: List<RectF> = emptyList()
        var detectedBoxes: List<RectF> = emptyList()
        var probeBox: RectF? = null
        private val screenLoc = IntArray(2)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 7f
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            if (learnedBoxes.isEmpty() && detectedBoxes.isEmpty() && probeBox == null) return
            // Los bounds son coordenadas de PANTALLA (getBoundsInScreen); la ventana puede no
            // empezar en (0,0), así que se resta su posición real — igual que hace TalkBack.
            getLocationOnScreen(screenLoc)
            canvas.save()
            canvas.translate(-screenLoc[0].toFloat(), -screenLoc[1].toFloat())
            // Primero lo detectado-pero-no-aprendido en el acento del tema (negro/blanco: nunca azul)…
            drawBoxes(canvas, detectedBoxes, Palette.accent)
            // …lo aprendido en verde, para que resalte sobre el resto…
            drawBoxes(canvas, learnedBoxes, Palette.learned)
            // …y por ENCIMA de todo, el destello de diagnóstico (naranja) del elemento resuelto al tocar.
            probeBox?.let { drawBoxes(canvas, listOf(it), Palette.probe) }
            canvas.restore()
        }

        private fun drawBoxes(canvas: Canvas, boxes: List<RectF>, color: Int) {
            if (boxes.isEmpty()) return
            stroke.color = color
            glow.color = Color.argb(38, Color.red(color), Color.green(color), Color.blue(color))
            for (box in boxes) {
                canvas.drawRoundRect(box, 18f, 18f, glow)
                canvas.drawRoundRect(box, 18f, 18f, stroke)
            }
        }
    }
}
