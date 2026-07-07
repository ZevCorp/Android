package com.zevcorp.graph.ui

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.platform.MeetingLog
import com.zevcorp.graph.platform.MicService
import com.zevcorp.graph.voice.Transcriber
import com.zevcorp.graph.voice.defaultTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Zonas de activación por voz en las DOS esquinas superiores. Arrastrar la burbuja CON EL DEDO a
 * una esquina y mantenerla ~2.5 s enciende el MODO REUNIÓN: escucha continua de la conversación
 * (una persona dictando o varias desarrollando ideas) que dura lo que dure la reunión. Cada
 * fragmento pasa por el cerebro de reunión (MeetingBrain), que decide: tomar nota, lanzar una
 * construcción al motor EN PARALELO (la escucha nunca se detiene), hablar en voz alta, o detectar
 * el cierre e intervenir con el resumen + demo. Todo queda en files/meetings/ (MeetingLog).
 * Sacar la burbuja de la esquina es la única forma de terminar.
 */
class VoiceDock(
    private val service: AccessibilityService,
    /** Cara pensativa on/off. */
    private val setThinking: (Boolean) -> Unit,
    /** Globo de narración de la burbuja. */
    private val narrate: (String) -> Unit,
    /** Habla EN VOZ ALTA (TTS): la voz con la que Ü interviene en la reunión. */
    private val speak: (String) -> Unit,
    /** Ejecuta una tarea con el motor, SUSPENDE hasta terminar y devuelve su resumen. */
    private val runTask: suspend (String) -> String,
    /** Reencaja la burbuja en su esquina (izq/der) al volver de una ejecución. */
    private val returnToCorner: (left: Boolean) -> Unit,
) {

    private val app get() = GraphApp.instance
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var badge: TextView? = null
    private var armTimer: Runnable? = null
    private var loopJob: Job? = null
    private var transcriber: Transcriber? = null
    private var dockLeft = true

    /* Estado del modo reunión. */
    private var meeting: MeetingLog? = null
    private var taskWorker: Job? = null
    private var taskQueue: Channel<String>? = null
    private val thinkMutex = Mutex()
    /** Hasta cuándo NO abrir el micrófono (mientras Ü habla, para no transcribirse a sí mismo). */
    @Volatile private var quietUntil = 0L

    /** Modo escucha permanente encendido (burbuja anclada en una esquina). */
    @Volatile var docked = false
        private set

    /** Hay un segmento de escucha de micrófono en curso. */
    @Volatile var listening = false
        private set

    private val zone get() = service.dp(120)

    /** ¿El centro de la burbuja está en una esquina superior (la posición más alta posible)? */
    fun inZone(cx: Int, cy: Int): Boolean {
        val w = service.resources.displayMetrics.widthPixels
        return cy < zone && (cx < zone || cx > w - zone)
    }

    /** La burbuja se movió (arrastre o encaje). Gobierna armado, anclaje y salida del modo. */
    fun track(cx: Int, cy: Int) {
        val inside = inZone(cx, cy)
        if (docked) {
            if (!inside) undock() // el usuario la sacó de la esquina: ÚNICA forma de apagar
            return
        }
        if (inside) arm(cx) else disarm()
    }

    private fun arm(cx: Int) {
        if (armTimer != null) return
        dockLeft = cx < service.resources.displayMetrics.widthPixels / 2
        setThinking(true)
        showBadge("🎧 mantén el dedo aquí… modo reunión en 2.5 s")
        val t = Runnable { armTimer = null; dock() }
        armTimer = t
        handler.postDelayed(t, 2500)
    }

    /** Desarma la cuenta regresiva (la burbuja salió de la esquina antes de activarse). */
    private fun disarm() {
        armTimer?.let { handler.removeCallbacks(it) }
        armTimer = null
        if (!docked && !listening) { hideBadge(); setThinking(false) }
    }

    /** Compat: desarmar desde fuera (p.ej. arranca una ejecución manual). No toca el modo anclado. */
    fun cancel() {
        if (!docked) disarm()
    }

    /** Toque en la burbuja durante la escucha: corta el segmento actual y procesa lo que haya. */
    fun stopNow() {
        transcriber?.stop()
    }

    private fun dock() {
        docked = true
        MicService.start(service)
        LogBus.log("meeting", "▶ MODO REUNIÓN en la esquina ${if (dockLeft) "izquierda" else "derecha"}")
        narrate("Estoy en la reunión 👂 tomo notas y construyo lo que decidan")
        loopJob = app.scope.launch {
            try {
                listenLoop()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    hideBadge(); setThinking(false)
                }
                MicService.stop(service)
            }
        }
    }

    /** Sale del modo reunión. Si hay una tarea en curso, el worker muere al acabar esta. */
    private fun undock() {
        docked = false
        transcriber?.stop()
        disarm()
        meeting?.persist()
        LogBus.log("meeting", "■ reunión terminada (burbuja fuera de la esquina) · " +
            "${meeting?.notes()?.size ?: 0} notas guardadas en files/meetings/")
        narrate("Listo, salgo de la reunión 👋 las notas quedaron guardadas")
        if (!listening) { hideBadge(); setThinking(false) }
    }

    /**
     * El bucle de la reunión: escucha en segmentos SIN detenerse nunca; cada fragmento se registra
     * y se manda al cerebro EN PARALELO (pensar no interrumpe la escucha). Las tareas que el
     * cerebro decide se encolan y un worker las ejecuta una por una, también en paralelo. La única
     * pausa del micrófono es mientras Ü habla, para no transcribirse a sí mismo.
     */
    private suspend fun listenLoop() {
        val log = MeetingLog(service.filesDir).also { meeting = it }
        val queue = Channel<String>(Channel.UNLIMITED).also { taskQueue = it }
        taskWorker = app.scope.launch {
            for (task in queue) {
                if (!docked) break
                log.taskStarted(task)
                LogBus.log("meeting", "🔨 ejecutando: \"${task.take(120)}\"")
                val result = runCatching { runTask(task) }
                log.taskDone(task, result.getOrElse { it.message ?: "error" }, ok = result.isSuccess)
                if (docked) {
                    withContext(Dispatchers.Main) { returnToCorner(dockLeft) }
                    delay(600) // deja aterrizar la burbuja
                }
            }
        }
        try {
            while (docked) {
                // Ü está hablando: micrófono cerrado hasta que termine (no se escucha a sí mismo).
                val quiet = quietUntil - System.currentTimeMillis()
                if (quiet > 0) { delay(quiet.coerceAtMost(500)); continue }

                listening = true
                withContext(Dispatchers.Main) {
                    setThinking(true)
                    showBadge("🎧 en la reunión · ${log.notes().size} notas · sácame de la esquina para terminar")
                }
                val t = defaultTranscriber(service)
                transcriber = t
                val transcript = runCatching { t.listen() }.getOrElse { "" }
                listening = false
                transcriber = null
                if (!docked) break
                if (transcript.isBlank()) { delay(250); continue } // silencio: se sigue escuchando

                LogBus.log("meeting", "🎧 \"${transcript.take(140)}\"")
                log.addSegment(transcript)
                // El cerebro piensa en paralelo: la escucha vuelve a abrirse de inmediato.
                app.scope.launch { think(log, queue, transcript) }
            }
        } finally {
            taskWorker?.cancel()
            queue.close()
            log.persist()
            meeting = null
            taskQueue = null
        }
    }

    /** Una jugada del cerebro de reunión. Serializada con mutex para que el estado quede en orden. */
    private suspend fun think(log: MeetingLog, queue: Channel<String>, segment: String) {
        val move = runCatching {
            app.meetingBrain.consider(segment, log.notes(), log.tasks(), log.elapsedMin(), log.closingDone)
        }.getOrNull() ?: return
        if (!docked) return
        thinkMutex.withLock {
            if (move.notes.isNotEmpty()) {
                log.addNotes(move.notes)
                LogBus.log("meeting", "📝 ${move.notes.joinToString(" · ")}")
            }
            if (move.closing && !log.closingDone) {
                log.closingDone = true
                LogBus.log("meeting", "🎬 detecté el cierre de la reunión: intervengo")
            }
            if (move.say.isNotBlank()) sayAloud(move.say)
            if (move.task.isNotBlank()) {
                log.taskQueued(move.task)
                LogBus.log("meeting", "⏳ en cola: \"${move.task.take(120)}\"")
                queue.trySend(move.task)
            }
        }
    }

    /** Habla por TTS y cierra el micrófono mientras tanto (estimación por longitud del texto). */
    private fun sayAloud(text: String) {
        quietUntil = System.currentTimeMillis() + (1800L + text.length * 65L).coerceAtMost(25_000L)
        transcriber?.stop() // corta el segmento actual: lo dicho hasta aquí ya quedó capturado
        speak(text)
    }

    private fun showBadge(text: String) {
        val b = badge ?: TextView(service).apply {
            setTextColor(Palette.bg)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(service.dp(14), service.dp(8), service.dp(14), service.dp(8))
            background = rounded(Palette.accent, service.dp(20).toFloat())
            elevation = 24f
        }.also {
            badge = it
            val p = WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = service.dp(130)
            }
            runCatching { wm.addView(it, p) }
        }
        b.text = text
    }

    private fun hideBadge() {
        badge?.let { runCatching { wm.removeView(it) } }
        badge = null
    }

    fun destroy() {
        docked = false
        transcriber?.stop()
        loopJob?.cancel()
        taskWorker?.cancel()
        meeting?.persist()
        disarm()
        hideBadge()
        MicService.stop(service)
    }
}
