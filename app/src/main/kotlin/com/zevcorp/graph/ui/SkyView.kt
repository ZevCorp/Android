package com.zevcorp.graph.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * El cielo de Ü: la "textura" de la app. Un fondo de nubes de cúmulo que enmarcan los bordes
 * (arriba, abajo y esquinas) sobre azul vivo, replicando la foto base. Las nubes se dibujan
 * componiéndolas de "copos" suaves (bitmap radial reutilizado, barato de pintar).
 *
 *  · `animate = true`  → la textura está viva: las nubes oscilan y respiran lentamente y responden
 *    al arrastre del dedo (paralaje). Es la única vista con movimiento.
 *  · `animate = false` → un solo fotograma estático, como fondo de las otras vistas.
 */
class SkyView(context: Context, private val animate: Boolean) : View(context) {

    // Azul de la foto: un poco más claro y neblinoso arriba, azul vivo hacia el centro-abajo.
    private val skyTop = 0xFF4C9BEA.toInt()
    private val skyMid = 0xFF1E82E6.toInt()
    private val skyBot = 0xFF3C8FE8.toInt()

    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hazePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val puffPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var puff: Bitmap? = null
    private val dst = RectF()

    private class Puff(val dx: Float, val dy: Float, val r: Float)
    private class Cloud(
        val homeX: Float, val homeY: Float, val scale: Float,
        val ampX: Float, val ampY: Float, val phase: Float, val speed: Float,
        val depth: Float, val puffs: List<Puff>,
    )

    private val clouds = ArrayList<Cloud>()

    /** Reloj propio en segundos, avanzado por el Choreographer (solo cuando `animate`). */
    private var tSec = 0f
    private var lastFrameNs = 0L

    /** Desplazamiento por arrastre del dedo; se disipa suavemente al soltar. */
    private var dragOffset = 0f
    private var dragging = false
    private var lastTouchX = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNs != 0L) {
                val dt = ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
                tSec += dt
            }
            lastFrameNs = frameTimeNanos
            if (!dragging) dragOffset *= 0.90f
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animate) {
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (animate) Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        skyPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(skyTop, skyMid, skyBot), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        // Halo neblinoso claro en el centro-alto (el "sol" difuso de la foto).
        hazePaint.shader = RadialGradient(w * 0.5f, h * 0.30f, h * 0.42f,
            intArrayOf(0x33FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        buildPuff(h)
        buildClouds(w, h)
    }

    /** Copo suave reutilizable: blanco pleno en el núcleo que se desvanece al borde. */
    private fun buildPuff(h: Int) {
        val size = (h * 0.5f).toInt().coerceIn(96, 512)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(size / 2f, size / 2f, size / 2f,
            intArrayOf(Color.WHITE, Color.WHITE, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        puff = bmp
    }

    /** Genera las nubes que enmarcan la composición: bandas arriba y abajo, con esquinas más densas. */
    private fun buildClouds(w: Int, h: Int) {
        clouds.clear()
        val fw = w.toFloat(); val fh = h.toFloat()
        var seed = 0x9E3779B1.toInt()
        fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }

        fun cumulus(): List<Puff> {
            val puffs = ArrayList<Puff>()
            val n = 5 + (rnd() * 3).toInt() // 5..7 copos en la base
            for (i in 0 until n) {
                val t = if (n == 1) 0.5f else i / (n - 1f)
                val x = (t - 0.5f) * 1.7f
                val y = 0.22f - 0.10f * (1f - abs(x)) + (rnd() - 0.5f) * 0.08f
                val r = 0.62f - 0.20f * abs(x) + (rnd() - 0.5f) * 0.10f
                puffs.add(Puff(x, y, r.coerceAtLeast(0.28f)))
            }
            // Un par de copos apilados al centro para dar altura al cúmulo.
            puffs.add(Puff(-0.22f + (rnd() - 0.5f) * 0.2f, -0.14f, 0.5f))
            puffs.add(Puff(0.24f + (rnd() - 0.5f) * 0.2f, -0.20f, 0.44f))
            puffs.add(Puff((rnd() - 0.5f) * 0.2f, -0.02f, 0.6f))
            return puffs
        }

        // cxFrac, cyFrac (0..1), scaleFrac (fracción del ancho), amplitudes de oscilación.
        fun add(cxF: Float, cyF: Float, scaleF: Float, ampXF: Float, ampYF: Float, speed: Float) {
            clouds.add(Cloud(
                homeX = cxF * fw, homeY = cyF * fh, scale = scaleF * fw,
                ampX = ampXF * fw, ampY = ampYF * fh, phase = rnd() * 6.283f, speed = speed,
                depth = 0.5f + scaleF, puffs = cumulus()))
        }

        // Banda superior (enmarca el borde de arriba).
        add(0.14f, 0.03f, 0.30f, 0.020f, 0.006f, 0.28f)
        add(0.48f, -0.02f, 0.40f, 0.026f, 0.008f, 0.22f)
        add(0.86f, 0.04f, 0.32f, 0.022f, 0.006f, 0.26f)
        add(0.00f, 0.16f, 0.28f, 0.018f, 0.010f, 0.32f)
        add(1.00f, 0.15f, 0.30f, 0.018f, 0.010f, 0.30f)
        // Banda inferior (enmarca el borde de abajo).
        add(0.20f, 1.00f, 0.34f, 0.024f, 0.008f, 0.24f)
        add(0.56f, 1.04f, 0.44f, 0.028f, 0.010f, 0.20f)
        add(0.88f, 0.99f, 0.32f, 0.022f, 0.008f, 0.27f)
        add(0.03f, 0.87f, 0.30f, 0.020f, 0.012f, 0.31f)
        add(0.97f, 0.89f, 0.30f, 0.020f, 0.012f, 0.29f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), hazePaint)
        val bmp = puff ?: return
        for (cloud in clouds) {
            val offX = cloud.ampX * sin(cloud.phase + tSec * cloud.speed) + dragOffset * cloud.depth
            val offY = cloud.ampY * sin(cloud.phase * 1.3f + tSec * cloud.speed * 0.7f)
            val breath = 1f + 0.03f * sin(cloud.phase + tSec * cloud.speed * 0.5f)
            val cx = cloud.homeX + offX
            val cy = cloud.homeY + offY
            val s = cloud.scale * breath
            for (p in cloud.puffs) {
                val pr = p.r * s
                val px = cx + p.dx * s
                val py = cy + p.dy * s
                dst.set(px - pr, py - pr, px + pr, py + pr)
                canvas.drawBitmap(bmp, null, dst, puffPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!animate) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; lastTouchX = event.x; return true }
            MotionEvent.ACTION_MOVE -> {
                dragOffset = (dragOffset + (event.x - lastTouchX) * 0.6f).coerceIn(-width * 0.25f, width * 0.25f)
                lastTouchX = event.x
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
        }
        return false
    }
}
