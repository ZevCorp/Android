package com.zevcorp.graph.ui

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zevcorp.graph.GraphApp
import graph.core.domain.Teacher
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/**
 * La burbuja flotante de Graph: la carita del asistente siempre encima de cualquier app, dibujada
 * como overlay del servicio de accesibilidad (TYPE_ACCESSIBILITY_OVERLAY, sin permisos extra).
 * Arrastrable; al tocarla abre el chat para pedirle algo (texto o voz). Durante la ejecución vuela
 * hacia donde actúa y muestra el botón de detener; si el asistente duda, pregunta aquí mismo.
 */
class FloatingBubble(private val service: AccessibilityService) : UserChannel, Voice, Teacher {

    private val app get() = GraphApp.instance
    private val wm = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var bubble: FaceView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: View? = null
    private var dragAnimator: ValueAnimator? = null

    private var speech: TextView? = null
    private var speechParams: WindowManager.LayoutParams? = null
    private var speechHide: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun show() {
        tts = TextToSpeech(service) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
        val size = service.dp(62)
        bubble = FaceView(service)
        bubbleParams = overlayParams(size, size, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = service.resources.displayMetrics.widthPixels - size - service.dp(8)
            y = service.resources.displayMetrics.heightPixels / 3
        }
        bubble.elevation = 12f
        attachDrag(size)
        bubble.setOnClickListener { if (panel == null) openPanel() else closePanel() }
        wm.addView(bubble, bubbleParams)
    }

    /** Arrastre fluido con inercia: un flick corto la lanza a la esquina; siempre "aterriza" al borde. */
    private fun attachDrag(size: Int) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        var tracker: VelocityTracker? = null
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragAnimator?.cancel()
                    downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; moved = false
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (moved || dx * dx + dy * dy > 120) {
                        moved = true
                        bubbleParams.x = startX + dx; bubbleParams.y = startY + dy
                        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) v.performClick()
                    else {
                        val vt = tracker
                        vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                        flingToEdge(size, vt?.xVelocity ?: 0f, vt?.yVelocity ?: 0f)
                    }
                    tracker?.recycle(); tracker = null
                }
            }
            true
        }
    }

    /** Proyecta la velocidad (momentum) y anima hasta la esquina más cercana con rebote sutil. */
    private fun flingToEdge(size: Int, vx: Float, vy: Float) {
        val m = service.resources.displayMetrics
        val maxX = m.widthPixels - size
        val maxY = m.heightPixels - size
        val projX = bubbleParams.x + vx * 0.12f
        val projY = bubbleParams.y + vy * 0.12f
        val destX = if (projX + size / 2 < m.widthPixels / 2) 0 else maxX
        val destY = projY.toInt().coerceIn(service.dp(24), maxY - service.dp(24))
        val speed = kotlin.math.hypot(vx.toDouble(), vy.toDouble()).toFloat()
        val dur = (260 + (kotlin.math.hypot((destX - bubbleParams.x).toDouble(), (destY - bubbleParams.y).toDouble()) / (0.6f + speed / 4000f))).toLong().coerceIn(200, 620)
        val fromX = bubbleParams.x; val fromY = bubbleParams.y
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                bubbleParams.x = (fromX + (destX - fromX) * f).toInt()
                bubbleParams.y = (fromY + (destY - fromY) * f).toInt()
                runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                moveSpeechToBubble()
            }
            start()
        }
    }

    fun destroy() {
        scope.cancel()
        dragAnimator?.cancel()
        tts?.shutdown()
        runCatching { wm.removeView(bubble) }
        panel?.let { runCatching { wm.removeView(it) } }
        speech?.let { runCatching { wm.removeView(it) } }
        stopButton?.let { runCatching { wm.removeView(it) } }
        learnBar?.let { runCatching { wm.removeView(it) } }
    }

    /* ---------- Voz y narración (globo de diálogo + TTS) ---------- */

    override fun narrate(text: String) = showSpeech(text, false)
    override fun speak(text: String) = showSpeech(text, true)

    private fun showSpeech(text: String, aloud: Boolean) {
        if (text.isBlank()) return
        scope.launch {
            val bubbleText = speech ?: TextView(service).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(service.dp(14), service.dp(10), service.dp(14), service.dp(10))
                background = rounded(Palette.accent, service.dp(18).toFloat())
                elevation = 16f
                maxWidth = service.dp(230)
            }.also {
                speech = it
                speechParams = overlayParams(-2, -2, focusable = false).apply { gravity = Gravity.TOP or Gravity.START }
                runCatching { wm.addView(it, speechParams) }
            }
            bubbleText.text = text
            bubbleText.visibility = View.VISIBLE
            moveSpeechToBubble()
            if (aloud && ttsReady) tts?.speak(text.filter { it.code in 32..0x2FFF }, TextToSpeech.QUEUE_FLUSH, null, "graph")
            speechHide?.cancel()
            speechHide = launch { delay(if (aloud) 5200 else 3400); speech?.visibility = View.GONE }
        }
    }

    private fun moveSpeechToBubble() {
        val view = speech ?: return
        val p = speechParams ?: return
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = view.measuredWidth.coerceAtLeast(service.dp(60))
        val onLeftHalf = bubbleParams.x < service.resources.displayMetrics.widthPixels / 2
        p.x = if (onLeftHalf) bubbleParams.x + service.dp(66) else (bubbleParams.x - w - service.dp(10)).coerceAtLeast(service.dp(4))
        p.y = (bubbleParams.y + service.dp(6)).coerceAtLeast(service.dp(4))
        runCatching { wm.updateViewLayout(view, p) }
    }

    /* ---------- Modo acompañante: la carita vuela hacia donde se va a actuar ---------- */

    /** Vuela suave hacia el objetivo (ease-in) y se queda flotando encima; pass-through para no estorbar. */
    suspend fun flyTo(targetX: Int, targetY: Int) = withContext(Dispatchers.Main) {
        setPassThrough(true)
        bubble.visibility = View.VISIBLE
        bubble.thinking = true
        val m = service.resources.displayMetrics
        val destX = (targetX - bubbleParams.width / 2).coerceIn(0, m.widthPixels - bubbleParams.width)
        val destY = (targetY - bubbleParams.height - service.dp(14)).coerceIn(0, m.heightPixels - bubbleParams.height)
        val startX = bubbleParams.x
        val startY = bubbleParams.y
        suspendCancellableCoroutine { cont ->
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480
                interpolator = AccelerateInterpolator(1.7f)
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    bubbleParams.x = (startX + (destX - startX) * f).toInt()
                    bubbleParams.y = (startY + (destY - startY) * f).toInt()
                    runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { if (cont.isActive) cont.resume(Unit) }
                })
                start()
            }
        }
        delay(60)
    }

    /** on=true durante la ejecución (pass-through, carita pensativa); on=false restaura. */
    fun companion(on: Boolean) {
        scope.launch {
            setPassThrough(on)
            bubble.visibility = View.VISIBLE
            bubble.thinking = on
        }
    }

    private var stopButton: TextView? = null

    /** Botón rojo ⏹ arriba-centro mientras el asistente ejecuta: un toque detiene. Ventana propia y tocable. */
    fun showStop(on: Boolean) {
        scope.launch {
            if (!on) {
                stopButton?.let { runCatching { wm.removeView(it) } }
                stopButton = null
                return@launch
            }
            if (stopButton != null) return@launch
            val button = TextView(service).apply {
                text = "⏹ detener"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(Palette.danger, service.dp(22).toFloat())
                setPadding(service.dp(16), service.dp(7), service.dp(16), service.dp(7))
                elevation = 22f
                setOnClickListener { app.stopExecution(); toast("Detenido ✋") }
            }
            val params = overlayParams(-2, -2, focusable = false).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = service.dp(8)
            }
            runCatching { wm.addView(button, params) }
            stopButton = button
        }
    }

    private fun setPassThrough(on: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val flags = if (on) bubbleParams.flags or flag else bubbleParams.flags and flag.inv()
        if (flags != bubbleParams.flags) {
            bubbleParams.flags = flags
            runCatching { wm.updateViewLayout(bubble, bubbleParams) }
        }
    }

    private fun overlayParams(w: Int, h: Int, focusable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        if (focusable) 0 else
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    /* ---------- Chat: pídele algo por texto o voz ---------- */

    private fun openPanel() {
        val c = service
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.bg, c.dp(22).toFloat(), Palette.cardBorder)
            setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(12))
        }

        val header = c.row()
        header.addView(FaceView(c), LinearLayout.LayoutParams(c.dp(38), c.dp(38)))
        val titles = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(10), 0, 0, 0) }
        titles.addView(c.title("Graph", 16f))
        titles.addView(c.caption(if (app.ui != null) "Pídeme algo 👇" else "Activa accesibilidad"))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton("✕") { closePanel() })
        body.addView(header)
        body.gap(c.dp(10))

        val input = EditText(c).apply {
            hint = "Pídeme algo…"
            setHintTextColor(Palette.textDim)
            setTextColor(Palette.text)
            textSize = 14f
            background = rounded(Palette.card, c.dp(22).toFloat(), Palette.cardBorder)
            setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(10))
            maxLines = 4
        }
        fun submit(text: String) {
            val prompt = text.trim()
            if (prompt.isBlank()) return
            closePanel()
            runPrompt(prompt)
        }
        input.setOnEditorActionListener { _, _, _ -> submit(input.text.toString()); true }

        val bar = c.row()
        bar.addView(iconButton("🎓") { startLearning() })
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(iconButton("🎤") {
            recognize { heard -> if (heard != null) submit(heard) else toast("No te escuché") }
        })
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(iconButton("➤", primary = true) { submit(input.text.toString()) })
        body.addView(bar)

        val scroll = ScrollView(c).apply { addView(body); elevation = 16f }
        val params = overlayParams(c.dp(316), -2, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams.x - c.dp(322)).coerceAtLeast(c.dp(4))
            y = bubbleParams.y.coerceAtMost(service.resources.displayMetrics.heightPixels - c.dp(240))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        wm.addView(scroll, params)
        panel = scroll
    }

    /** Botón-ícono circular (fill blanco si es primary). */
    private fun iconButton(glyph: String, primary: Boolean = false, onClick: () -> Unit) =
        TextView(service).apply {
            text = glyph
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Palette.bg else Palette.text)
            val d = service.dp(42)
            minWidth = d; minHeight = d
            setPadding(service.dp(10), service.dp(8), service.dp(10), service.dp(8))
            background = rounded(if (primary) Color.WHITE else Palette.card,
                service.dp(21).toFloat(), if (primary) 0 else Palette.cardBorder)
            setOnClickListener { onClick() }
        }

    /** Ejecuta un prompt con el motor mixto (Gemini computer-use + herramientas MCP). */
    private fun runPrompt(prompt: String) {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Graph"); return }
        narrate("¡Vamos! $prompt")
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { app.run(prompt, this@FloatingBubble) } }
                .onSuccess { toast(it) }
                .onFailure { toast(if (it is CancellationException) "Ejecución detenida ✋" else "Error: ${it.message}") }
        }
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    /* ---------- El asistente pregunta: respuesta por texto o voz ---------- */

    override suspend fun ask(question: String): String = withContext(Dispatchers.Main) {
        bubble.visibility = View.VISIBLE
        bubble.thinking = true
        suspendCancellableCoroutine { cont ->
            val c = service
            val body = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Palette.bg, c.dp(22).toFloat(), Palette.accent)
                setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16))
            }
            lateinit var window: View
            fun finish(answer: String) {
                runCatching { wm.removeView(window) }
                bubble.visibility = View.VISIBLE
                if (cont.isActive) cont.resume(answer)
            }
            val header = c.row()
            header.addView(FaceView(c).apply { thinking = true }, LinearLayout.LayoutParams(c.dp(36), c.dp(36)))
            header.addView(c.title("Tengo una duda", 15f).apply { setPadding(c.dp(10), 0, 0, 0) })
            body.addView(header)
            body.gap(c.dp(8))
            body.addView(TextView(c).apply { text = question; textSize = 14f; setTextColor(Palette.text) })
            body.gap(c.dp(10))
            val input = EditText(c).apply {
                hint = "Tu respuesta…"
                setHintTextColor(Palette.textDim)
                setTextColor(Palette.text)
                background = rounded(Palette.card, c.dp(12).toFloat(), Palette.cardBorder)
                setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
            }
            body.addView(input)
            body.gap(c.dp(10))
            body.addView(c.button("Responder", primary = true) { finish(input.text.toString()) })
            body.gap(c.dp(6))
            body.addView(c.button("🎤 Voz") {
                recognize { heard -> if (heard != null) finish(heard) else toast("No te escuché, intenta de nuevo") }
            })

            window = ScrollView(c).apply { addView(body); elevation = 20f }
            val params = overlayParams(c.dp(310), -2, focusable = true).apply {
                gravity = Gravity.CENTER
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
            wm.addView(window, params)
            cont.invokeOnCancellation { runCatching { wm.removeView(window) } }
        }
    }

    /* ---------- Enseñanza (Teacher): barra con 🎤 hablar y ✅ terminar ---------- */

    private var learnBar: View? = null
    private var listenCont: kotlinx.coroutines.CancellableContinuation<String?>? = null

    private fun startLearning() {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Graph"); return }
        closePanel()
        showLearnBar()
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { app.learn(this@FloatingBubble) } }
                .onSuccess { toast(it) }
                .onFailure { toast(if (it is CancellationException) "Enseñanza detenida ✋" else "Enseñanza: ${it.message}") }
            hideLearnBar()
        }
    }

    private fun showLearnBar() {
        if (learnBar != null) return
        val c = service
        val body = c.row().apply {
            background = rounded(Palette.bg, c.dp(24).toFloat(), Palette.accent)
            setPadding(c.dp(10), c.dp(8), c.dp(10), c.dp(8))
        }
        body.addView(c.caption("🎓 Enséñame: habla 🎤 · ✅ al terminar"),
            LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(iconButton("🎤") {
            recognize { heard -> listenCont?.let { it.resume(heard ?: ""); listenCont = null } }
        })
        body.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        body.addView(iconButton("✅", primary = true) { listenCont?.let { it.resume(null); listenCont = null } })
        val params = overlayParams(c.dp(300), -2, focusable = false).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = c.dp(28)
        }
        runCatching { wm.addView(body, params) }
        learnBar = body
    }

    private fun hideLearnBar() {
        scope.launch {
            learnBar?.let { runCatching { wm.removeView(it) } }
            learnBar = null
        }
    }

    override suspend fun listen(): String? = suspendCancellableCoroutine { cont ->
        listenCont = cont
        cont.invokeOnCancellation { listenCont = null }
    }

    override suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) {
        speak(question)
        suspendCancellableCoroutine { cont ->
            val c = service
            val body = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Palette.bg, c.dp(22).toFloat(), Palette.accent)
                setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16))
            }
            lateinit var window: View
            fun answer(yes: Boolean) { runCatching { wm.removeView(window) }; if (cont.isActive) cont.resume(yes) }
            body.addView(c.title(question, 15f))
            body.gap(c.dp(12))
            val row = c.row()
            row.addView(c.button("✅ Sí", primary = true) { answer(true) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(View(c), LinearLayout.LayoutParams(c.dp(8), 1))
            row.addView(c.button("✋ No") { answer(false) }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row)
            window = ScrollView(c).apply { addView(body); elevation = 22f }
            val params = overlayParams(c.dp(300), -2, focusable = true).apply { gravity = Gravity.CENTER }
            runCatching { wm.addView(window, params) }
            cont.invokeOnCancellation { runCatching { wm.removeView(window) } }
        }
    }

    /* ---------- Voz sin Activity: SpeechRecognizer directo en el servicio ---------- */

    private fun recognize(onResult: (String?) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(service)) return onResult(null)
        val recognizer = SpeechRecognizer.createSpeechRecognizer(service)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                onResult(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                recognizer.destroy()
            }
            override fun onError(error: Int) { onResult(null); recognizer.destroy() }
            override fun onReadyForSpeech(params: Bundle?) { toast("Te escucho…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }

    private fun toast(message: String) =
        Toast.makeText(service, message, Toast.LENGTH_LONG).show()
}
