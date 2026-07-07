package com.zevcorp.graph.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/**
 * Silueta de nube blanca y esponjosa dibujada por composición de un cuerpo redondeado + protuberancias
 * (círculos) arriba y abajo, con una sombra suave. Sirve tanto para la barra de escritura (ancha) como
 * para los botones tipo nube (toggle, micrófono, enviar). Requiere capa de software en la vista anfitriona
 * para que la sombra difusa (`setShadowLayer`) se renderice.
 */
class CloudDrawable(
    private val fill: Int = Color.WHITE,
    private val bumpsTop: Int = 4,
    private val bumpsBottom: Int = 3,
    private val shadow: Boolean = true,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val h = b.height().toFloat()
        val r = h * 0.28f // radio de las protuberancias
        val top = b.top + r * 0.75f
        val bot = b.bottom - r * 0.75f
        val left = b.left + r * 0.75f
        val right = b.right - r * 0.75f

        if (shadow) paint.setShadowLayer(h * 0.18f, 0f, h * 0.07f, 0x33203040)

        path.reset()
        val body = RectF(left, top, right, bot)
        path.addRoundRect(body, (bot - top) / 2f, (bot - top) / 2f, Path.Direction.CW)

        fun bump(cx: Float, cy: Float, rad: Float) = path.addCircle(cx, cy, rad, Path.Direction.CW)
        for (i in 0 until bumpsTop) {
            val f = if (bumpsTop == 1) 0.5f else i / (bumpsTop - 1f)
            bump(lerp(left, right, f), top, r * (0.9f + 0.25f * (1f - kotlin.math.abs(f - 0.5f) * 2f)))
        }
        for (i in 0 until bumpsBottom) {
            val f = if (bumpsBottom == 1) 0.5f else i / (bumpsBottom - 1f)
            bump(lerp(left, right, f), bot, r * 0.85f)
        }
        bump(left, (top + bot) / 2f, r * 0.9f)
        bump(right, (top + bot) / 2f, r * 0.9f)

        canvas.drawPath(path, paint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Botón con forma de nube y un ícono vectorial centrado (toggle de vistas, micrófono, enviar). */
fun Context.cloudChip(
    icon: Icon,
    sizeDp: Int = 46,
    tint: Int = 0xFF37566E.toInt(),
    subtle: Boolean = false,
    onClick: (() -> Unit)? = null,
): FrameLayout {
    val container = FrameLayout(this)
    val d = dp(sizeDp)
    container.layoutParams = FrameLayout.LayoutParams(d, d)
    container.setLayerType(View.LAYER_TYPE_SOFTWARE, null) // para la sombra difusa de la nube
    container.background = CloudDrawable(fill = Color.WHITE, bumpsTop = 3, bumpsBottom = 2)
    val iv = IconView(this, icon, tint = tint).apply {
        val pad = (d * 0.34f).toInt()
        layoutParams = FrameLayout.LayoutParams(d - pad, d - pad, Gravity.CENTER)
    }
    container.addView(iv)
    if (subtle) container.alpha = 0.6f
    onClick?.let { container.setOnClickListener { it() } }
    return container
}
