package com.zevcorp.graph.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.ui.FloatingBubble
import com.zevcorp.graph.platform.GraphAccessibilityService
import com.zevcorp.graph.voice.Transcriber
import com.zevcorp.graph.voice.defaultTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El punto de entrada como ASISTENTE DEL SISTEMA (mantener oprimido el botón de encendido, igual
 * que Gemini). Muestra una barra tipo pastilla en la parte inferior — carita, campo de texto y
 * micrófono — sobre un fondo atenuado. Mismo comportamiento general: la voz pasa por el pipeline
 * de Graph (transcripción → destilador de intención) y todo termina en el Execution Engine.
 * Para elegirlo: Ajustes → Apps predeterminadas → App de asistente digital → Graph.
 */
class AssistActivity : Activity() {

    private val app get() = GraphApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var transcriber: Transcriber? = null
    private lateinit var hint: TextView
    private lateinit var face: FaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setOnClickListener { finish() } // tocar fuera de la barra la cierra

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(24))
            isClickable = true // no propagar el cierre al tocar la barra
        }

        hint = caption("🎤 te escucho…").apply { setPadding(dp(20), 0, dp(20), dp(8)) }
        sheet.addView(hint)

        // La pastilla: carita + campo + micrófono (la barra de Gemini, con la cara de Graph).
        val pill = row().apply {
            background = rounded(Palette.bg, dp(30).toFloat(), Palette.cardBorder)
            setPadding(dp(12), dp(10), dp(10), dp(10))
            elevation = 24f
        }
        face = FaceView(this)
        pill.addView(face, LinearLayout.LayoutParams(dp(38), dp(38)))

        val input = EditText(this).apply {
            hint = "Pídeme algo…"
            setHintTextColor(Palette.textDim)
            setTextColor(Palette.text)
            textSize = 15f
            background = null
            setPadding(dp(12), dp(6), dp(12), dp(6))
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        input.setOnEditorActionListener { _, _, _ -> dispatch(input.text.toString(), distill = false); true }
        pill.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        val mic = TextView(this).apply {
            text = "🎤"
            textSize = 19f
            gravity = Gravity.CENTER
            val d = dp(46)
            minWidth = d; minHeight = d
            background = rounded(Palette.accent, dp(23).toFloat())
            setOnClickListener { transcriber?.stop() ?: startListening() } // corta y procesa, o reabre
        }
        pill.addView(mic)
        sheet.addView(pill)

        root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        startListening() // como Gemini: al invocarlo ya está escuchando
    }

    /** Un segmento de escucha; el texto se destila (es voz cruda) y se despacha al motor. */
    private fun startListening() {
        if (transcriber != null) return
        face.thinking = true
        hint.text = "🎤 te escucho… (toca el micrófono al terminar, o escribe)"
        val t = defaultTranscriber(this)
        transcriber = t
        scope.launch {
            val transcript = runCatching { withContext(Dispatchers.Default) { t.listen() } }.getOrElse { "" }
            transcriber = null
            face.thinking = false
            if (transcript.isBlank()) {
                hint.text = "No te escuché — habla de nuevo tocando 🎤 o escribe 👇"
                return@launch
            }
            hint.text = "✨ entendiendo…"
            LogBus.log("assist", "transcripción: \"${transcript.take(140)}\"")
            dispatch(transcript, distill = true)
        }
    }

    /** Cierra la barra y manda la orden al Execution Engine (la pantalla queda libre para actuar). */
    private fun dispatch(text: String, distill: Boolean) {
        val raw = text.trim()
        if (raw.isBlank()) return
        transcriber?.stop()
        finish()
        val bubble: FloatingBubble? = (app.ui as? GraphAccessibilityService)?.bubble
        app.scope.launch {
            val prompt = if (distill) {
                runCatching { app.intentDistiller.distill(raw) }.getOrNull() ?: return@launch Unit.also {
                    LogBus.log("assist", "sin intención accionable")
                    bubble?.narrate("Te oí, pero no había nada que hacer 🙂")
                }
            } else raw
            LogBus.log("assist", "▶ \"${prompt.take(120)}\"")
            runCatching { app.run(prompt, bubble) }
                .onFailure { LogBus.log("assist", "error: ${it.message}") }
        }
    }

    override fun onDestroy() {
        transcriber?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
