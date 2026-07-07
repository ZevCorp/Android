package com.zevcorp.graph.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pantalla de diagnóstico: cuando algo revienta (arranque incluido), en vez de que la app se cierre
 * en silencio, se muestra AQUÍ la traza del error (y se copia al portapapeles) para poder compartirla.
 * Corre en su propio proceso (:crash) para poder mostrarse aunque el proceso principal esté muriendo.
 */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "(sin traza)"
        val body = "Ü se cerró por un error.\nCopia esto y envíamelo 🙏\n\n$trace"
        val tv = TextView(this).apply {
            text = body
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF14181F.toInt())
            addView(tv)
        }
        setContentView(scroll)
        runCatching {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("graph-crash", trace))
        }
    }

    companion object {
        const val EXTRA_TRACE = "trace"
    }
}
