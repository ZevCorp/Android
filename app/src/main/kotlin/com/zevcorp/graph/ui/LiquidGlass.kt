package com.zevcorp.graph.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Píldora "liquid glass" (estilo Apple), calcada del diseño de referencia midiendo su columna
 * vertical pixel a pixel. La profundidad la dan cuatro capas en un solo gradiente + borde:
 *
 *  · borde superior casi blanco (rim light ~95%) que se apaga hacia abajo,
 *  · mitad superior "lechosa" (blanco 62%→42%) — la iluminación de arriba,
 *  · cuerpo inferior mucho más transparente (deja ver el cielo, ~20%),
 *  · y una LUZ DE REBOTE en el borde inferior (vuelve a ~45%): el toque 3D del vidrio.
 *
 * Las paradas de alfa son las medidas en la imagen (implied alpha del blanco sobre el cielo).
 */
class LiquidGlassDrawable(private val maxRadiusPx: Float, private val strokePx: Float) : Drawable() {

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }
    private val rect = RectF()

    override fun onBoundsChange(b: Rect) {
        rect.set(b)
        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()
        // Cuerpo: paradas medidas en el diseño (blanco con alfa variable, de arriba a abajo).
        body.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(
                0xBFFFFFFF.toInt(), // 0.00 · 75% — bajo el rim, lo más lechoso
                0x9EFFFFFF.toInt(), // 0.10 · 62%
                0x6BFFFFFF.toInt(), // 0.45 · 42% — la mitad clara de arriba
                0x4AFFFFFF.toInt(), // 0.62 · 29%
                0x33FFFFFF.toInt(), // 0.85 · 20% — el vidrio casi desnudo
                0x42FFFFFF.toInt(), // 0.93 · 26%
                0x73FFFFFF.toInt(), // 1.00 · 45% — luz de rebote del borde inferior
            ),
            floatArrayOf(0f, 0.10f, 0.45f, 0.62f, 0.85f, 0.93f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Borde: brillante arriba (rim light), tenue a los lados y levemente encendido abajo.
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xF2FFFFFF.toInt(), 0x66FFFFFF.toInt(), 0x33FFFFFF.toInt(), 0x80FFFFFF.toInt()),
            floatArrayOf(0f, 0.35f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        // Cápsula mientras es una línea; si crece (multilínea) el radio se queda en el máximo.
        val r = minOf(rect.height() / 2f, maxRadiusPx)
        canvas.drawRoundRect(rect, r, r, body)
        val inset = strokePx / 2f
        canvas.drawRoundRect(
            RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset),
            r - inset, r - inset, rim)
    }

    override fun setAlpha(alpha: Int) { body.alpha = alpha; rim.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        body.colorFilter = colorFilter; rim.colorFilter = colorFilter; invalidateSelf()
    }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
