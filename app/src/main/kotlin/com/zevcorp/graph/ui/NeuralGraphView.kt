package com.zevcorp.graph.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import graph.core.domain.Step
import graph.core.domain.StepStatus
import kotlin.math.sin

/**
 * La "red neuronal" de un workflow: una neurona por step, unidas en cadena sinuosa.
 * 🟢 CONFIRMED aprendido por árbol de UI · 🔴 LLM lo hace Gemini · 🟡 DRAFT por consolidar.
 */
class NeuralGraphView(context: Context, private val steps: List<Step>) : View(context) {

    private val rowH = context.dp(72)
    private val pad = context.dp(28)

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = Color.parseColor("#33415A")
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.dp(11).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textSize = context.dp(13).toFloat()
        typeface = Typeface.DEFAULT_BOLD
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.textDim
        textSize = context.dp(11).toFloat()
    }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            pad * 2 + maxOf(1, steps.size) * rowH,
        )
    }

    private fun colorOf(step: Step) = when (step.status) {
        StepStatus.CONFIRMED -> Color.parseColor("#2FBF71") // verde: aprendido (árbol de UI)
        StepStatus.LLM -> Palette.danger                    // rojo: lo ejecuta Gemini
        StepStatus.DRAFT -> Color.parseColor("#D9A03F")     // ámbar: por consolidar
    }

    private fun statusText(step: Step) = when (step.status) {
        StepStatus.CONFIRMED -> "aprendido · árbol de UI"
        StepStatus.LLM -> "lo hace Gemini 3.5 Flash"
        StepStatus.DRAFT -> "por consolidar en learning"
    }

    override fun onDraw(canvas: Canvas) {
        if (steps.isEmpty()) {
            canvas.drawText("Sin steps todavía", pad.toFloat(), pad * 2f, sub)
            return
        }
        val cx = width * 0.22f
        val amp = width * 0.10f
        fun px(i: Int) = cx + amp * sin(i * 1.15).toFloat()
        fun py(i: Int) = (pad + i * rowH + rowH / 2).toFloat()

        // sinapsis
        for (i in 0 until steps.size - 1) {
            val x1 = px(i); val y1 = py(i)
            val x2 = px(i + 1); val y2 = py(i + 1)
            edge.color = if (steps[i].status == StepStatus.CONFIRMED && steps[i + 1].status == StepStatus.CONFIRMED)
                Color.parseColor("#2FBF71") else Color.parseColor("#33415A")
            path.reset()
            path.moveTo(x1, y1)
            path.cubicTo(x1, y1 + rowH * 0.4f, x2, y2 - rowH * 0.4f, x2, y2)
            canvas.drawPath(path, edge)
        }
        // neuronas
        val rGlow = context.dp(19).toFloat()
        val rCore = context.dp(12).toFloat()
        steps.forEachIndexed { i, step ->
            val x = px(i); val y = py(i)
            val c = colorOf(step)
            glow.color = c and 0x00FFFFFF or (0x38 shl 24)
            core.color = c
            canvas.drawCircle(x, y, rGlow, glow)
            canvas.drawCircle(x, y, rCore, core)
            canvas.drawText("${step.order}", x, y + num.textSize * 0.36f, num)
            val tx = x + rGlow + context.dp(12)
            val title = "${step.action} ${step.label.ifBlank { step.selector.short() }}".take(30)
            canvas.drawText(title, tx, y - context.dp(2), label)
            canvas.drawText(statusText(step), tx, y + context.dp(14), sub)
        }
    }
}
