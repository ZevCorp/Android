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

/** Tokens de diseño de Graph (paleta del icon.svg / asistente del repo Graph). */
object Palette {
    val bg = Color.parseColor("#0D141C")
    val card = Color.parseColor("#16202C")
    val cardBorder = Color.parseColor("#243244")
    val accent = Color.parseColor("#2F8CFF")
    val accentDark = Color.parseColor("#1E5FB4")
    val text = Color.parseColor("#E9EEF5")
    val textDim = Color.parseColor("#8FA3B8")
    val danger = Color.parseColor("#E5534B")
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
    setTextColor(Palette.accent)
    typeface = Typeface.DEFAULT_BOLD
    background = rounded(Color.argb(36, 47, 140, 255), dp(20).toFloat())
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

fun Context.button(text: String, primary: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) Color.WHITE else Palette.text)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        val base = rounded(if (primary) Palette.accent else Palette.card, dp(14).toFloat(),
            if (primary) 0 else Palette.cardBorder)
        background = RippleDrawable(ColorStateList.valueOf(Palette.accentDark), base, null)
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
