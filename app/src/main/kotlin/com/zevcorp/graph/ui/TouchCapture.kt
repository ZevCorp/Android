package com.zevcorp.graph.ui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager

/**
 * PROXY DE INTERACCIÓN del modo diagnóstico/enseñanza (🎓 mantenido oprimido).
 *
 * Por qué existe: muchas apps modernas (UI de Compose, reproductores con vistas custom como el botón
 * de play de Spotify) NUNCA emiten TYPE_VIEW_CLICKED al tocar con el dedo, y para cuando cualquier
 * evento llega, la UI ya cambió. La única técnica universal (la de los grabadores de UI, documentada
 * en la literatura como "interaction proxy") es capturar el gesto ANTES de que llegue a la app:
 *
 *  1. Una ventana transparente TÁCTIL captura el gesto completo del usuario.
 *  2. En el ACTION_DOWN avisa (x,y): ahí se hace el snapshot del árbol de UI, que aún NO ha cambiado
 *     porque la app ni siquiera ha recibido el toque. Carrera eliminada de raíz.
 *  3. Al soltar, REINYECTA el mismo gesto (mismo recorrido y duración) con dispatchGesture, haciendo
 *     la ventana no-táctil durante la inyección para no volver a capturarlo.
 *
 * Costo: ~50-150 ms de latencia por gesto, SOLO mientras el modo 🎓 está activo. Failsafe: si la
 * reinyección falla (el toque del usuario se perdería), se auto-apaga vía onBroken para no dejar la
 * pantalla "sorda".
 */
class TouchCapture(
    private val service: AccessibilityService,
    /** ACTION_DOWN en coordenadas de pantalla: momento exacto de tomar el snapshot pre-cambio. */
    private val onDown: (x: Int, y: Int) -> Unit,
    /** El gesto terminó y fue un TAP (sin arrastre): confirmar el diagnóstico del snapshot. */
    private val onTap: () -> Unit,
    /** La reinyección falló de forma dura: quien controla el modo debe apagarlo ya. */
    private val onBroken: () -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    private val path = Path()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragged = false

    val active get() = view != null

    fun start() {
        if (view != null) return
        val v = View(service)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // TÁCTIL (sin FLAG_NOT_TOUCHABLE): es la ventana la que captura el gesto del usuario.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        v.setOnTouchListener { _, e -> handle(e); true }
        if (runCatching { wm.addView(v, p) }.isFailure) return
        view = v
        params = p
    }

    fun stop() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    private fun handle(e: MotionEvent) {
        // rawX/rawY: coordenadas de PANTALLA, las mismas que espera dispatchGesture.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(e.rawX, e.rawY)
                downX = e.rawX; downY = e.rawY
                downAt = SystemClock.elapsedRealtime()
                dragged = false
                onDown(e.rawX.toInt(), e.rawY.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(e.rawX, e.rawY)
                if (kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop)
                    dragged = true
            }
            MotionEvent.ACTION_UP -> {
                val duration = (SystemClock.elapsedRealtime() - downAt).coerceIn(1, 59_000)
                if (!dragged) onTap()
                replay(duration)
            }
            MotionEvent.ACTION_CANCEL -> path.reset()
        }
    }

    /** Reinyecta el gesto capturado; la ventana se hace no-táctil mientras, para no re-capturarlo. */
    private fun replay(duration: Long) {
        val v = view ?: return
        val p = params ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path(path), 0, duration))
            .build()
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = restoreTouchable()
            override fun onCancelled(g: GestureDescription?) = restoreTouchable()
        }, null)
        if (!ok) { restoreTouchable(); onBroken() }
    }

    private fun restoreTouchable() {
        val v = view ?: return
        val p = params ?: return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(v, p) }
    }
}
