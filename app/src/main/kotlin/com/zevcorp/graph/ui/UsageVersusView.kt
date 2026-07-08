package com.zevcorp.graph.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * Gráfica central del "modo usuario": dos barras enfrentadas, en blanco/negro (nunca azul).
 *  · Ü  → cuánto tiempo el asistente usó el dispositivo por ti.
 *  · Tú → cuánto tiempo estuviste tú frente a la pantalla.
 * La palabra "versus" flota arriba, al centro, muy por encima de las barras.
 */
class UsageVersusView(
    context: Context,
    private val uMs: Long,
    private val userMs: Long,
) : View(context) {

    private val pad = context.dp(24)
    private val barW = context.dp(64)

    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
        color = Palette.cardBorder
    }
    private val versus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textAlign = Paint.Align.CENTER
        textSize = context.dp(30).toFloat()
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textAlign = Paint.Align.CENTER
        textSize = context.dp(19).toFloat()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val valP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.textDim
        textAlign = Paint.Align.CENTER
        textSize = context.dp(13).toFloat()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val heightPx = context.dp(360)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), heightPx)
    }

    private fun fmt(ms: Long): String {
        val min = ms / 60_000L
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 -> "${h} h ${m} min"
            m > 0 -> "${m} min"
            else -> "0 min"
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx1 = width * 0.32f          // barra de Ü
        val cx2 = width * 0.68f          // barra de Tú
        val baseY = (height - pad - context.dp(30)).toFloat()
        val topLimit = (pad + context.dp(96)).toFloat()
        val usable = baseY - topLimit
        val max = maxOf(uMs, userMs, 1L).toFloat()

        canvas.drawLine(pad.toFloat(), baseY, (width - pad).toFloat(), baseY, axis)
        canvas.drawText("versus", width / 2f, topLimit - context.dp(38).toFloat(), versus)

        // Ü resaltada (acento pleno); Tú atenuada. Todo en la paleta blanco/negro del tema.
        data class Bar(val cx: Float, val ms: Long, val color: Int, val name: String)
        val bars = listOf(
            Bar(cx1, uMs, Palette.accent, "Ü"),
            Bar(cx2, userMs, Palette.textDim, "Tú"),
        )
        for (b in bars) {
            val h = if (b.ms <= 0L) 0f else maxOf(context.dp(6).toFloat(), usable * (b.ms / max))
            val left = b.cx - barW / 2f
            val right = b.cx + barW / 2f
            val top = baseY - h
            val r = context.dp(14).toFloat()
            if (h > 0f) {
                barPaint.shader = LinearGradient(
                    0f, top, 0f, baseY,
                    b.color, (b.color and 0x00FFFFFF) or (0x66 shl 24), Shader.TileMode.CLAMP)
                path.reset()
                path.addRoundRect(
                    RectF(left, top, right, baseY),
                    floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
                canvas.drawPath(path, barPaint)
                barPaint.shader = null
            }
            canvas.drawText(fmt(b.ms), b.cx, top - context.dp(12), valP)
            canvas.drawText(b.name, b.cx, baseY + context.dp(23), nameP)
        }
    }
}
