package com.zevcorp.graph.ui

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.view.animation.AccelerateInterpolator
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
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
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/**
 * La burbuja flotante de Graph: la carita del asistente siempre encima de cualquier app,
 * dibujada como overlay del servicio de accesibilidad (TYPE_ACCESSIBILITY_OVERLAY, sin
 * permisos extra). Arrastrable; al tocarla abre el panel con las tres etapas. Durante el
 * Learning desaparece (para no salir en las capturas del agente) y reaparece para preguntar;
 * en modo demostración un toque la convierte en el botón de "terminé".
 */
class FloatingBubble(private val service: AccessibilityService) : UserChannel {

    private val app get() = GraphApp.instance
    private val wm = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var bubble: FaceView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var panel: View? = null
    private var demoEnd: CompletableDeferred<Unit>? = null
    private var demoBadge: TextView? = null

    fun show() {
        val size = service.dp(60)
        bubble = FaceView(service)
        bubbleParams = overlayParams(size, size, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = service.resources.displayMetrics.widthPixels - size - service.dp(8)
            y = service.resources.displayMetrics.heightPixels / 3
        }
        bubble.elevation = 12f
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (moved || dx * dx + dy * dy > 900) {
                        moved = true
                        bubbleParams.x = startX + dx; bubbleParams.y = startY + dy
                        wm.updateViewLayout(bubble, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) v.performClick()
            }
            true
        }
        bubble.setOnClickListener {
            when {
                demoEnd != null -> { demoBadge?.let { runCatching { wm.removeView(it) } }; demoBadge = null; demoEnd?.complete(Unit); demoEnd = null }
                panel == null -> openPanel()
                else -> closePanel()
            }
        }
        wm.addView(bubble, bubbleParams)
    }

    fun destroy() {
        scope.cancel()
        runCatching { wm.removeView(bubble) }
        panel?.let { runCatching { wm.removeView(it) } }
        demoBadge?.let { runCatching { wm.removeView(it) } }
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
            setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16))
        }

        val header = c.row()
        header.addView(FaceView(c), LinearLayout.LayoutParams(c.dp(40), c.dp(40)))
        val titles = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(10), 0, 0, 0) }
        titles.addView(c.title("Graph", 17f))
        titles.addView(c.caption(if (app.ui != null) "Aprendo lo que me enseñas" else "Activa accesibilidad"))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(c.button("✕") { closePanel() })
        body.addView(header)
        body.gap(c.dp(12))

        body.addView(c.button(if (app.teachingActive) "⏹ Detener y analizar tutorial" else "⏺ Enseñarle grabando la pantalla", primary = true) {
            if (!app.teachingActive) {
                closePanel()
                c.startActivity(Intent(c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("action", "teach"))
            } else {
                closePanel()
                app.teachingActive = false
                toast("Analizando el tutorial…")
                scope.launch {
                    runCatching { withContext(Dispatchers.Default) { app.teaching.stopAndAnalyze() } }
                        .onSuccess { toast("Lección aprendida: ${it.goal}") }
                        .onFailure { toast("Error al analizar: ${it.message}") }
                }
            }
        })
        body.gap(c.dp(10))

        val lessons = app.lessons.list().takeLast(4).reversed()
        val workflows = app.workflows.list().takeLast(4).reversed()
        if (lessons.isNotEmpty()) {
            body.addView(c.caption("LECCIONES · toca para que las ejecute y aprenda"))
            body.gap(c.dp(6))
            lessons.forEach { lesson ->
                body.addView(c.button("🎓 ${lesson.goal.take(42)}") { closePanel(); learn(lesson) })
                body.gap(c.dp(6))
            }
        }
        if (workflows.isNotEmpty()) {
            body.gap(c.dp(4))
            body.addView(c.caption("WORKFLOWS · ejecución subconsciente, sin LLM"))
            body.gap(c.dp(6))
            workflows.forEach { w ->
                body.addView(c.button("⚡ ${w.name.take(42)}") {
                    closePanel()
                    scope.launch { toast(withContext(Dispatchers.Default) { app.runWorkflow(w.id, emptyMap()) }) }
                })
                body.gap(c.dp(6))
            }
        }
        body.gap(c.dp(4))
        body.addView(c.button("Abrir la app completa") {
            closePanel()
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })

        val scroll = ScrollView(c).apply { addView(body); elevation = 16f }
        val params = overlayParams(c.dp(300), -2, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams.x - c.dp(305)).coerceAtLeast(c.dp(4))
            y = bubbleParams.y.coerceAtMost(service.resources.displayMetrics.heightPixels - c.dp(420))
        }
        wm.addView(scroll, params)
        panel = scroll
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
