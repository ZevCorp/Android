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
import com.zevcorp.graph.platform.GraphAccessibilityService
import com.zevcorp.graph.platform.MicService
import com.zevcorp.graph.voice.defaultTranscriber
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
class FloatingBubble(private val service: AccessibilityService) : UserChannel, Voice {

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

    /** Esquinas superiores = zona de encaje para la escucha PERMANENTE (pipeline Graph). */
    private val voiceDock by lazy {
        VoiceDock(service,
            setThinking = { bubble.thinking = it },
            narrate = ::narrate,
            runIntent = { prompt -> runPromptAwait(prompt) },
            returnToCorner = { left -> scope.launch { snapTo(cornerX(left), service.dp(6)) } })
    }

    private fun cornerX(left: Boolean): Int {
        val m = service.resources.displayMetrics
        return if (left) service.dp(4) else m.widthPixels - bubbleParams.width - service.dp(4)
    }

    /** El micrófono está ocupado por la escucha de esquina: el aprendizaje no debe interrumpir ahora. */
    val voiceBusy get() = voiceDock.docked || voiceDock.listening

    fun show() {
        tts = TextToSpeech(service) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
        val size = service.dp(82)
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
                // Un toque lo calla: corta el TTS y esconde el globo.
                tts?.isSpeaking == true -> {
                    tts?.stop()
                    speechHide?.cancel()
                    speech?.visibility = View.GONE
                }
                // Durante la escucha por esquina: el toque termina la grabación y procesa.
                voiceDock.listening -> voiceDock.stopNow()
                panel == null -> openPanel()
                else -> closePanel()
            }
        }
        bubble.pivotX = size / 2f
        bubble.pivotY = size / 2f
        wm.addView(bubble, bubbleParams)
        scheduleIdleShrink()
    }

    /* ---------- Reposo: la carita se encoge cuando llevas rato sin usarla ---------- */

    private var idleJob: Job? = null
    private var idleAnimator: ValueAnimator? = null
    @Volatile private var shrunk = false
    // Curva suave estilo Euler/Material (ease-in-out) para una transición placentera.
    private val idleEase = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
    private val idleGrow = OvershootInterpolator(1.2f)

    /** Reinicia el temporizador de reposo; si estaba encogida, la agranda de nuevo. */
    private fun wake() {
        if (shrunk) animateScale(1f, idleGrow)
        scheduleIdleShrink()
    }

    private fun scheduleIdleShrink() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(15_000) // ~15 s sin usarla → se encoge para no estorbar
            if (!shrunk) animateScale(0.34f, idleEase)
        }
    }

    private fun animateScale(target: Float, interp: android.view.animation.Interpolator) {
        shrunk = target < 0.99f
        idleAnimator?.cancel()
        val from = bubble.scaleX
        idleAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = if (target < from) 620 else 420
            interpolator = interp
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                bubble.scaleX = f; bubble.scaleY = f
            }
            start()
        }
    }

    /** Arrastre fluido con inercia: un flick corto la lanza a la esquina; siempre "aterriza" al borde. */
    private fun attachDrag(size: Int) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        var tracker: VelocityTracker? = null
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wake() // tocarla/moverla la despierta y la agranda
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
                        voiceDock.track(bubbleParams.x + size / 2, bubbleParams.y + size / 2)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) v.performClick()
                    else if (voiceDock.docked) {
                        // El dedo se mantuvo 2.5 s en la esquina y la escucha ya arrancó: solo
                        // asienta la burbuja en la esquina, sin volver a armar nada.
                        snapToCorner(size)
                    } else {
                        // Al SOLTAR el dedo se cancela cualquier cuenta pendiente: lanzar la burbuja
                        // a la esquina (o soltarla ahí sin mantener) NO activa la voz. Solo el
                        // arrastre-y-mantener 2.5 s la activa (lo maneja voiceDock.track en MOVE).
                        voiceDock.cancel()
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

    /** Asienta la burbuja en la esquina más cercana (solo visual; ya está en modo escucha). */
    private fun snapToCorner(size: Int) {
        val left = bubbleParams.x + size / 2 < service.resources.displayMetrics.widthPixels / 2
        snapTo(cornerX(left), service.dp(6))
    }

    /** Animación corta de encaje hacia un punto (también para volver a la esquina tras ejecutar). */
    private fun snapTo(destX: Int, destY: Int) {
        val fromX = bubbleParams.x; val fromY = bubbleParams.y
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
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
        idleAnimator?.cancel()
        voiceDock.destroy()
        tts?.shutdown()
        runCatching { wm.removeView(bubble) }
        panel?.let { runCatching { wm.removeView(it) } }
        speech?.let { runCatching { wm.removeView(it) } }
        stopButton?.let { runCatching { wm.removeView(it) } }
        answerMic?.let { runCatching { wm.removeView(it) } }
    }

    /* ---------- Voz y narración (globo de diálogo + TTS) ---------- */

    override fun narrate(text: String) = showSpeech(text, false)
    override fun speak(text: String) = showSpeech(text, true)

    private fun showSpeech(text: String, aloud: Boolean) {
        if (text.isBlank()) return
        scope.launch {
            wake() // si está hablando/narrando, que esté a tamaño completo
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
        moveAnswerMicToBubble() // el micrófono de respuesta también sigue a la carita
        val view = speech ?: return
        val p = speechParams ?: return
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = view.measuredWidth.coerceAtLeast(service.dp(60))
        val onLeftHalf = bubbleParams.x < service.resources.displayMetrics.widthPixels / 2
        p.x = if (onLeftHalf) bubbleParams.x + bubbleParams.width + service.dp(4) else (bubbleParams.x - w - service.dp(10)).coerceAtLeast(service.dp(4))
        p.y = (bubbleParams.y + service.dp(6)).coerceAtLeast(service.dp(4))
        runCatching { wm.updateViewLayout(view, p) }
    }

    /* ---------- Modo acompañante: la carita vuela hacia donde se va a actuar ---------- */

    /** Vuela suave hacia el objetivo (ease-in) y se queda flotando encima; pass-through para no estorbar. */
    suspend fun flyTo(targetX: Int, targetY: Int) = withContext(Dispatchers.Main) {
        setPassThrough(true)
        bubble.visibility = View.VISIBLE
        bubble.thinking = true
        wake() // trabajando: a tamaño completo
        val m = service.resources.displayMetrics
        val destX = (targetX - bubbleParams.width / 2).coerceIn(0, m.widthPixels - bubbleParams.width)
        val destY = (targetY - bubbleParams.height - service.dp(14)).coerceIn(0, m.heightPixels - bubbleParams.height)
        val startX = bubbleParams.x
        val startY = bubbleParams.y
        suspendCancellableCoroutine { cont ->
            ValueAnimator.ofFloat(0f, 1f).apply {
                // El vuelo sigue la barra de velocidad: es lo que domina el tiempo entre clics.
                duration = (app.stepDelay() * 12 / 10).coerceIn(140, 600)
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
            if (on) wake()
            setPassThrough(on)
            bubble.visibility = View.VISIBLE
            bubble.thinking = on
        }
    }

    private var stopButton: View? = null

    /** Botón rojo ⏹ arriba-centro mientras el asistente ejecuta: un toque detiene. Ventana propia y tocable. */
    fun showStop(on: Boolean) {
        scope.launch {
            if (!on) {
                stopButton?.let { runCatching { wm.removeView(it) } }
                stopButton = null
                return@launch
            }
            if (stopButton != null) return@launch
            val button = service.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Palette.danger, service.dp(22).toFloat())
                setPadding(service.dp(14), service.dp(7), service.dp(16), service.dp(7))
                elevation = 22f
                addView(IconView(service, Icon.STOP, tint = Color.WHITE),
                    LinearLayout.LayoutParams(service.dp(16), service.dp(16)))
                addView(TextView(service).apply {
                    text = "detener"; textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    setPadding(service.dp(8), 0, 0, 0)
                })
                setOnClickListener { app.stopExecution(); toast("Detenido") }
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
        header.addView(c.iconChip(Icon.CLOSE) { closePanel() })
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
        bar.addView(learnToggleButton())
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(c.iconChip(Icon.MIC) {
            recognize { heard -> if (heard != null) submit(heard) else toast("No te escuché") }
        })
        bar.addView(View(c), LinearLayout.LayoutParams(c.dp(6), 1))
        bar.addView(c.iconChip(Icon.SEND, primary = true) { submit(input.text.toString()) })
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

    /** Ejecuta un prompt con el motor mixto (Gemini computer-use + herramientas MCP). */
    private fun runPrompt(prompt: String) {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Graph"); return }
        voiceDock.cancel() // desarma una cuenta regresiva pendiente (no toca la escucha permanente)
        scope.launch { runPromptAwait(prompt) }
    }

    /** Igual que runPrompt pero SUSPENDE hasta terminar: lo usa el bucle de escucha permanente. */
    suspend fun runPromptAwait(prompt: String) = withContext(Dispatchers.Main) {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Graph"); return@withContext }
        narrate("¡Vamos! $prompt")
        runCatching { withContext(Dispatchers.Default) { app.run(prompt, this@FloatingBubble) } }
            .onSuccess { toast(it) }
            .onFailure { toast(if (it is CancellationException) "Ejecución detenida ✋" else "Error: ${it.message}") }
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
            body.addView(c.button("Responder con voz") {
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

    /* ---------- Enseñanza PASIVA: el 🎓 alterna el modo; mantenerlo oprimido muestra lo aprendido ---------- */

    /**
     * Botón 🎓 del panel. Toque: activa/desactiva la enseñanza pasiva (sin barra, sin botón de
     * detener, sin popups: el asistente solo observa el uso normal y consolida al salir de cada
     * app). Mantener oprimido: alterna la visualización de los elementos ya trackeados en MCPs.
     */
    private fun learnToggleButton(): View {
        val active = app.passive.active
        // Cuando está activo, el chip se pinta en acento y el ícono en blanco.
        val button = service.iconChip(Icon.TEACH, primary = active) { toggleTeaching() }
        if (active) button.background = rounded(Palette.accent, service.dp(21).toFloat())
        button.setOnLongClickListener {
            val on = (service as? GraphAccessibilityService)?.toggleLearnedVisualization() ?: false
            toast(if (on) "Te muestro lo que ya aprendí" else "Oculto lo aprendido")
            closePanel()
            true
        }
        return button
    }

    private fun toggleTeaching() {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Graph"); return }
        closePanel()
        val passive = app.passive
        if (!passive.active) passive.start()
        else scope.launch {
            runCatching { withContext(Dispatchers.Default) { passive.stop() } }
                .onFailure { toast("Enseñanza: ${it.message}") }
        }
    }

    /** Parpadeo de la carita: 1 vez al pasar a ejecución consciente, 2 al pasar a subconsciente. */
    fun blink(times: Int) {
        scope.launch { wake(); bubble.blink(times) }
    }

    /** Pulso de vida en acciones MCP por Intent (sin coordenadas: no hay a dónde volar). */
    fun pulse() {
        scope.launch { wake(); bubble.pulse() }
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

    /* ---------- Responder por voz: micrófono sticky pegado bajo la carita ---------- */

    private var answerMic: View? = null
    private var answerMicParams: WindowManager.LayoutParams? = null

    /**
     * Pregunta en voz alta y espera la respuesta del usuario mostrando un botón de micrófono
     * pegado JUSTO debajo de la carita (sticky): tocarlo termina la respuesta. Devuelve lo que oyó.
     * Lo usa la interrupción del aprendizaje pasivo para que responder sea inmediato.
     */
    suspend fun askAloud(question: String): String = withContext(Dispatchers.Main) {
        speak(question)
        val transcriber = defaultTranscriber(service)
        MicService.start(service)
        showAnswerMic { transcriber.stop() }
        val answer = withContext(Dispatchers.IO) { runCatching { transcriber.listen() }.getOrElse { "" } }
        hideAnswerMic()
        MicService.stop(service)
        answer
    }

    private fun showAnswerMic(onTap: () -> Unit) {
        if (answerMic != null) return
        val c = service
        val d = c.dp(52)
        val mic = IconView(c, Icon.MIC, tint = Color.WHITE).apply {
            val pad = c.dp(13)
            setPadding(pad, pad, pad, pad)
            background = rounded(Palette.accent, (d / 2).toFloat())
            elevation = 26f
            setOnClickListener { onTap() }
        }
        val p = overlayParams(d, d, focusable = false).apply { gravity = Gravity.TOP or Gravity.START }
        answerMic = mic
        answerMicParams = p
        runCatching { wm.addView(mic, p) }
        moveAnswerMicToBubble()
        pulseAnswerMic()
    }

    /** Late suavemente bajo la carita (o encima si no cabe abajo) y la sigue si se mueve. */
    private fun moveAnswerMicToBubble() {
        val mic = answerMic ?: return
        val p = answerMicParams ?: return
        val m = service.resources.displayMetrics
        val size = mic.layoutParams?.width ?: service.dp(52)
        p.x = (bubbleParams.x + (bubbleParams.width - size) / 2).coerceIn(service.dp(4), m.widthPixels - size - service.dp(4))
        val below = bubbleParams.y + bubbleParams.height + service.dp(8)
        p.y = if (below + size < m.heightPixels - service.dp(8)) below
        else bubbleParams.y - size - service.dp(8) // no cabe abajo: ponlo encima
        runCatching { wm.updateViewLayout(mic, p) }
    }

    private fun pulseAnswerMic() {
        val mic = answerMic ?: return
        ValueAnimator.ofFloat(1f, 1.12f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                if (answerMic == null) { cancel(); return@addUpdateListener }
                mic.scaleX = f; mic.scaleY = f
            }
            start()
        }
    }

    private fun hideAnswerMic() {
        answerMic?.let { runCatching { wm.removeView(it) } }
        answerMic = null
        answerMicParams = null
    }
}
