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
 * CAPTURA PROFUNDA DE TOQUES estilo TalkBack — experimental, API 34+. Su capacidad se HABILITA en el
 * panel de Desarrollador, pero solo se ACTIVA (empieza a interceptar) mientras el 🎓 está mantenido
 * oprimido. Nunca intercepta por su cuenta.
 *
 * Modelo de interacción (como el explorador táctil de TalkBack):
 *  - UN toque: se CONSUME (no llega a la app) y se avisa (onExplore) para enmarcar el elemento tocado.
 *    Es exploración: ver qué hay sin activarlo.
 *  - DOBLE toque (sobre el mismo punto, rápido): AHORA sí se reinyecta el clic real con dispatchGesture
 *    para que el sistema interactúe (onActivate).
 *  - Arrastre/desliz de un dedo: se reinyecta tal cual, para poder navegar/scrollear con normalidad.
 *
 * Por qué observar el touchscreen crudo (setMotionEventSources, API 34): muchas apps modernas (Compose,
 * vistas custom como el play de Spotify) NUNCA emiten TYPE_VIEW_CLICKED al tocarlas; esta vía capta el
 * toque físico sin depender de que la app coopere.
 *
 * Seguridad (la doc de Android confirma que las fuentes registradas "are not sent to the rest of the
 * system" mientras están activas):
 *  - disable() es SIEMPRE segura de llamar y devuelve el touchscreen a las apps de inmediato.
 *  - Kill switch (fail): ante dispatchGesture rechazado o cancelado dos veces seguidas, o un segundo
 *    dedo (multitouch fuera de alcance), se apaga sola. Se prefiere perder ESE gesto puntual a
 *    arriesgar quedar atascado.
 *  - El atajo físico de volumen arriba+abajo (nativo de Android para des/activar accesibilidad) es un
 *    KeyEvent, fuente distinta a SOURCE_TOUCHSCREEN: nunca se ve afectado por esta captura.
 */
class DeepTouchCapture(
    private val service: AccessibilityService,
    /** UN toque (consumido, no pasa a la app): enmarca el elemento en (x,y). Coords de pantalla. */
    private val onExplore: (x: Int, y: Int) -> Unit,
    /** DOBLE toque: el clic real ya se reinyectó en (x,y); consolidar/aprender lo interactuado. */
    private val onActivate: (x: Int, y: Int) -> Unit,
    /** El kill switch se disparó: reflejar el apagado (log/UI). */
    private val onKillSwitch: (reason: String) -> Unit,
    /** ¿El punto cae sobre NUESTRA propia UI (carita/panel)? Esos toques se dejan pasar TAL CUAL, para
     *  que el usuario pueda abrir el panel y salir del modo (si no, la carita quedaría intocable). */
    private val isPassthrough: (x: Int, y: Int) -> Boolean = { _, _ -> false },
) {
    companion object {
        val supported get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    @Volatile var active = false
        private set

    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private val doubleTapSlop = ViewConfiguration.get(service).scaledDoubleTapSlop
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()

    private val path = Path()
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragged = false
    private var passthrough = false
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lastTapAt = 0L
    private var consecutiveFailures = 0
    // Tras reinyectar, ignora eventos un instante: cortafuegos contra un posible bucle si los gestos
    // reinyectados reaparecieran por onMotionEvent (evita cualquier realimentación descontrolada).
    @Volatile private var ignoreUntil = 0L

    /** Empieza a interceptar el touchscreen. false si no es soportado o falla. */
    fun enable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val ok = runCatching {
            val info = service.serviceInfo ?: return@runCatching false
            info.motionEventSources = InputDevice.SOURCE_TOUCHSCREEN
            service.serviceInfo = info
            true
        }.getOrDefault(false)
        if (ok) { active = true; consecutiveFailures = 0; lastTapAt = 0L }
        return ok
    }

    /** Kill switch: SIEMPRE segura; libera el touchscreen de inmediato. */
    fun disable() {
        active = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) runCatching {
            val info = service.serviceInfo ?: return@runCatching
            info.motionEventSources = 0
            service.serviceInfo = info
        }
        path.reset()
        lastTapAt = 0L
    }

    /** Llamar desde AccessibilityService.onMotionEvent. No hace nada si no está activa. */
    fun handle(e: MotionEvent) {
        if (!active) return
        if (SystemClock.elapsedRealtime() < ignoreUntil) return
        if (e.pointerCount > 1) { fail("segundo dedo en pantalla (multitouch no soportado)"); return }
        // rawX/rawY: coordenadas de PANTALLA (las mismas de getBoundsInScreen y dispatchGesture).
        val ex = e.rawX; val ey = e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                path.reset(); path.moveTo(ex, ey)
                downX = ex; downY = ey; downAt = SystemClock.elapsedRealtime(); dragged = false
                passthrough = isPassthrough(ex.toInt(), ey.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(ex, ey)
                if (kotlin.math.abs(ex - downX) > slop || kotlin.math.abs(ey - downY) > slop) dragged = true
            }
            MotionEvent.ACTION_UP -> {
                val duration = (SystemClock.elapsedRealtime() - downAt).coerceIn(1, 59_000)
                when {
                    // Toque sobre nuestra carita/panel: se pasa TAL CUAL (con su duración real, para que
                    // funcionen tanto el toque como el mantener-oprimido del 🎓 para salir del modo).
                    passthrough -> { reinject(Path(path), duration); lastTapAt = 0L }
                    // Arrastre en la app: pasa tal cual, para poder scrollear/navegar.
                    dragged -> { reinject(Path(path), duration); lastTapAt = 0L }
                    else -> {
                        val now = SystemClock.elapsedRealtime()
                        val isDouble = lastTapAt != 0L && now - lastTapAt <= doubleTapTimeout &&
                            kotlin.math.abs(downX - lastTapX) <= doubleTapSlop &&
                            kotlin.math.abs(downY - lastTapY) <= doubleTapSlop
                        if (isDouble) {
                            // Segundo toque: AHORA se pasa el clic al sistema (en el punto explorado).
                            reinject(clickPath(lastTapX, lastTapY), 50)
                            onActivate(lastTapX.toInt(), lastTapY.toInt())
                            lastTapAt = 0L
                        } else {
                            // Primer toque: exploración — se consume y se enmarca, sin pasarlo a la app.
                            lastTapX = downX; lastTapY = downY; lastTapAt = now
                            onExplore(downX.toInt(), downY.toInt())
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { path.reset(); passthrough = false }
        }
    }

    private fun clickPath(x: Float, y: Float) = Path().apply { moveTo(x, y); lineTo(x, y) }

    private fun reinject(gesturePath: Path, duration: Long) {
        if (!active) return
        ignoreUntil = SystemClock.elapsedRealtime() + duration + 80 // cortafuegos anti-bucle
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(gesturePath, 0, duration))
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
