package com.zevcorp.graph.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Los tres modos de la app y de la cara. Todo en blanco/negro puro (nunca azul). */
enum class ThemeMode { LIGHT, DARK, TRANSPARENT }

/**
 * Tokens de diseño de Ü. Tres modos que se alternan tocando la carita del cuadro de diálogo:
 * CLARO (cara blanca · fondo blanco), OSCURO (cara oscura · fondo negro) y TRANSPARENCIA (relleno de
 * la cara transparente, líneas negras). El acento es siempre el OPUESTO del fondo, de modo que el
 * contraste es siempre blanco↔negro. Nunca hay azul.
 */
object Palette {
    @Volatile var mode: ThemeMode = ThemeMode.LIGHT

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private val dark get() = mode == ThemeMode.DARK

    /** Fondo de ventanas, tarjetas y cuadros de diálogo. */
    val bg: Int get() = when (mode) {
        ThemeMode.LIGHT -> WHITE
        ThemeMode.DARK -> BLACK
        ThemeMode.TRANSPARENT -> 0xF2FFFFFF.toInt() // blanco casi opaco
    }
    val card: Int get() = when (mode) {
        ThemeMode.LIGHT -> WHITE
        ThemeMode.DARK -> 0xFF0F0F0F.toInt()
        ThemeMode.TRANSPARENT -> 0xF2FFFFFF.toInt()
    }
    val cardBorder: Int get() = if (dark) 0xFF2A2A2A.toInt() else 0xFFDADADA.toInt()
    /** Acento = opuesto del fondo (negro sobre claro, blanco sobre oscuro). */
    val accent: Int get() = if (dark) WHITE else BLACK
    val accentDark: Int get() = if (dark) 0xFFCCCCCC.toInt() else 0xFF2B2B2B.toInt()
    val text: Int get() = if (dark) 0xFFF5F5F5.toInt() else 0xFF0A0A0A.toInt()
    val textDim: Int get() = if (dark) 0xFF9AA0A6.toInt() else 0xFF6B6B6B.toInt()
    val danger = 0xFFE5534B.toInt()
    /** Verde semántico: marca los elementos de UI que el sistema YA aprendió (visualización del 🎓). */
    val learned = 0xFF2FBF71.toInt()

    /* ---------- La cara (FaceView) ---------- */
    val faceLine: Int get() = if (dark) WHITE else BLACK
    val faceFillTop: Int get() = if (dark) 0xFF1A1A1A.toInt() else WHITE
    val faceFillBottom: Int get() = if (dark) BLACK else WHITE
    val faceBorder: Int get() = if (dark) 0x33FFFFFF else 0x1F000000
    val faceTransparent: Boolean get() = mode == ThemeMode.TRANSPARENT

    /** Siguiente modo del ciclo claro → oscuro → transparencia → claro. */
    fun next(): ThemeMode = when (mode) {
        ThemeMode.LIGHT -> ThemeMode.DARK
        ThemeMode.DARK -> ThemeMode.TRANSPARENT
        ThemeMode.TRANSPARENT -> ThemeMode.LIGHT
    }
    fun label(): String = when (mode) {
        ThemeMode.LIGHT -> "claro"
        ThemeMode.DARK -> "oscuro"
        ThemeMode.TRANSPARENT -> "transparente"
    }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radiusPx: Float, borderColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (borderColor != 0) setStroke(2, borderColor)
}

fun Context.pill(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 11f
    setTextColor(Palette.bg)
    typeface = Typeface.DEFAULT_BOLD
    background = rounded(Palette.accent, dp(20).toFloat())
    setPadding(dp(10), dp(4), dp(10), dp(4))
}

fun Context.title(text: String, size: Float = 16f): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(Palette.text)
    typeface = Typeface.DEFAULT_BOLD
}

fun Context.caption(text: String): TextView = TextView(this).apply {
    this.text = text
    textSize = 12f
    setTextColor(Palette.textDim)
}

/** Texto de cuerpo legible: buen tamaño, color pleno y interlineado cómodo (para conversación). */
fun Context.body(text: String, size: Float = 16f): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(Palette.text)
    setLineSpacing(dp(3).toFloat(), 1f)
}

fun Context.button(text: String, primary: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        // primary ahora es fill BLANCO con texto oscuro; secundario tarjeta con borde
        setTextColor(if (primary) Palette.bg else Palette.text)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        val base = rounded(if (primary) Palette.accent else Palette.card, dp(14).toFloat(),
            if (primary) 0 else Palette.cardBorder)
        val ripple = if (primary) Color.argb(48, 128, 128, 128) else Palette.accentDark
        background = RippleDrawable(ColorStateList.valueOf(ripple), base, null)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onClick() }
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(Palette.card, dp(18).toFloat(), Palette.cardBorder)
    setPadding(dp(16), dp(14), dp(16), dp(14))
}

fun LinearLayout.gap(px: Int) = addView(android.view.View(context), LinearLayout.LayoutParams(1, px))

fun Context.row(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
}
