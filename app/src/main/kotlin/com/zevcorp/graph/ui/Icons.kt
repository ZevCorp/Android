package com.zevcorp.graph.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** Set de íconos de línea limpios (trazo fino, extremos redondeados) estilo Apple/OpenAI. */
enum class Icon { MIC, SEND, CLOSE, STOP, TEACH, EYE, BOLT, TOOLS, CODE, ASSISTANT }

/**
 * Ícono vectorial dibujado en Canvas — nítido a cualquier tamaño y con la estética de línea
 * minimalista (SF Symbols / OpenAI) en vez de los emojis anteriores.
 */
class IconView(context: Context, private val icon: Icon, var tint: Int = Palette.text) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        fun x(v: Float) = ox + v * s
        fun y(v: Float) = oy + v * s
        stroke.color = tint
        fill.color = tint
        stroke.strokeWidth = s * 0.072f

        when (icon) {
            Icon.MIC -> {
                // cuerpo tipo cápsula
                val w = s * 0.26f
                val body = RectF(x(0.5f) - w / 2, y(0.16f), x(0.5f) + w / 2, y(0.56f))
                canvas.drawRoundRect(body, w / 2, w / 2, stroke)
                // cuna en U
                path.reset()
                path.addArc(RectF(x(0.28f), y(0.30f), x(0.72f), y(0.66f)), 20f, 140f)
                canvas.drawPath(path, stroke)
                // tallo + base
                canvas.drawLine(x(0.5f), y(0.66f), x(0.5f), y(0.80f), stroke)
                canvas.drawLine(x(0.36f), y(0.82f), x(0.64f), y(0.82f), stroke)
            }
            Icon.SEND -> {
                canvas.drawLine(x(0.5f), y(0.76f), x(0.5f), y(0.24f), stroke)
                path.reset()
                path.moveTo(x(0.30f), y(0.44f)); path.lineTo(x(0.5f), y(0.22f)); path.lineTo(x(0.70f), y(0.44f))
                canvas.drawPath(path, stroke)
            }
            Icon.CLOSE -> {
                canvas.drawLine(x(0.30f), y(0.30f), x(0.70f), y(0.70f), stroke)
                canvas.drawLine(x(0.70f), y(0.30f), x(0.30f), y(0.70f), stroke)
            }
            Icon.STOP -> {
                val r = RectF(x(0.30f), y(0.30f), x(0.70f), y(0.70f))
                canvas.drawRoundRect(r, s * 0.07f, s * 0.07f, fill)
            }
            Icon.TEACH -> { // destello de 4 puntas (sparkle)
                sparkle(canvas, ::x, ::y, 0.5f, 0.5f, 0.34f, s)
                sparkle(canvas, ::x, ::y, 0.80f, 0.22f, 0.13f, s)
            }
            Icon.EYE -> {
                path.reset()
                path.moveTo(x(0.14f), y(0.5f))
                path.quadTo(x(0.5f), y(0.22f), x(0.86f), y(0.5f))
                path.quadTo(x(0.5f), y(0.78f), x(0.14f), y(0.5f))
                path.close()
                canvas.drawPath(path, stroke)
                canvas.drawCircle(x(0.5f), y(0.5f), s * 0.11f, stroke)
            }
            Icon.BOLT -> {
                path.reset()
                path.moveTo(x(0.56f), y(0.14f)); path.lineTo(x(0.30f), y(0.54f)); path.lineTo(x(0.48f), y(0.54f))
                path.lineTo(x(0.44f), y(0.86f)); path.lineTo(x(0.70f), y(0.46f)); path.lineTo(x(0.52f), y(0.46f))
                path.close()
                canvas.drawPath(path, stroke)
            }
            Icon.TOOLS -> { // cuatro celdas redondeadas (rejilla de herramientas)
                val g = s * 0.05f
                for (r in 0..1) for (c in 0..1) {
                    val l = x(0.24f) + c * (s * 0.26f + g)
                    val t = y(0.24f) + r * (s * 0.26f + g)
                    canvas.drawRoundRect(RectF(l, t, l + s * 0.20f, t + s * 0.20f), s * 0.05f, s * 0.05f, stroke)
                }
            }
            Icon.CODE -> {
                path.reset()
                path.moveTo(x(0.38f), y(0.32f)); path.lineTo(x(0.20f), y(0.5f)); path.lineTo(x(0.38f), y(0.68f))
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(x(0.62f), y(0.32f)); path.lineTo(x(0.80f), y(0.5f)); path.lineTo(x(0.62f), y(0.68f))
                canvas.drawPath(path, stroke)
            }
            Icon.ASSISTANT -> { // destello + orbe: identidad del asistente
                canvas.drawCircle(x(0.5f), y(0.5f), s * 0.26f, stroke)
                sparkle(canvas, ::x, ::y, 0.5f, 0.5f, 0.14f, s)
            }
        }
    }

    /** Destello de 4 puntas con lados cóncavos (hacia el centro). */
    private fun sparkle(canvas: Canvas, x: (Float) -> Float, y: (Float) -> Float, cx: Float, cy: Float, r: Float, s: Float) {
        val w = r * 0.34f // "cintura" del destello
        path.reset()
        path.moveTo(x(cx), y(cy - r))
        path.quadTo(x(cx + w), y(cy - w), x(cx + r), y(cy))
        path.quadTo(x(cx + w), y(cy + w), x(cx), y(cy + r))
        path.quadTo(x(cx - w), y(cy + w), x(cx - r), y(cy))
        path.quadTo(x(cx - w), y(cy - w), x(cx), y(cy - r))
        path.close()
        canvas.drawPath(path, fill)
    }
}

/** Botón circular con un ícono vectorial centrado (reemplaza los TextView con emoji). */
fun Context.iconChip(
    icon: Icon,
    sizeDp: Int = 42,
    primary: Boolean = false,
    tint: Int? = null,
    onClick: (() -> Unit)? = null,
): FrameLayout {
    val container = FrameLayout(this)
    val d = dp(sizeDp)
    val iv = IconView(this, icon, tint = tint ?: if (primary) Palette.bg else Palette.text).apply {
        val pad = (d * 0.28f).toInt()
        val lp = FrameLayout.LayoutParams(d - pad, d - pad, Gravity.CENTER)
        layoutParams = lp
    }
    container.addView(iv)
    container.layoutParams = FrameLayout.LayoutParams(d, d)
    container.background = rounded(
        if (primary) android.graphics.Color.WHITE else Palette.card,
        (sizeDp / 2).toFloat() * resources.displayMetrics.density,
        if (primary) 0 else Palette.cardBorder,
    )
    onClick?.let { container.setOnClickListener { it() } }
    return container
}
