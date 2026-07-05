package com.zevcorp.graph.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View

/**
 * La carita del asistente de Graph (assistant-runtime.js del repo Graph), en Canvas nativo:
 * cejas curvas + ojos de línea vertical + sonrisa bezier, trazo blanco sobre vidrio oscuro.
 * Coordenadas en el sistema original del SVG (viewBox -75..75), escaladas al tamaño de la vista.
 */
class FaceView(context: Context) : View(context) {

    var thinking = false
        set(value) { field = value; invalidate() }

    /* Parpadeo: señal de cambio de vía de ejecución (1 = consciente, 2 = subconsciente). */
    private var blinkClosed = 0f // 0 = ojos abiertos, 1 = cerrados
    private var blinkAnim: android.animation.ValueAnimator? = null

    /** Parpadea `times` veces seguidas cerrando y abriendo los ojos. */
    fun blink(times: Int) {
        if (times <= 0) return
        blinkAnim?.cancel()
        blinkAnim = android.animation.ValueAnimator.ofFloat(0f, times.toFloat()).apply {
            duration = 340L * times
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val phase = t - kotlin.math.floor(t) // 0..1 dentro de cada parpadeo
                blinkClosed = 1f - kotlin.math.abs(phase * 2f - 1f) // triángulo: abre→cierra→abre
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    blinkClosed = 0f; invalidate()
                }
            })
            start()
        }
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#0B0E12") // líneas del rostro negras
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(28, 0, 0, 0)
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 150f // unidades del viewBox → px
        val cx = width / 2f
        val cy = height / 2f
        fun x(v: Float) = cx + v * s
        fun y(v: Float) = cy + v * s

        fill.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            Color.WHITE, Color.parseColor("#EEF2F6"), Shader.TileMode.CLAMP) // fill blanco
        canvas.drawCircle(cx, cy, minOf(width, height) / 2f - s, fill)
        border.strokeWidth = 1.5f * s
        canvas.drawCircle(cx, cy, minOf(width, height) / 2f - s, border)

        stroke.strokeWidth = 4f * s
        canvas.save()
        canvas.rotate(-2f, cx, cy)

        // presets smile / thinking de FACE_PRESETS
        val browL = if (thinking) -1f else 2f
        val browR = if (thinking) 4f else 2.5f
        val curveL = if (thinking) 0.1f else 0.3f
        val curveR = if (thinking) 0.5f else 0.4f
        val eyeOpen = if (thinking) 0.75f else 0.85f
        val squint = if (thinking) 0.2f else 0.15f
        val mouthCurve = 0.7f
        val mouthWidth = 34f * (if (thinking) 0.95f else 1.1f)
        val cornerL = if (thinking) 0.2f else 0.3f
        val cornerR = if (thinking) 0.1f else 0.5f

        // cejas: bezier cuadrática sobre cada ojo
        for ((bx, h, c) in listOf(Triple(-30f, browL, curveL), Triple(30f, browR, curveR))) {
            path.reset()
            path.moveTo(x(bx - 10f), y(-34f - h))
            path.quadTo(x(bx), y(-34f - h - c * 15f), x(bx + 10f), y(-34f - h))
            canvas.drawPath(path, stroke)
        }
        // ojos: líneas verticales (el parpadeo los cierra casi del todo)
        val eyeLen = 25f * eyeOpen * (1f - squint * 0.4f) * (1f - blinkClosed * 0.92f)
        for (ex in listOf(-30f, 30f)) {
            canvas.drawLine(x(ex), y(-14f - eyeLen / 2f), x(ex), y(-14f + eyeLen / 2f), stroke)
        }
        // boca: bezier cúbica asimétrica (sonrisa)
        val base = mouthCurve * 15f
        val leftY = 34f - base - cornerL * 8f
        val rightY = 34f - base - cornerR * 8f
        val midY = 34f - mouthCurve * 12f
        val shift = (cornerR - cornerL) * 10f
        val half = mouthWidth / 2f
        path.reset()
        path.moveTo(x(-half), y(leftY))
        path.cubicTo(x(-half * 0.3f + shift), y(midY), x(half * 0.3f + shift), y(midY), x(half), y(rightY))
        canvas.drawPath(path, stroke)

        canvas.restore()
    }
}
