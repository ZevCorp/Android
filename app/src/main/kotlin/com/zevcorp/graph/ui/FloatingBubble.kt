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
import android.media.AudioAttributes
import android.media.SoundPool
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
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.platform.MicService
import com.zevcorp.graph.voice.Transcriber
import com.zevcorp.graph.voice.defaultTranscriber
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/**
 * La burbuja flotante de Ü: la carita del asistente siempre encima de cualquier app, dibujada
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
    /** Momento del último autocierre por toque-fuera: evita que ese mismo toque en la carita reabra. */
    private var outsideCloseAt = 0L
    private var dragAnimator: ValueAnimator? = null

    // Gestos sobre la carita: 1 toque = menú (con leve retraso) · 2 = micrófono · 3 = tema.
    private var tapCount = 0
    private var tapJob: Job? = null

    private var speech: TextView? = null
    private var speechParams: WindowManager.LayoutParams? = null
    private var speechHide: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    /** Voz de nueva generación (OpenAI); si está activa y hay key, reemplaza al TTS del sistema. */
    private val openAiTts by lazy { com.zevcorp.graph.voice.OpenAiTts(service) }

    /** Esquinas superiores = zona de encaje para el MODO REUNIÓN (escucha continua con cerebro). */
    private val voiceDock by lazy {
        VoiceDock(service,
            setThinking = { bubble.thinking = it },
            narrate = ::narrate,
            speak = ::speak,
            runTask = { prompt -> runTaskAwait(prompt) },
            returnToCorner = { left -> scope.launch { snapTo(cornerX(left), service.dp(6)) } })
    }

    /* ---------- Feedback sonoro: un tick breve para toques rápidos, un carrillón para activar el
     * micrófono — igual de cuidado que el háptico de una app premium, nunca intrusivo. ---------- */

    private val soundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }
    private var tickSoundId = 0
    private var chimeSoundId = 0
    @Volatile private var soundsReady = false

    private fun loadSounds() {
        soundPool.setOnLoadCompleteListener { _, _, status -> if (status == 0) soundsReady = true }
        tickSoundId = soundPool.load(service, com.zevcorp.graph.R.raw.tick, 1)
        chimeSoundId = soundPool.load(service, com.zevcorp.graph.R.raw.mic_chime, 1)
    }

    /** Toques rápidos y de un solo paso: abrir/cerrar el menú, silenciar, detener — un tick discreto. */
    private fun playTick() {
        if (soundsReady) soundPool.play(tickSoundId, 0.45f, 0.45f, 0, 0, 1f)
    }

    /** Activar el micrófono (doble toque, o un toque cuando ya está anclada en la barra) — un carrillón cuidado. */
    private fun playListenChime() {
        if (soundsReady) soundPool.play(chimeSoundId, 0.65f, 0.65f, 0, 0, 1f)
    }

    /** Sin sombra en modo transparencia: si no, aunque no haya relleno, queda una figura visible detrás. */
    private fun faceElevation() = if (Palette.faceTransparent) 0f else 30f

    private fun cornerX(left: Boolean): Int {
        val m = service.resources.displayMetrics
        return if (left) service.dp(4) else m.widthPixels - bubbleParams.width - service.dp(4)
    }

    /** El micrófono está ocupado por la escucha de esquina: el aprendizaje no debe interrumpir ahora. */
    val voiceBusy get() = voiceDock.docked || voiceDock.listening

    fun show() {
        loadSounds()
        tts = TextToSpeech(service) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
                applyVoice() // voz más humana + género elegido en la configuración
            }
        }
        val size = service.dp(82)
        bubble = FaceView(service)
        bubbleParams = overlayParams(size, size, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = service.resources.displayMetrics.widthPixels - size - service.dp(8)
            y = service.resources.displayMetrics.heightPixels / 3
        }
        bubble.elevation = faceElevation() // sombra profunda que contrasta con el fondo (outline en FaceView)
        attachDrag(size)
        bubble.setOnClickListener {
            when {
                // Un toque lo calla: corta la voz (OpenAI o sistema) y esconde el globo.
                tts?.isSpeaking == true || openAiTts.isPlaying -> { playTick(); hush() }
                // Escucha en vivo de la ejecución: el toque a la burbuja la apaga.
                execLive -> { playTick(); stopExecLive() }
                // Durante la escucha por esquina: el toque termina la grabación y procesa.
                voiceDock.listening -> { playTick(); voiceDock.stopNow() }
                // Pequeña al inicio de la barra de la app: UN toque = sube grande y es el micrófono.
                appDocked && atBar -> { playListenChime(); flyUpAndListen() }
                // Estado normal: gestos por número de toques (1 menú · 2 micrófono · 3 tema).
                else -> onBubbleTap()
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
    // Transición "dopamínica": encoge con un rebote suave y agranda con un pop marcado (overshoot).
    private val idleEase = OvershootInterpolator(1.6f)
    private val idleGrow = OvershootInterpolator(3.4f)

    /** Reinicia el temporizador de reposo; si estaba encogida, la agranda de nuevo. */
    private fun wake() {
        if (appDocked) return // anclada a la app: su tamaño y posición los gobierna la app
        wanderJob?.cancel()
        if (shrunk) animateScale(1f, idleGrow)
        scheduleIdleShrink()
    }

    private fun scheduleIdleShrink() {
        if (appDocked) return
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(15_000) // ~15 s sin usarla → se encoge para no estorbar
            // Nunca se encoge con el cuadro de diálogo abierto: se mantiene grande mientras lo usas.
            if (!shrunk && panel == null) { animateScale(0.56f, idleEase); startWander() }
        }
    }

    /* ---------- Paseo en reposo: encogida, de vez en cuando cambia de sitio ---------- */

    private var wanderJob: Job? = null

    /**
     * Muy de vez en cuando (tiempo ALEATORIO, entre 2 y 5 minutos) la carita encogida se pasea a
     * otro punto del borde — señal sutil de vida. Solo con la pantalla ENCENDIDA (si está apagada
     * no gasta nada: simplemente vuelve a sortear el próximo intento) y nunca mientras ejecuta,
     * escucha o está anclada en una esquina.
     */
    private fun startWander() {
        wanderJob?.cancel()
        wanderJob = scope.launch {
            val random = java.util.Random()
            while (shrunk) {
                delay(120_000L + (random.nextFloat() * 180_000L).toLong()) // 2–5 min, nunca exacto
                if (!shrunk) break
                val pm = service.getSystemService(android.os.PowerManager::class.java)
                if (pm?.isInteractive != true) continue // pantalla apagada: no molestar ni gastar
                if (app.executing || voiceDock.docked || voiceDock.listening || panel != null) continue
                wanderOnce(random)
            }
        }
    }

    /** Un paseo: al borde opuesto (o el mismo, a veces) con altura aleatoria, animación lenta. */
    private fun wanderOnce(random: java.util.Random) {
        val m = service.resources.displayMetrics
        val size = bubbleParams.width
        val onLeft = bubbleParams.x + size / 2 < m.widthPixels / 2
        val goLeft = if (random.nextFloat() < 0.75f) !onLeft else onLeft // casi siempre cruza
        val destX = if (goLeft) 0 else m.widthPixels - size
        val minY = service.dp(90)
        val maxY = m.heightPixels - size - service.dp(140)
        val destY = minY + (random.nextFloat() * (maxY - minY).coerceAtLeast(1)).toInt()
        snapTo(destX, destY, dur = 1400, interp = idleEase)
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

    /* ---------- Anclaje a la app: pequeña al inicio de la barra de texto; un toque = micrófono ---------- */

    private var preAppDockX = -1
    private var preAppDockY = -1
    private var appDocked = false
    /** Está asentada (pequeña) al inicio de la barra de texto. */
    private var atBar = false
    private var barDockX = -1
    private var barDockY = -1

    /** La app (MainActivity) pasó a primer plano SIN barra (vistas versus/desarrollador): la burbuja
     * se posiciona al centro superior a tamaño completo, sin encogerse ni pasear mientras dure. */
    fun dockToApp() {
        bubble.visibility = View.VISIBLE
        if (appDocked && !atBar) return
        rememberHome()
        appDocked = true
        atBar = false
        wanderJob?.cancel()
        idleJob?.cancel()
        animateScale(1f, idleGrow)
        val m = service.resources.displayMetrics
        snapTo((m.widthPixels - bubbleParams.width) / 2, service.dp(150), dur = 420, interp = idleGrow)
    }

    /** Vista principal: la carita se hace PEQUEÑA y se asienta al inicio de la barra de texto
     * (cx,cy = centro deseado, en coordenadas de pantalla). Ahí ES el micrófono: ver el click. */
    fun dockToBar(cx: Int, cy: Int) {
        rememberHome()
        val first = !appDocked || !atBar
        appDocked = true
        atBar = true
        barDockX = cx - bubbleParams.width / 2
        barDockY = cy - bubbleParams.height / 2
        wanderJob?.cancel()
        idleJob?.cancel()
        animateScale(BAR_SCALE, idleEase)
        snapTo(barDockX, barDockY, dur = if (first) 420 else 200, interp = idleGrow)
    }

    /** Sigue a la barra si se mueve (p.ej. sube con el teclado); no hace nada si está volando/mic. */
    fun trackBar(cx: Int, cy: Int) {
        if (appDocked && atBar) dockToBar(cx, cy)
    }

    /** Guarda la posición "de la calle" solo al entrar al modo app (no al moverse dentro de él). */
    private fun rememberHome() {
        if (!appDocked) { preAppDockX = bubbleParams.x; preAppDockY = bubbleParams.y }
    }

    /** Toque estando en la barra: vuela arriba (grande) y ESCUCHA — la carita es el micrófono. */
    private fun flyUpAndListen() {
        atBar = false
        val m = service.resources.displayMetrics
        animateScale(1f, idleGrow)
        snapTo((m.widthPixels - bubbleParams.width) / 2, service.dp(150), dur = 380, interp = idleGrow)
        scope.launch {
            delay(420) // deja aterrizar la carita antes de abrir el oído
            recognize { heard ->
                if (heard.isNullOrBlank()) {
                    toast("No te escuché")
                    redockToBar()
                } else scope.launch {
                    runPromptAwait(heard)
                    redockToBar() // si la app sigue abierta, vuelve a su puesto en la barra
                }
            }
        }
    }

    /** Vuelve pequeña a su puesto en la barra (si la app sigue en primer plano). */
    private fun redockToBar() {
        if (!appDocked || barDockX < 0) return
        atBar = true
        animateScale(BAR_SCALE, idleEase)
        snapTo(barDockX, barDockY, dur = 320, interp = idleGrow)
    }

    /** La app volvió a segundo plano: la burbuja recupera su tamaño y regresa a donde estaba. */
    fun undockFromApp() {
        if (!appDocked) return
        appDocked = false
        atBar = false
        animateScale(1f, idleGrow)
        // Si hay una ejecución en curso, no pelear con flyTo: el motor gobierna la posición.
        if (!app.executing && preAppDockX >= 0) snapTo(preAppDockX, preAppDockY, dur = 320)
        scheduleIdleShrink()
    }

    /**
     * En la PORTADA (MainActivity, pantalla Miracle) la cara protagonista es la in-app (grande, al
     * centro, arrastrable). Mientras esa pantalla está en primer plano, la burbuja overlay se OCULTA
     * para no duplicar caras ni asentarse en la barra. Al salir (ejecución, otra app) reaparece.
     */
    fun setHiddenForApp(hidden: Boolean) {
        scope.launch {
            if (hidden) {
                appDocked = false; atBar = false
                wanderJob?.cancel(); idleJob?.cancel(); dragAnimator?.cancel()
                bubble.visibility = View.GONE
            } else if (bubble.visibility != View.VISIBLE) {
                shrunk = false
                bubble.scaleX = 1f; bubble.scaleY = 1f
                bubble.visibility = View.VISIBLE
                scheduleIdleShrink()
            }
        }
    }

    /* ---------- Voz (TTS): elegir una voz más humana y su género desde la configuración ---------- */

    /**
     * Selecciona la mejor voz disponible del idioma actual (galería del motor TTS del sistema) y aplica
     * el género elegido en la configuración. Para que la diferencia hombre/mujer se oiga en cualquier
     * dispositivo (el género no es un atributo fiable del motor), se combina: voz distinta cuando hay
     * varias + un tono (pitch) más grave para la masculina y más agudo para la femenina.
     */
    fun reapplyVoice() { scope.launch { applyVoice() } }

    private fun applyVoice() {
        val t = tts ?: return
        if (!ttsReady) return
        val male = app.prefs.getString("voiceGender", "male") != "female"
        val lang = Locale.getDefault().language
        val cands = runCatching {
            (t.voices ?: emptySet()).filter {
                it.locale.language == lang &&
                    it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
            }.sortedByDescending { it.quality }
        }.getOrDefault(emptyList())
        val base = when {
            cands.isEmpty() -> null
            male -> cands.first()                       // masculina: la mejor voz
            else -> cands.getOrNull(1) ?: cands.first() // femenina: otra voz si la hay
        }
        base?.let { runCatching { t.voice = it } }
        runCatching { t.setPitch(if (male) 0.9f else 1.12f) }
        runCatching { t.setSpeechRate(1.0f) }
    }

    /** Animación de encaje hacia un punto (esquinas, regreso tras ejecutar, paseo en reposo). */
    private fun snapTo(destX: Int, destY: Int, dur: Long = 220, interp: android.view.animation.Interpolator? = null) {
        val fromX = bubbleParams.x; val fromY = bubbleParams.y
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interp?.let { interpolator = it }
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
        openAiTts.stop()
        soundPool.release()
        runCatching { wm.removeView(bubble) }
        panel?.let { runCatching { wm.removeView(it) } }
        speech?.let { runCatching { wm.removeView(it) } }
        stopBar?.let { runCatching { wm.removeView(it) } }
    }

    /* ---------- Voz y narración (globo de diálogo + TTS) ---------- */

    override fun narrate(text: String) = showSpeech(text, false)
    override fun speak(text: String) = showSpeech(text, true)

    /** Calla la voz en curso (OpenAI o TTS del sistema) y esconde el globo. También lo usa el chip de medios. */
    fun hush() {
        runCatching { tts?.stop() }
        openAiTts.stop()
        app.voiceSession.endSpeaking()
        speechHide?.cancel()
        speech?.visibility = View.GONE
    }

    private fun showSpeech(text: String, aloud: Boolean) {
        if (text.isBlank()) return
        scope.launch {
            wake() // si está hablando/narrando, que esté a tamaño completo
            val bubbleText = speech ?: TextView(service).apply {
                setTextColor(Palette.bg)
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
            if (aloud) {
                val clean = text.filter { it.code in 32..0x2FFF }
                // Voz de nueva generación (OpenAI) si está elegida y hay key; si falla, TTS del sistema.
                val spoke = runCatching { openAiTts.speak(clean) }.getOrDefault(false)
                if (!spoke && ttsReady) {
                    // Voz del sistema: respeta la misma ganancia del asistente y publica su barra en el
                    // panel de volumen (se desactiva sola tras un margen estimado por la longitud del texto).
                    app.voiceSession.beginSpeaking((clean.length * 75L).coerceIn(4_000L, 40_000L))
                    val params = android.os.Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, app.voiceSession.gain)
                    }
                    tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, "graph")
                }
            }
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

    private var stopBar: View? = null
    private var execStatusText: TextView? = null
    private var execMicButton: View? = null

    /**
     * Interruptor temporal: la píldora de ejecución no distingue "consciente"/"subconsciente" en el
     * texto (queda fijo en "ejecutando…"). Mismo criterio que GraphApp.subconsciousExecution: poner
     * en `true` cuando se reactive la vía subconsciente.
     */
    private val showExecutionVia = false

    /**
     * "NOTCH" de ejecución, arriba-centro: una píldora negra (detener, toca en cualquier parte) junto
     * a un círculo negro con un micrófono (interrumpir por voz), separados por un espacio pequeño —
     * misma estética redondeada/negra que el notch del teléfono. Ventana propia y tocable; el círculo
     * del micrófono nace oculto y `showExecutionMic` lo muestra/oculta sin recrear la ventana.
     */
    fun showStop(on: Boolean) {
        scope.launch {
            if (!on) {
                stopBar?.let { runCatching { wm.removeView(it) } }
                stopBar = null
                execStatusText = null
                execMicButton = null
                return@launch
            }
            if (stopBar != null) return@launch
            val pill = service.row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.BLACK, service.dp(22).toFloat())
                setPadding(service.dp(14), service.dp(7), service.dp(16), service.dp(7))
                elevation = 22f
                addView(IconView(service, Icon.STOP, tint = Color.WHITE),
                    LinearLayout.LayoutParams(service.dp(16), service.dp(16)))
                execStatusText = TextView(service).apply {
                    text = "ejecutando…"; textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                    setPadding(service.dp(8), 0, 0, 0)
                }
                addView(execStatusText)
                setOnClickListener { app.stopExecution(); toast("Detenido") }
            }
            val micD = service.dp(40)
            val mic = IconView(service, Icon.MIC, tint = Color.WHITE).apply {
                val pad = service.dp(10)
                setPadding(pad, pad, pad, pad)
                background = rounded(Color.BLACK, (micD / 2).toFloat())
                elevation = 22f
                visibility = View.GONE // solo se muestra mientras hay una respuesta en curso
                setOnClickListener { startExecVoice(this) }
            }
            execMicButton = mic

            val outer = service.row()
            outer.addView(pill)
            outer.addView(View(service), LinearLayout.LayoutParams(service.dp(10), 1)) // espacio pequeño
            outer.addView(mic, LinearLayout.LayoutParams(micD, micD))

            val params = overlayParams(-2, -2, focusable = false).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = service.dp(8)
            }
            runCatching { wm.addView(outer, params) }
            stopBar = outer
        }
    }

    /** Actualiza la vía mostrada en la píldora (cada acción del motor la reporta). Sin efecto mientras
     *  `showExecutionVia` esté en false: el texto queda fijo en "ejecutando…". */
    fun execStatus(subconscious: Boolean) {
        if (!showExecutionVia) return
        scope.launch {
            execStatusText?.text = if (subconscious) "🧩 subconsciente" else "👁 consciente"
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
        // La carita del panel: TÓCALA para alternar el tema (claro → oscuro → transparencia).
        val headFace = FaceView(c)
        headFace.setOnClickListener { cycleTheme() }
        header.addView(headFace, LinearLayout.LayoutParams(c.dp(40), c.dp(40)))
        val titles = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(c.dp(10), 0, 0, 0) }
        titles.addView(c.title("Ü", 16f))
        titles.addView(c.caption(if (app.ui != null) "Toca mi cara: tema ${Palette.label()}" else "Activa accesibilidad"))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
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

        val scroll = ScrollView(c).apply {
            addView(body); elevation = 16f
            // Un toque FUERA del panel lo cierra y deja pasar el toque al teléfono (no bloquea nada).
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) { onOutsidePanelTouch(); true } else false
            }
        }
        // Focusable (para poder escribir) pero NO modal: los toques fuera van al resto del teléfono y,
        // con WATCH_OUTSIDE_TOUCH, recibimos ACTION_OUTSIDE para autocerrarnos. Antes era modal y
        // bloqueaba todo el teléfono hasta tocar la X (que ya no existe).
        val params = WindowManager.LayoutParams(
            c.dp(316), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams.x - c.dp(322)).coerceAtLeast(c.dp(4))
            y = bubbleParams.y.coerceAtMost(service.resources.displayMetrics.heightPixels - c.dp(240))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        wm.addView(scroll, params)
        panel = scroll
        wake()            // asegura que la carita esté grande…
        idleJob?.cancel() // …y no se encoja mientras el panel esté abierto
    }

    /** Toque fuera del panel: se cierra y el toque pasa al teléfono (marca la hora para no reabrir). */
    private fun onOutsidePanelTouch() {
        outsideCloseAt = android.os.SystemClock.uptimeMillis()
        closePanel()
    }

    /** Alterna el tema de TODA la app (claro → oscuro → transparencia) tocando la carita del panel. */
    private fun cycleTheme() {
        Palette.mode = Palette.next()
        app.prefs.edit().putString("theme", Palette.mode.name).apply()
        bubble.elevation = faceElevation() // sin sombra en transparencia; la restaura en claro/oscuro
        bubble.invalidate() // la carita grande cambia de tema al instante
        toast("Tema ${Palette.label()}")
        // Repinta el panel con los nuevos colores en el próximo frame (no lo removemos durante su
        // propio evento de toque).
        scope.launch { closePanel(); openPanel() }
    }

    /**
     * Gestos por número de toques, resueltos tras una ventana breve. El pequeño retraso hace que la
     * apertura del menú "se sienta" como transición y evita abrirlo en el primer toque de un doble o
     * triple toque.
     *  · 1 toque  → abre/cierra el menú
     *  · 2 toques → activa el micrófono (ejecuta lo pedido por voz)
     *  · 3 toques → cambia el tema (claro · oscuro · transparente)
     */
    private fun onBubbleTap() {
        tapCount++
        tapJob?.cancel()
        tapJob = scope.launch {
            delay(GESTURE_WINDOW_MS)
            val n = tapCount
            tapCount = 0
            when (n) {
                1 -> if (panel == null) {
                    if (android.os.SystemClock.uptimeMillis() - outsideCloseAt > 350) { playTick(); openPanel() }
                } else { playTick(); closePanel() }
                2 -> { playListenChime(); activateMic() }
                else -> cycleTheme()
            }
        }
    }

    /**
     * Doble toque: escucha por voz y ejecuta lo pedido, sin abrir el menú. Antes de escuchar, la
     * carita vuelve siempre al centro superior (igual que al abrir la app), sin importar en qué
     * rincón de la pantalla estuviera paseando o anclada.
     */
    private fun activateMic() {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Ü"); return }
        centerTop()
        recognize { heard -> if (heard != null) runPrompt(heard) else toast("No te escuché") }
    }

    /** Lleva la carita al centro superior desde cualquier posición (paseando, en una esquina, encogida). */
    private fun centerTop() {
        wanderJob?.cancel()
        idleJob?.cancel()
        animateScale(1f, idleGrow)
        val m = service.resources.displayMetrics
        snapTo((m.widthPixels - bubbleParams.width) / 2, service.dp(150), dur = 380, interp = idleGrow)
    }

    /** Ejecuta un prompt con el motor mixto (Gemini computer-use + herramientas MCP). */
    private fun runPrompt(prompt: String) {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Ü"); return }
        voiceDock.cancel() // desarma una cuenta regresiva pendiente (no toca la escucha permanente)
        scope.launch { runPromptAwait(prompt) }
    }

    /** Igual que runPrompt pero SUSPENDE hasta terminar: lo usa el bucle de escucha permanente. */
    suspend fun runPromptAwait(prompt: String) = withContext(Dispatchers.Main) {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Ü"); return@withContext }
        narrate("¡Vamos! $prompt")
        runCatching { withContext(Dispatchers.Default) { app.run(prompt, this@FloatingBubble) } }
            .onSuccess { toast(it) }
            .onFailure { toast(if (it is CancellationException) "Ejecución detenida ✋" else "Error: ${it.message}") }
    }

    /** Ejecuta una tarea y DEVUELVE el resumen del motor: lo usa el modo reunión para su registro. */
    suspend fun runTaskAwait(prompt: String): String = withContext(Dispatchers.Main) {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Ü"); return@withContext "sin accesibilidad" }
        runCatching { withContext(Dispatchers.Default) { app.run(prompt, this@FloatingBubble) } }
            .getOrElse { if (it is CancellationException) "detenida por el usuario ✋" else "error: ${it.message}" }
    }

    private fun closePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        scheduleIdleShrink() // al cerrar, retoma el ciclo de reposo (encoger tras un rato)
    }

    /* ---------- El asistente pregunta: respuesta por texto o voz ---------- */

    override suspend fun ask(question: String): String = withContext(Dispatchers.Main) {
        bubble.visibility = View.VISIBLE
        bubble.thinking = true
        wake()
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
                val wasExecuting = app.executing
                recognize { heard ->
                    if (heard != null) {
                        finish(heard)
                        // ÚNICO caso donde se enciende la escucha en tiempo real fuera de las
                        // esquinas: respondiste por voz una duda del asistente mientras ejecutaba.
                        if (wasExecuting) startExecLive()
                    } else toast("No te escuché, intenta de nuevo")
                }
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

    /* ---------- Enseñanza ACTIVA: el 🎓 comparte pantalla; mantenerlo oprimido muestra lo aprendido ---------- */

    /**
     * Botón 🎓 del panel. Toque: activa/detiene la enseñanza ACTIVA (comparte pantalla: graba video +
     * audio y al terminar estructura lo enseñado como conocimiento MCP por app). Mantener oprimido:
     * alterna la visualización de los elementos ya trackeados en MCPs (igual que antes). La activación
     * de la enseñanza PASIVA se movió a la app principal.
     */
    private fun learnToggleButton(): View {
        val active = app.activeLearning.busy
        // Cuando está grabando/procesando, el chip se pinta en acento y el ícono en blanco.
        val button = service.iconChip(Icon.TEACH, primary = active) { toggleActiveLearning() }
        if (active) button.background = rounded(Palette.accent, service.dp(21).toFloat())
        button.setOnLongClickListener {
            val on = (service as? GraphAccessibilityService)?.toggleLearnedVisualization() ?: false
            toast(if (on) "Te muestro lo que ya aprendí" else "Oculto lo aprendido")
            closePanel()
            true
        }
        return button
    }

    private fun toggleActiveLearning() {
        if (app.ui == null) { toast("Activa el servicio de accesibilidad de Ü"); return }
        closePanel()
        app.activeLearning.toggle()
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

    /* ---------- Mensaje-sobre-mensaje: micrófono junto al "notch" durante la ejecución ---------- */

    private var execTranscriber: Transcriber? = null

    /**
     * Muestra/oculta el círculo de micrófono que vive junto a la píldora de ejecución (creado, oculto,
     * en `showStop`). Tocarlo empieza a escuchar; al terminar, el texto va a `augmentExecution` y el
     * motor se reencamina — se puede interrumpir en cualquier momento mientras el asistente ejecuta.
     */
    fun showExecutionMic(on: Boolean) {
        scope.launch {
            if (!on) execTranscriber?.stop()
            execMicButton?.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    private fun startExecVoice(mic: View) {
        if (execTranscriber != null) { execTranscriber?.stop(); return } // ya escuchando → corta
        val t = defaultTranscriber(service)
        execTranscriber = t
        mic.animate().scaleX(1.15f).scaleY(1.15f).setDuration(150).start()
        MicService.start(service)
        toast("Dime, lo sumo a lo que estoy haciendo…")
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { t.listen() }.getOrElse { "" } }
            MicService.stop(service)
            execTranscriber = null
            mic.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
            if (text.isNotBlank()) app.augmentExecution(text)
        }
    }

    /* ---------- Escucha continua DURANTE la ejecución (tras responder una duda por voz) ---------- */

    private var execLiveJob: Job? = null
    private var execLiveTranscriber: Transcriber? = null

    /** ¿Está encendida la escucha en tiempo real de esta ejecución? */
    val execLive get() = execLiveJob?.isActive == true

    /**
     * El modo de las esquinas, pero atado a UNA ejecución: escucha en bucle y cada cosa que digas
     * se suma al objetivo (mensaje-sobre-mensaje). Se apaga solo al terminar la ejecución, o antes
     * si tocas la burbuja.
     */
    fun startExecLive() {
        if (execLive) return
        narrate("Te sigo escuchando mientras trabajo; tócame para dejar de oírte 👂")
        LogBus.log("voice", "▶ escucha en vivo durante la ejecución")
        execLiveJob = scope.launch {
            MicService.start(service)
            try {
                while (app.executing) {
                    val t = defaultTranscriber(service)
                    execLiveTranscriber = t
                    val text = withContext(Dispatchers.IO) { runCatching { t.listen() }.getOrElse { "" } }
                    execLiveTranscriber = null
                    if (!app.executing) break
                    if (text.isBlank()) { delay(250); continue }
                    LogBus.log("voice", "en vivo: \"${text.take(120)}\"")
                    app.augmentExecution(text)
                }
            } finally {
                execLiveTranscriber = null
                MicService.stop(service)
            }
        }
    }

    /** Apaga la escucha en vivo (toque de la burbuja, o fin de la ejecución desde GraphApp). */
    fun stopExecLive(announce: Boolean = true) {
        if (execLiveJob == null) return
        execLiveJob?.cancel()
        execLiveJob = null
        execLiveTranscriber?.stop()
        execLiveTranscriber = null
        MicService.stop(service)
        LogBus.log("voice", "■ escucha en vivo apagada")
        if (announce) narrate("Ok, dejo de escucharte")
    }

    private companion object {
        const val GESTURE_WINDOW_MS = 260L
        /** Escala de la carita cuando está asentada al inicio de la barra de texto de la app. */
        const val BAR_SCALE = 0.45f
    }
}
