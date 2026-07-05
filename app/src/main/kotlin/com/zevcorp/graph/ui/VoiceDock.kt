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
import com.zevcorp.graph.voice.DeepgramTranscriber
import com.zevcorp.graph.voice.SystemTranscriber
import com.zevcorp.graph.voice.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zonas de activación por voz en las DOS esquinas superiores: si la burbuja se arrastra hasta una
 * esquina y se queda ahí ~2.5 s, se enciende la escucha del micrófono con feedback visual. El audio
 * pasa por el pipeline del repo Graph (transcripción Deepgram → LLM destilador de intención) y la
 * intención destilada se entrega al Execution Engine. Aislado de FloatingBubble: la burbuja solo
 * reporta posiciones y toques.
 */
class VoiceDock(
    private val service: AccessibilityService,
    /** Cara pensativa on/off durante la escucha. */
    private val setThinking: (Boolean) -> Unit,
    /** Globo de narración de la burbuja. */
    private val narrate: (String) -> Unit,
    /** Entrega la intención destilada al motor de ejecución. */
    private val runIntent: (String) -> Unit,
) {

    private val app get() = GraphApp.instance
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var badge: TextView? = null
    private var armTimer: Runnable? = null
    private var transcriber: Transcriber? = null

    @Volatile var listening = false
        private set

    private val zone get() = service.dp(120)

    /** ¿El centro de la burbuja está en una esquina superior (la posición más alta posible)? */
    fun inZone(cx: Int, cy: Int): Boolean {
        val w = service.resources.displayMetrics.widthPixels
        return cy < zone && (cx < zone || cx > w - zone)
    }

    /** La burbuja se movió (arrastre o encaje): arma o cancela la cuenta de 2.5 s. */
    fun track(cx: Int, cy: Int) {
        if (listening) return
        if (inZone(cx, cy)) arm() else cancel()
    }

    private fun arm() {
        if (armTimer != null) return
        setThinking(true)
        showBadge("🎤 quieta ahí… te escucho en 2.5 s")
        val t = Runnable { armTimer = null; startListening() }
        armTimer = t
        handler.postDelayed(t, 2500)
    }

    /** La burbuja salió de la esquina (o empezó una ejecución): se desarma sin escuchar. */
    fun cancel() {
        armTimer?.let { handler.removeCallbacks(it) }
        armTimer = null
        if (!listening) { hideBadge(); setThinking(false) }
    }

    /** Toque en la burbuja durante la escucha: corta la grabación y procesa lo que haya. */
    fun stopNow() {
        transcriber?.stop()
    }

    private fun startListening() {
        listening = true
        setThinking(true)
        showBadge("🎤 te escucho… toca la burbuja al terminar")
        val key = app.prefs.getString("deepgramKey", "")?.trim().orEmpty()
        val t = if (key.isNotBlank()) DeepgramTranscriber(key) else SystemTranscriber(service)
        transcriber = t
        MicService.start(service)
        LogBus.log("voice", "▶ escucha por esquina · ${if (key.isNotBlank()) "deepgram nova-3" else "reconocedor del sistema"}")
        app.scope.launch {
            val transcript = runCatching { t.listen() }.getOrElse { "" }
            withContext(Dispatchers.Main) {
                MicService.stop(service)
                listening = false
                transcriber = null
                if (transcript.isBlank()) {
                    hideBadge(); setThinking(false)
                    narrate("No te escuché 🤔")
                    return@withContext
                }
                showBadge("✨ entendiendo…")
                LogBus.log("voice", "transcripción: \"${transcript.take(140)}\"")
            }
            if (transcript.isBlank()) return@launch
            val intent = runCatching { app.intentDistiller.distill(transcript) }.getOrNull()
            withContext(Dispatchers.Main) {
                hideBadge(); setThinking(false)
                if (intent == null) {
                    narrate("Te oí, pero no había nada que hacer 🙂")
                    LogBus.log("voice", "sin intención accionable")
                } else {
                    LogBus.log("voice", "intención destilada: \"${intent.take(140)}\"")
                    runIntent(intent)
                }
            }
        }
    }

    private fun showBadge(text: String) {
        val b = badge ?: TextView(service).apply {
            setTextColor(Color.WHITE)
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
        cancel()
        stopNow()
        hideBadge()
    }
}
