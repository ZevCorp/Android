package com.zevcorp.graph.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * EL MOVIMIENTO de la carita, en un solo sitio.
 *
 * La burbuja de accesibilidad se sentía viva y la carita de dentro de la app no: eran dos arrastres
 * distintos escritos por separado — uno con inercia, aterrizaje al borde, reposo y paseo; el otro
 * un `translationX = dedo` a secas. Aquí vive el bueno, y las dos lo usan.
 *
 * Lo único que cambia entre una y otra es CÓMO se aplica la posición: la burbuja mueve su ventana
 * (`WindowManager.updateViewLayout`) y la de la app mueve una vista (`translationX/Y`). Eso entra
 * por [place]; todo lo demás — inercia, rebote, encogido de reposo, paseo — es idéntico por
 * construcción, no por parecido.
 *
 * Coordenadas: esquina superior izquierda del cuadro que se mueve, en píxeles del área utilizable
 * que reporta [bounds].
 */
class FaceMotion(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Lado del cuadro que se mueve (px). Se consulta en cada gesto: la carita cambia de tamaño. */
    private val size: () -> Int,
    /** Área por la que puede moverse, en px: ancho y alto. */
    private val bounds: () -> Pair<Int, Int>,
    /** Aplica la posición. Es lo ÚNICO específico de cada carita. */
    private val place: (x: Int, y: Int) -> Unit,
    /** Aplica la escala (reposo/despertar). */
    private val scale: (Float) -> Unit,
    /** Toque limpio, sin arrastre. */
    private val onTap: () -> Unit,
    /** Centro de la carita durante el arrastre: lo usan las zonas de encaje (modo reunión). */
    private val onDragTrack: ((cx: Int, cy: Int) -> Unit)? = null,
    /**
     * Al soltar tras arrastrar. Si devuelve `true`, quien la hospeda ya decidió dónde aterriza
     * (p. ej. encajada en una esquina) y este motor no hace nada más.
     */
    private val onDragEnd: ((vx: Float, vy: Float) -> Boolean)? = null,
    /** ¿Está ocupada (ejecutando, escuchando, con un panel abierto)? Entonces no se pasea. */
    private val busy: () -> Boolean = { false },
    /** ¿Tiene sentido pasear ahora? La burbuja lo apaga con la pantalla apagada. */
    private val canWander: () -> Boolean = { true },
) {

    /** Posición actual (esquina superior izquierda, px). */
    var x = 0
        private set
    var y = 0
        private set

    /** ¿Está encogida por reposo? */
    @Volatile var shrunk = false
        private set

    /**
     * Con el reposo apagado, ni encoge ni pasea: su tamaño y su sitio los gobierna quien la
     * hospeda (la burbuja anclada a la barra de la app, por ejemplo).
     */
    var idleEnabled = true

    private var dragAnimator: ValueAnimator? = null
    private var scaleAnimator: ValueAnimator? = null
    private var idleJob: Job? = null
    private var wanderJob: Job? = null

    // Transición "dopamínica": encoge con un rebote suave y agranda con un pop marcado.
    private val idleEase = OvershootInterpolator(1.6f)
    private val idleGrow = OvershootInterpolator(3.4f)

    /** El interpolador con el que se agranda: lo comparten los encajes para que todo case. */
    val growInterpolator: Interpolator get() = idleGrow
    val easeInterpolator: Interpolator get() = idleEase

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** Coloca la carita sin animación (posición inicial, cambios de layout). */
    fun placeAt(newX: Int, newY: Int) {
        x = newX
        y = newY
        place(x, y)
    }

    /* ---------- Arrastre fluido con inercia ---------- */

    /**
     * Arrastre con inercia: un flick corto la lanza a la esquina; siempre "aterriza" al borde.
     * El umbral de movimiento (120 px²) y la proyección de la velocidad son los que tenía la
     * burbuja: cambiarlos aquí los cambia en las dos caritas a la vez.
     */
    fun attach(view: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var tracker: VelocityTracker? = null
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wake() // tocarla/moverla la despierta y la agranda
                    dragAnimator?.cancel()
                    downX = e.rawX; downY = e.rawY; startX = x; startY = y; moved = false
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (moved || dx * dx + dy * dy > 120) {
                        moved = true
                        placeAt(startX + dx, startY + dy)
                        val side = size()
                        onDragTrack?.invoke(x + side / 2, y + side / 2)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        v.performClick()
                    } else {
                        val vt = tracker
                        vt?.addMovement(e)
                        vt?.computeCurrentVelocity(1000)
                        val vx = vt?.xVelocity ?: 0f
                        val vy = vt?.yVelocity ?: 0f
                        if (onDragEnd?.invoke(vx, vy) != true) {
                            flingToEdge(vx, vy)
                            // Al soltarla vuelve a pequeña casi de inmediato, sin esperar los 15 s.
                            scheduleIdleShrink(700)
                        }
                    }
                    tracker?.recycle(); tracker = null
                }
            }
            true
        }
        view.setOnClickListener { onTap() }
    }

    /** Proyecta la velocidad (momentum) y anima hasta el borde más cercano con rebote sutil. */
    fun flingToEdge(vx: Float, vy: Float) {
        val side = size()
        val (areaW, areaH) = bounds()
        val maxX = areaW - side
        val maxY = areaH - side
        val projX = x + vx * 0.12f
        val projY = y + vy * 0.12f
        val destX = if (projX + side / 2 < areaW / 2) 0 else maxX
        val destY = projY.toInt().coerceIn(dp(24), (maxY - dp(24)).coerceAtLeast(dp(24)))
        val speed = kotlin.math.hypot(vx.toDouble(), vy.toDouble()).toFloat()
        val distance = kotlin.math.hypot((destX - x).toDouble(), (destY - y).toDouble())
        val duration = (260 + (distance / (0.6f + speed / 4000f))).toLong().coerceIn(200, 620)
        snapTo(destX, destY, duration, OvershootInterpolator(0.9f))
    }

    /** Animación de encaje hacia un punto (esquinas, regreso tras ejecutar, paseo en reposo). */
    fun snapTo(destX: Int, destY: Int, dur: Long = 220, interp: Interpolator? = null) {
        val fromX = x
        val fromY = y
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interp?.let { interpolator = it }
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                placeAt((fromX + (destX - fromX) * f).toInt(), (fromY + (destY - fromY) * f).toInt())
            }
            start()
        }
    }

    /* ---------- Reposo: la carita se encoge cuando llevas rato sin usarla ---------- */

    /** Reinicia el temporizador de reposo; si estaba encogida, la agranda de nuevo. */
    fun wake() {
        if (!idleEnabled) return
        wanderJob?.cancel()
        if (shrunk) animateScale(1f, idleGrow)
        scheduleIdleShrink()
    }

    /**
     * Reprograma el encogido de reposo. Por defecto ~15 s (dejó de usarla); tras SOLTARLA de un
     * arrastre pasa un [delayMs] corto (~0.7 s) para que vuelva a pequeña casi de inmediato.
     */
    fun scheduleIdleShrink(delayMs: Long = 15_000) {
        if (!idleEnabled) return
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(delayMs)
            if (!shrunk && !busy()) {
                animateScale(0.56f, idleEase)
                startWander()
            }
        }
    }

    /**
     * Muy de vez en cuando (tiempo ALEATORIO, entre 2 y 5 minutos) la carita encogida se pasea a
     * otro punto del borde — señal sutil de vida. Nunca mientras ejecuta, escucha o está anclada.
     */
    private fun startWander() {
        wanderJob?.cancel()
        wanderJob = scope.launch {
            val random = java.util.Random()
            while (shrunk) {
                delay(120_000L + (random.nextFloat() * 180_000L).toLong()) // 2–5 min, nunca exacto
                if (!shrunk) break
                if (!canWander() || busy()) continue
                wanderOnce(random)
            }
        }
    }

    /** Un paseo: al borde opuesto (o el mismo, a veces) con altura aleatoria, animación lenta. */
    private fun wanderOnce(random: java.util.Random) {
        val side = size()
        val (areaW, areaH) = bounds()
        val onLeft = x + side / 2 < areaW / 2
        val goLeft = if (random.nextFloat() < 0.75f) !onLeft else onLeft // casi siempre cruza
        val destX = if (goLeft) 0 else areaW - side
        val minY = dp(90)
        val maxY = areaH - side - dp(140)
        val destY = minY + (random.nextFloat() * (maxY - minY).coerceAtLeast(1)).toInt()
        snapTo(destX, destY, dur = 1400, interp = idleEase)
    }

    /** Escala animada. Encoger es más lento que agrandar: el pop de vuelta se siente inmediato. */
    fun animateScale(target: Float, interp: Interpolator = idleGrow, from: Float = currentScale) {
        shrunk = target < 0.99f
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = if (target < from) 620 else 420
            interpolator = interp
            addUpdateListener { a ->
                currentScale = a.animatedValue as Float
                scale(currentScale)
            }
            start()
        }
    }

    private var currentScale = 1f

    /** Devuelve la carita a tamaño completo de inmediato (sin animación). */
    fun resetScale() {
        scaleAnimator?.cancel()
        shrunk = false
        currentScale = 1f
        scale(1f)
    }

    /** Detiene el reposo y el paseo sin tocar la posición (encajes gobernados por la app). */
    fun cancelIdle() {
        idleJob?.cancel()
        wanderJob?.cancel()
    }

    fun destroy() {
        cancelIdle()
        dragAnimator?.cancel()
        scaleAnimator?.cancel()
    }
}
