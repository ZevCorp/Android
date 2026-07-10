package com.zevcorp.graph.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.GraphAccessibilityService
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.voice.SystemTranscriber
import com.zevcorp.graph.voice.Transcriber
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ASISTENTE DEL SISTEMA (mantener oprimido el botón de encendido). Hoja conversacional estilo
 * Gemini: la carita, un hilo de mensajes legibles, escucha en vivo con transcripción parcial, y una
 * barra de entrada con micrófono y enviar. Distingue conversación de acción: si preguntas algo
 * responde ahí mismo; si pides una tarea, confirma y se la pasa al motor de ejecución (que actúa
 * con la burbuja narrando). Elegir en Ajustes → Apps predeterminadas → App de asistente digital.
 */
class AssistActivity : Activity() {

    private val app get() = GraphApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var transcriber: Transcriber? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val openAiTts by lazy { com.zevcorp.graph.voice.OpenAiTts(this) }

    private lateinit var sheet: LinearLayout
    private lateinit var convo: LinearLayout
    private lateinit var convoScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var micChip: FrameLayout
    private lateinit var face: FaceView

    private var partialBubble: TextView? = null
    @Volatile private var listening = false
    @Volatile private var processing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setDecorFitsSystemWindows(false) // recibo los insets del teclado y los manejo yo
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) { tts?.language = Locale.getDefault(); ttsReady = true } }

        val root = FrameLayout(this).apply { setOnClickListener { dismiss() } }

        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBackground()
            setPadding(dp(18), dp(10), dp(18), dp(16))
            elevation = dp(24).toFloat()
            isClickable = true // absorbe toques: no cierra al tocar la hoja
        }

        // Asa superior
        sheet.addView(View(this).apply {
            background = rounded(Palette.cardBorder, dp(3).toFloat())
        }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })

        // Encabezado: carita + nombre + cerrar
        val header = row()
        face = FaceView(this)
        header.addView(face, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(title("Ü", 18f).apply { setPadding(dp(12), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconChip(Icon.CLOSE, sizeDp = 38) { dismiss() })
        sheet.addView(header)
        sheet.gap(dp(10))

        // Hilo de conversación (altura acotada; crece con el contenido)
        convo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        convoScroll = BoundedScrollView(this, resources.displayMetrics.heightPixels * 46 / 100).apply {
            addView(convo)
            isVerticalScrollBarEnabled = false
        }
        sheet.addView(convoScroll, LinearLayout.LayoutParams(-1, -2))
        sheet.gap(dp(8))

        // Estado (escuchando / pensando)
        status = caption("").apply { setPadding(dp(6), 0, dp(6), dp(6)) }
        sheet.addView(status)

        // Barra de entrada: campo + micrófono + enviar
        val bar = row().apply {
            background = rounded(Palette.card, dp(26).toFloat(), Palette.cardBorder)
            setPadding(dp(16), dp(6), dp(6), dp(6))
        }
        input = EditText(this).apply {
            hint = "Escribe o habla…"
            setHintTextColor(Palette.textDim)
            setTextColor(Palette.text)
            textSize = 16f
            background = null
            setPadding(0, dp(8), dp(8), dp(8))
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, _, _ -> submitTyped(); true }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        micChip = iconChip(Icon.MIC, sizeDp = 46, primary = true) { toggleMic() }
        bar.addView(micChip)
        bar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        bar.addView(iconChip(Icon.SEND, sizeDp = 46) { submitTyped() })
        sheet.addView(bar)

        root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)

        // El teclado empuja la hoja hacia arriba (sin cruzarse).
        sheet.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            v.setPadding(dp(18), dp(10), dp(18), dp(16) + maxOf(ime, nav))
            insets
        }

        addBubble("Hola, ¿en qué te ayudo?", mine = false)
        startListening() // como Gemini: al abrir ya está escuchando
    }

    /* ---------- Escucha ---------- */

    private fun toggleMic() {
        if (listening) transcriber?.stop() else startListening()
    }

    private fun startListening() {
        if (listening || processing) return
        listening = true
        face.thinking = true
        setStatus("Escuchando…")
        // Mismo motor que el micrófono que SÍ funciona en la burbuja: SpeechRecognizer del sistema
        // (sin foreground service, que en una Activity de asistente puede bloquear la captura).
        val t = SystemTranscriber(this)
        transcriber = t
        t.onPartial = { partial -> runOnUiThread { showPartial(partial) } }
        t.onLevel = { lvl -> runOnUiThread { micChip.scaleX = 1f + lvl * 0.14f; micChip.scaleY = 1f + lvl * 0.14f } }
        scope.launch {
            val transcript = runCatching { t.listen() }.getOrElse { "" }
            listening = false
            transcriber = null
            face.thinking = false
            micChip.scaleX = 1f; micChip.scaleY = 1f
            if (transcript.isBlank()) {
                partialBubble?.let { convo.removeView(it.parent as View); partialBubble = null }
                setStatus("No te escuché — toca el micrófono o escribe")
                return@launch
            }
            finalizePartial(transcript)
            process(transcript)
        }
    }

    /** Muestra/actualiza en vivo lo que se va entendiendo como una burbuja del usuario. */
    private fun showPartial(text: String) {
        val tv = partialBubble ?: addBubble(text, mine = true).also { partialBubble = it }
        tv.text = text
        scrollToBottom()
    }

    private fun finalizePartial(text: String) {
        val tv = partialBubble
        if (tv != null) { tv.text = text; partialBubble = null } else addBubble(text, mine = true)
    }

    /* ---------- Entrada por texto ---------- */

    private fun submitTyped() {
        val text = input.text.toString().trim()
        if (text.isBlank() || processing) return
        input.setText("")
        transcriber?.stop()
        addBubble(text, mine = true)
        process(text)
    }

    /* ---------- Enrutado: conversación vs acción ---------- */

    private fun process(text: String) {
        processing = true
        setStatus("Pensando…")
        val thinking = addThinking()
        scope.launch {
            val intent = withContext(Dispatchers.IO) {
                runCatching { app.intentDistiller.distill(text) }.getOrNull()
            }
            convo.removeView(thinking)
            processing = false
            if (intent != null) actOn(intent) else converse(text)
        }
    }

    /** Es una tarea del teléfono: confirma y entrega al motor (la burbuja narra y actúa). */
    private fun actOn(intent: String) {
        addBubble("Voy a hacerlo.", mine = false)
        LogBus.log("assist", "▶ acción: \"${intent.take(120)}\"")
        val bubble = (app.ui as? GraphAccessibilityService)?.bubble
        scope.launch {
            delay(650) // deja leer la confirmación
            dismiss()
            app.scope.launch {
                runCatching { app.run(intent, bubble) }.onFailure { LogBus.log("assist", "error: ${it.message}") }
            }
        }
    }

    /**
     * No es una acción: responde conversando, pero CONTINUANDO el hilo compartido (app.run) — la misma
     * ventana de contexto que la burbuja y las demás activaciones, para que haya continuidad. La voz la
     * pone la carita (app.run ya narra), por eso aquí no se repite el TTS.
     */
    private fun converse(text: String) {
        setStatus("")
        val thinking = addThinking()
        scope.launch {
            val bubble = (app.ui as? GraphAccessibilityService)?.bubble
            val reply = withContext(Dispatchers.IO) {
                runCatching { app.run(text, bubble) }.getOrElse { "" }
            }.ifBlank { "Puedo abrir apps, poner música, mandar mensajes y ayudarte con lo que necesites en el teléfono." }
            convo.removeView(thinking)
            addBubble(reply, mine = false)
            setStatus("Toca el micrófono o escribe para seguir")
        }
    }

    /* ---------- Burbujas ---------- */

    private fun addBubble(text: String, mine: Boolean): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(if (mine) Palette.bg else Palette.text)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            maxWidth = resources.displayMetrics.widthPixels * 78 / 100
            background = rounded(if (mine) Palette.accent else Palette.card,
                dp(20).toFloat(), if (mine) 0 else Palette.cardBorder)
        }
        val rowLp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            addView(tv, LinearLayout.LayoutParams(-2, -2))
        }
        convo.addView(holder, rowLp)
        scrollToBottom()
        return tv
    }

    /** Burbuja de "escribiendo" con tres puntos animados. */
    private fun addThinking(): View {
        val dots = TextView(this).apply {
            text = "•  •  •"
            textSize = 16f
            setTextColor(Palette.textDim)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = rounded(Palette.card, dp(20).toFloat(), Palette.cardBorder)
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START
            addView(dots, LinearLayout.LayoutParams(-2, -2))
        }
        convo.addView(holder, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        scrollToBottom()
        ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 700; repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                if (holder.parent == null) { cancel(); return@addUpdateListener }
                dots.alpha = it.animatedValue as Float
            }
            start()
        }
        return holder
    }

    private fun scrollToBottom() = convoScroll.post { convoScroll.fullScroll(View.FOCUS_DOWN) }

    private fun setStatus(text: String) { status.text = text; status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE }

    private fun speak(text: String) {
        val clean = text.filter { it.code in 32..0x2FFF }
        scope.launch {
            val spoke = runCatching { openAiTts.speak(clean) }.getOrDefault(false)
            if (!spoke && ttsReady) tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "assist")
        }
    }

    private fun dismiss() {
        transcriber?.stop()
        finish()
    }

    override fun onDestroy() {
        transcriber?.stop()
        tts?.shutdown()
        openAiTts.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun sheetBackground() = android.graphics.drawable.GradientDrawable().apply {
        setColor(Palette.bg)
        cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
        setStroke(dp(1), Palette.cardBorder)
    }

    /** ScrollView con altura máxima: el hilo crece hasta un tope y luego se desplaza. */
    private class BoundedScrollView(context: Context, private val maxH: Int) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }
}
