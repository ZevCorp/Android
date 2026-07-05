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
import com.zevcorp.graph.platform.MicService
import com.zevcorp.graph.voice.Transcriber
import com.zevcorp.graph.voice.defaultTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zonas de activación por voz en las DOS esquinas superiores. Arrastrar la burbuja a una esquina y
 * dejarla ~2.5 s enciende la ESCUCHA PERMANENTE: un bucle transcribe → destila → ejecuta que se
 * repite hasta que el usuario saca la burbuja de la esquina. El silencio o una frase sin intención
 * NO apagan el modo: se sigue escuchando. Tras cada ejecución la burbuja vuelve sola a su esquina.
 * El audio sigue el pipeline del repo Graph: Deepgram nova-3 (o reconocedor del sistema) → LLM
 * destilador de intención → Execution Engine.
 */
class VoiceDock(
    private val service: AccessibilityService,
    /** Cara pensativa on/off. */
    private val setThinking: (Boolean) -> Unit,
    /** Globo de narración de la burbuja. */
    private val narrate: (String) -> Unit,
    /** Ejecuta la intención destilada y SUSPENDE hasta que el motor termina. */
    private val runIntent: suspend (String) -> Unit,
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
        showBadge("🎤 mantén el dedo aquí… escucha en 2.5 s")
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
        LogBus.log("voice", "▶ escucha PERMANENTE en la esquina ${if (dockLeft) "izquierda" else "derecha"}")
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

    /** Sale del modo permanente. Si hay una ejecución en curso, el bucle termina al acabar esta. */
    private fun undock() {
        docked = false
        transcriber?.stop()
        disarm()
        LogBus.log("voice", "■ escucha permanente desactivada (burbuja fuera de la esquina)")
        narrate("Listo, dejo de escuchar 👂")
        if (!listening) { hideBadge(); setThinking(false) }
    }

    /** El bucle permanente: escucha → destila → ejecuta → vuelve a la esquina → repite. */
    private suspend fun listenLoop() {
        while (docked) {
            listening = true
            withContext(Dispatchers.Main) {
                setThinking(true)
                showBadge("🎤 te escucho… sácame de la esquina para parar")
            }
            val t = defaultTranscriber(service)
            transcriber = t
            val transcript = runCatching { t.listen() }.getOrElse { "" }
            listening = false
            transcriber = null
            if (!docked) break

            if (transcript.isBlank()) { delay(250); continue } // silencio: se sigue escuchando
            withContext(Dispatchers.Main) { showBadge("✨ entendiendo…") }
            LogBus.log("voice", "transcripción: \"${transcript.take(140)}\"")

            val intent = runCatching { app.intentDistiller.distill(transcript) }.getOrNull()
            if (!docked) break
            if (intent == null) {
                // Nada accionable (charla de fondo, saludos…): NO se apaga, se sigue escuchando.
                LogBus.log("voice", "sin intención accionable · sigo escuchando")
                continue
            }

            LogBus.log("voice", "intención destilada: \"${intent.take(140)}\"")
            withContext(Dispatchers.Main) { hideBadge(); setThinking(false) }
            runCatching { runIntent(intent) } // suspende hasta que el motor termina
            if (!docked) break
            withContext(Dispatchers.Main) { returnToCorner(dockLeft) }
            delay(600) // deja aterrizar la burbuja antes de reabrir el micrófono
        }
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
        disarm()
        hideBadge()
        MicService.stop(service)
    }
}
