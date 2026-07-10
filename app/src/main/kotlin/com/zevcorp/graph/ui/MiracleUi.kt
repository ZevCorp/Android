package com.zevcorp.graph.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.animation.ValueAnimator

/**
 * Paleta de la pantalla principal "Miracle" (portada del diseño estético entregado). Todo derivado de
 * los tokens oklch del diseño, convertidos a sRGB. Modo claro (principal) y oscuro. Ver globals.css.
 */
data class MiracleTheme(
    val bgTop: Int, val bgMid: Int, val bgBot: Int,
    val glow: Int, val glowStrong: Int, val stream: Int,
    val pill: Int, val pillText: Int, val placeholder: Int, val border: Int,
)

private val LIGHT_MIRACLE = MiracleTheme(
    bgTop = 0xFFEAF7FF.toInt(), bgMid = 0xFFF9FCFF.toInt(), bgBot = 0xFFFFFFFF.toInt(),
    glow = 0xFFAED7F5.toInt(), glowStrong = 0xFF71C1F7.toInt(), stream = 0xFF4FA8E1.toInt(),
    pill = 0xFFFFFFFF.toInt(), pillText = 0xFF0F171F.toInt(), placeholder = 0xFF737373.toInt(),
    border = 0xFFE5E5E5.toInt(),
)
private val DARK_MIRACLE = MiracleTheme(
    bgTop = 0xFF122032.toInt(), bgMid = 0xFF07101C.toInt(), bgBot = 0xFF03060D.toInt(),
    glow = 0xFF3275B4.toInt(), glowStrong = 0xFF2389E2.toInt(), stream = 0xFF1A83DB.toInt(),
    pill = 0xFF1C222B.toInt(), pillText = 0xFFF0F6FC.toInt(), placeholder = 0xFF8A94A6.toInt(),
    border = 0x1AFFFFFF,
)

fun miracleTheme(): MiracleTheme = if (Palette.mode == ThemeMode.DARK) DARK_MIRACLE else LIGHT_MIRACLE

private fun withAlpha(color: Int, a: Float): Int =
    (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

/** Fondo: degradado radial suave desde arriba-centro (glow azulado) hacia el blanco/negro del fondo. */
class MiracleBgView(context: Context, private val t: MiracleTheme) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = RadialGradient(
            w * 0.5f, 0f, h * 1.15f,
            intArrayOf(t.bgTop, t.bgMid, t.bgBot), floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}

/**
 * Glow radial reutilizable (halo del título, aura de la cara, reflejo del suelo). Se estira al tamaño
 * de la vista, así que en una vista ancha-y-baja queda como elipse (reflejo) y en una cuadrada, círculo.
 */
class RadialGlowView(
    context: Context,
    private val color: Int,
    private val maxAlpha: Float = 0.6f,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var pulse: Float = 1f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = (minOf(width, height) / 2f) * pulse
        if (r <= 0f) return
        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(withAlpha(color, maxAlpha), withAlpha(color, 0f)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.scale(width / (2f * r), height / (2f * r), cx, cy)
        canvas.drawCircle(cx, cy, r, paint)
        canvas.restore()
    }
}

/**
 * Los "streams" de luz descendentes del diseño: líneas verticales finas con degradado
 * transparente→azul→transparente que aparecen, bajan y se desvanecen en bucle. Puro decorado.
 */
class StreamsView(context: Context, private val t: MiracleTheme) : View(context) {
    private data class Stream(val leftFrac: Float, val heightDp: Int, val delay: Float, val dur: Float)
    private val streams = listOf(
        Stream(0.32f, 90, 0f, 3.2f), Stream(0.38f, 140, 0.6f, 4f), Stream(0.44f, 200, 1.1f, 3.6f),
        Stream(0.50f, 260, 0.3f, 4.4f), Stream(0.56f, 190, 0.9f, 3.4f), Stream(0.62f, 130, 1.4f, 4.1f),
        Stream(0.68f, 80, 0.5f, 3f), Stream(0.47f, 160, 1.7f, 3.8f), Stream(0.53f, 220, 2f, 4.2f),
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(1).toFloat() }
    private val density = context.resources.displayMetrics.density
    private var clock = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 6000L; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { clock = it.animatedValue as Float * 6f; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        for (s in streams) {
            val local = ((clock + s.delay) % s.dur) / s.dur // 0..1 dentro de su ciclo
            val fade = when {
                local < 0.4f -> local / 0.4f
                else -> 1f - (local - 0.4f) / 0.6f
            }.coerceIn(0f, 1f)
            if (fade <= 0.01f) continue
            val x = w * s.leftFrac
            val hPx = s.heightDp * density
            val top = height * 0.16f + local * hPx * 0.4f
            paint.shader = LinearGradient(
                x, top, x, top + hPx,
                intArrayOf(withAlpha(t.stream, 0f), withAlpha(t.stream, 0.35f * fade), withAlpha(t.stream, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawLine(x, top, x, top + hPx, paint)
        }
    }
}
