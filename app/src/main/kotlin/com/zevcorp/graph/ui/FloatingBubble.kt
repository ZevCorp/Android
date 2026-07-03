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
import graph.core.domain.Answer
import graph.core.domain.Lesson
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/**
 * La burbuja flotante de Graph: la carita del asistente siempre encima de cualquier app,
 * dibujada como overlay del servicio de accesibilidad (TYPE_ACCESSIBILITY_OVERLAY, sin
 * permisos extra). Arrastrable; al tocarla abre el panel con las tres etapas. Durante el
 * Learning desaparece (para no salir en las capturas del agente) y reaparece para preguntar;
 * en modo demostración un toque la convierte en el botón de "terminé".
 */
class FloatingBubble(private val service: AccessibilityService) : UserChannel, Voice {

    private val app get() = GraphApp.instance
    private val wm = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var bubble: FaceView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: View? = null
    private var demoEnd: CompletableDeferred<Unit>? = null
    private var demoBadge: TextView? = null
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
        bubble.setOnClickListener {
            when {
                demoEnd != null -> { demoBadge?.let { runCatching { wm.removeView(it) } }; demoBadge = null; demoEnd?.complete(Unit); demoEnd = null }
                panel == null -> openPanel()
                else -> closePanel()
            }
        }
        wm.addView(bubble, bubbleParams)
    }

    /** Arrastre fluido con inercia: un flick corto la lanza a la esquina; siempre "aterriza" pegada al borde. */
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
                        vt?.addMovement(e); vt?.computeCurrentVelocity(1000) // px/s
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
        val destX = if (projX + size / 2 < m.widthPixels / 2) 0 else maxX // pega al borde según hacia dónde va
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
        demoBadge?.let { runCatching { wm.removeView(it) } }
        speech?.let { runCatching { wm.removeView(it) } }
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

    /* ---------- Modo acompañante: la carita vuela hacia donde se va a hacer clic ---------- */

    /**
     * Vuela suave hacia el objetivo con curva de velocidad ascendente (ease-in:
     * arranca lento y llega rápido). Se queda flotando justo encima del punto.
     * En este modo la burbuja es pass-through: los taps del agente la atraviesan.
     */
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
                interpolator = AccelerateInterpolator(1.7f) // curva de velocidad: despacio → rápido
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    bubbleParams.x = (startX + (destX - startX) * f).toInt()
                    bubbleParams.y = (startY + (destY - startY) * f).toInt()
                    runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
                start()
            }
        }
        delay(60) // un respiro sobre el objetivo antes del clic
    }

    /** Se oculta justo antes de cada captura de pantalla del agente. */
    fun hideForShot() {
        bubble.visibility = View.INVISIBLE
    }

    /** on=true durante Learning/subconsciente (pass-through); on=false restaura la burbuja normal. */
    fun companion(on: Boolean) {
        scope.launch {
            setPassThrough(on)
            if (!on) {
                bubble.visibility = View.VISIBLE
                bubble.thinking = false
            }
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

    /* ---------- Panel principal ---------- */

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
        titles.addView(c.caption(if (app.ui != null) "Pídeme algo o enséñame ✏️" else "Activa accesibilidad"))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(iconButton("✕") { closePanel() })
        body.addView(header)
        body.gap(c.dp(10))

        // Accesos rápidos: lecciones y workflows recientes
        val lessons = app.lessons.list().takeLast(3).reversed()
        val workflows = app.workflows.list().takeLast(3).reversed()
        if (workflows.isNotEmpty() || lessons.isNotEmpty()) {
            workflows.forEach { w ->
                body.addView(c.button("⚡ ${w.name.take(38)} · ${w.learnedPct()}%") {
                    closePanel()
                    scope.launch { toast(withContext(Dispatchers.Default) { app.runWorkflow(w.id, emptyMap()) }) }
                })
                body.gap(c.dp(6))
            }
            lessons.forEach { lesson ->
                body.addView(c.button("🎓 ${lesson.goal.take(38)}") { closePanel(); learn(lesson) })
                body.gap(c.dp(6))
            }
            body.gap(c.dp(4))
        }

        // Barra de chat: lápiz (enseñar) · input · micrófono · enviar
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
        bar.addView(iconButton("✏️") {
            if (!app.teachingActive) {
                closePanel()
                c.startActivity(Intent(c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("action", "teach"))
            } else {
                closePanel(); app.teachingActive = false; toast("Analizando el tutorial…")
                scope.launch {
                    runCatching { withContext(Dispatchers.Default) { app.teaching.stopAndAnalyze() } }
                        .onSuccess { toast("Lección aprendida: ${it.goal}") }
                        .onFailure { toast("Error al analizar: ${it.message}") }
                }
            }
        })
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(iconButton("🎤") {
            listen { heard -> if (heard != null) submit(heard) else toast("No te escuché") }
        })
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(iconButton("➤", primary = true) { submit(input.text.toString()) })
        body.addView(bar)

        val scroll = ScrollView(c).apply { addView(body); elevation = 16f }
        val params = overlayParams(c.dp(316), -2, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams.x - c.dp(322)).coerceAtLeast(c.dp(4))
            y = bubbleParams.y.coerceAtMost(service.resources.displayMetrics.heightPixels - c.dp(420))
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
            background = rounded(if (primary) android.graphics.Color.WHITE else Palette.card,
                service.dp(21).toFloat(), if (primary) 0 else Palette.cardBorder)
            setOnClickListener { onClick() }
        }

    /** Ejecuta un prompt libre con el motor de ejecución (Gemini computer use + aprendizaje por workflow). */
    private fun runPrompt(prompt: String) {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Graph"); return }
        narrate("¡Vamos! $prompt")
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { app.runPrompt(prompt, this@FloatingBubble) } }
                .onSuccess { toast("Hecho · ${it.steps.size} steps (workflow ${it.id})") }
                .onFailure { toast("Error: ${it.message}") }
        }
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    /* ---------- Learning desde la burbuja ---------- */

    private fun learn(lesson: Lesson) {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Graph"); return }
        toast("Aprendiendo: ${lesson.goal.take(40)}")
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { app.runLearning(lesson, this@FloatingBubble) } }
                .onSuccess { toast("Workflow aprendido: ${it.name.take(32)} (${it.steps.size} steps)") }
                .onFailure { toast("Error en learning: ${it.message}") }
        }
    }

    override suspend fun ask(question: String): Answer = withContext(Dispatchers.Main) {
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
            fun finish(answer: Answer) {
                runCatching { wm.removeView(window) }
                bubble.thinking = false
                if (!answer.demo) bubble.visibility = View.GONE
                cont.resume(answer)
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
            body.addView(c.button("Responder", primary = true) { finish(Answer(input.text.toString())) })
            body.gap(c.dp(6))
            val actions = c.row()
            actions.addView(c.button("🎤 Voz") {
                listen { heard -> if (heard != null) finish(Answer(heard)) else toast("No te escuché, intenta de nuevo") }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
            actions.addView(c.button("👋 Te lo muestro") {
                demoEnd = CompletableDeferred()
                setPassThrough(false) // la burbuja vuelve a ser tocable: es el botón de "terminé"
                showDemoBadge()
                toast("Hazlo tú; cuando termines toca la burbuja ✅")
                finish(Answer(demo = true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(actions)

            window = ScrollView(c).apply { addView(body); elevation = 20f }
            val params = overlayParams(c.dp(310), -2, focusable = true).apply {
                gravity = Gravity.CENTER
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
            wm.addView(window, params)
            cont.invokeOnCancellation { runCatching { wm.removeView(window) } }
        }
    }

    override suspend fun awaitDemoEnd() {
        demoEnd?.await()
        demoEnd = null
        withContext(Dispatchers.Main) { setPassThrough(true) } // el agente retoma el control
    }

    private fun showDemoBadge() {
        val badge = TextView(service).apply {
            text = "✅"
            textSize = 14f
            background = rounded(Palette.accent, service.dp(20).toFloat())
            setPadding(service.dp(6), service.dp(2), service.dp(6), service.dp(2))
        }
        val params = overlayParams(-2, -2, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x + service.dp(36)
            y = bubbleParams.y - service.dp(8)
        }
        wm.addView(badge, params)
        demoBadge = badge
    }

    /* ---------- Voz sin Activity: SpeechRecognizer directo en el servicio ---------- */

    private fun listen(onResult: (String?) -> Unit) {
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
