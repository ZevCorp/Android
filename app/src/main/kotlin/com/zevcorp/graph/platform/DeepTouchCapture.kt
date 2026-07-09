package com.zevcorp.graph.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration

/**
 * CAPTURA PROFUNDA DE TOQUES — experimental, API 34+, APAGADA por defecto. Toggle manual desde el
 * panel de Desarrollador (nunca se reactiva sola entre reinicios del servicio).
 *
 * Por qué existe: muchas apps modernas (UI de Compose, vistas custom como el botón de play de Spotify)
 * NUNCA emiten TYPE_VIEW_CLICKED al tocarlas con el dedo — no es que la señal llegue tarde, es que no
 * existe. Es la causa real por la que el aprendizaje nunca detectaba esos toques.
 *
 * La vía "moderna": registrar el servicio para OBSERVAR los MotionEvent crudos del touchscreen
 * (AccessibilityServiceInfo#setMotionEventSources, API 34) y REINYECTAR cada gesto con dispatchGesture
 * para que la app los reciba con normalidad — el usuario no nota diferencia salvo una latencia mínima.
 *
 * RIESGO REAL (por qué está tan acotado): la documentación de Android es explícita — los MotionEvent
 * de las fuentes registradas "are not sent to the rest of the system" mientras estén activas. Si la
 * reinyección fallara sin red de seguridad, el touchscreen completo dejaría de responder, en cualquier
 * app, incluida la pantalla de Ajustes para desactivar el servicio. Por eso:
 *  - Apagada por defecto; el toggle vive en el panel de Desarrollador y no se persiste como "encendida".
 *  - Kill switch INMEDIATO (`fail()`) ante la primera señal real de problema: dispatchGesture
 *    rechazado o gesto cancelado dos veces seguidas, o un segundo dedo en pantalla (multitouch: fuera
 *    de alcance de esta primera versión — se prefiere perder ESE gesto puntual a arriesgar quedar
 *    atascado). `disable()` es segura de llamar en cualquier estado y devuelve el touchscreen a las
 *    apps de inmediato.
 *  - El gesto físico de volumen arriba+abajo (atajo NATIVO de Android para activar/desactivar un
 *    servicio de accesibilidad) sigue funcionando SIEMPRE pase lo que pase aquí: es un KeyEvent, una
 *    fuente de entrada distinta a SOURCE_TOUCHSCREEN, así que nunca se ve afectado por esta captura.
 */
class DeepTouchCapture(
    private val service: AccessibilityService,
    /** ACTION_DOWN en coordenadas de pantalla: la UI todavía no ha reaccionado al toque. */
    private val onDown: (x: Int, y: Int) -> Unit,
    /** El gesto terminó y fue un TAP (sin arrastre significativo). */
    private val onTap: () -> Unit,
    /** El kill switch se disparó: quien controla el toggle debe reflejar el apagado (UI, log, aviso). */
    private val onKillSwitch: (reason: String) -> Unit,
) {
    companion object {
        val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    @Volatile var active = false
        private set

    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private val path = Path()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragged = false
    private var consecutiveFailures = 0

    /** Activa la observación del touchscreen a nivel de sistema. false si no es soportado o falla. */
    fun enable(): Boolean {
        if (!supported) return false
        val ok = runCatching {
            val info = service.serviceInfo ?: return@runCatching false
            info.motionEventSources = InputDevice.SOURCE_TOUCHSCREEN
            service.serviceInfo = info
            true
        }.getOrDefault(false)
        if (ok) { active = true; consecutiveFailures = 0 }
        return ok
    }

    /** Kill switch: SIEMPRE segura de llamar en cualquier estado; libera el touchscreen de inmediato. */
    fun disable() {
        active = false
        runCatching {
            val info = service.serviceInfo ?: return@runCatching
            info.motionEventSources = 0
            service.serviceInfo = info
        }
        path.reset()
    }

    /** Llamar desde AccessibilityService.onMotionEvent. No hace nada si no está activa. */
    fun handle(e: MotionEvent) {
        if (!active) return
        if (e.pointerCount > 1) { fail("segundo dedo en pantalla (multitouch no soportado)"); return }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(e.x, e.y)
                downX = e.x; downY = e.y
                downAt = SystemClock.elapsedRealtime()
                dragged = false
                onDown(e.x.toInt(), e.y.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(e.x, e.y)
                if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) dragged = true
            }
            MotionEvent.ACTION_UP -> {
                val duration = (SystemClock.elapsedRealtime() - downAt).coerceIn(1, 59_000)
                if (!dragged) onTap()
                replay(duration)
            }
            MotionEvent.ACTION_CANCEL -> path.reset()
        }
    }

    private fun replay(duration: Long) {
        if (!active) return
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(Path(path), 0, duration))
            .build()
        val ok = runCatching {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { consecutiveFailures = 0 }
                override fun onCancelled(g: GestureDescription?) = fail("gesto reinyectado cancelado")
            }, null)
        }.getOrDefault(false)
        if (!ok) fail("dispatchGesture rechazó el gesto")
    }

    private fun fail(reason: String) {
        consecutiveFailures++
        LogBus.log("deep-touch", "⚠️ $reason (fallo $consecutiveFailures/2)")
        if (consecutiveFailures >= 2) { disable(); onKillSwitch(reason) }
    }
}
