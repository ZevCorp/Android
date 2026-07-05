package com.zevcorp.graph

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import com.zevcorp.graph.platform.AndroidSystemApi
import com.zevcorp.graph.platform.CloudSync
import com.zevcorp.graph.platform.GeminiBrain
import com.zevcorp.graph.platform.LearningInquiry
import com.zevcorp.graph.platform.MemoryDistiller
import com.zevcorp.graph.platform.MemoryStore
import com.zevcorp.graph.voice.IntentDistiller
import com.zevcorp.graph.platform.GeminiLearning
import com.zevcorp.graph.platform.GraphAccessibilityService
import com.zevcorp.graph.platform.LearnedToolRepo
import com.zevcorp.graph.platform.LogBus
import graph.core.application.ExecutionEngine
import graph.core.application.PassiveLearning
import graph.core.domain.ExecutionMode
import graph.core.domain.Mcp
import graph.core.domain.Phone
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Composition root: une el núcleo (motor mixto + MCP) con los adaptadores Android. */
class GraphApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: SharedPreferences

    /** La superficie del teléfono, viva mientras el servicio de accesibilidad esté activo. */
    @Volatile var ui: Phone? = null

    private val apiKey = { prefs.getString("apiKey", DEFAULT_API_KEY)?.ifBlank { DEFAULT_API_KEY } ?: DEFAULT_API_KEY }
    private val model = { prefs.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash" }

    private val bubble get() = (ui as? GraphAccessibilityService)?.bubble

    private val voice = object : Voice {
        override fun narrate(text: String) { bubble?.narrate(text) }
        override fun speak(text: String) { bubble?.speak(text) }
    }

    /** Herramientas aprendidas (se vuelven MCP ejecutables): disco local + copia en la nube. */
    val learnedTools by lazy {
        LearnedToolRepo(filesDir) { tool -> scope.launch(Dispatchers.IO) { CloudSync.push(tool) } }
    }

    /**
     * Enseñanza PASIVA: se alterna con el 🎓 de la burbuja. Observa los clics del usuario usando el
     * teléfono con normalidad y consolida el MCP de cada app cuando el usuario sale de ella. En el
     * modo iniciado por el usuario, el asistente puede interrumpir y preguntarle cosas por voz.
     */
    val passive by lazy {
        PassiveLearning(GeminiLearning(apiKey, model), learnedTools, voice, LogBus,
            inquirer = LearningInquiry(this, apiKey, model, voice, memories, scope,
                busy = { executing || bubble?.voiceBusy == true }))
    }

    /** Memoria durable: reglas/preferencias destiladas de cualquier input (local + nube). */
    val memories by lazy {
        MemoryStore(filesDir) { note -> scope.launch(Dispatchers.IO) { CloudSync.pushMemory(note) } }
    }
    private val memoryDistiller by lazy { MemoryDistiller(apiKey, model) }

    /** El "primer LLM" del pipeline de voz (repo Graph): destila la intención del transcript. */
    val intentDistiller by lazy { IntentDistiller(apiKey, model) }

    /** Pausa entre steps MCP enviados juntos, ajustable con la barra de velocidad de la app. */
    val stepDelay = { prefs.getInt("stepDelayMs", 350).toLong() }

    /* Parpadeos de la carita al CAMBIAR de vía: 1 = consciente (computer-use con screenshots),
       2 = subconsciente (MCP). Si repite la misma vía, no parpadea. */
    @Volatile private var lastSubconscious: Boolean? = null
    private val modeSignal = ExecutionMode { subconscious ->
        if (lastSubconscious != subconscious) {
            lastSubconscious = subconscious
            bubble?.blink(if (subconscious) 2 else 1)
        }
        // Las MCP por Intent no vuelan la burbuja (no hay coordenadas): pulso para que se vea viva.
        if (subconscious) bubble?.pulse()
    }

    private fun newBrain(mcp: Mcp) = GeminiBrain(apiKey, model, mcp.tools, listApps = {
        packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .joinToString(", ") { packageManager.getApplicationLabel(it).toString() }
    }, memory = { memories.promptBlock() })

    /**
     * Pídele algo por texto o voz. Gemini 3.5 Flash lo ejecuta con el motor mixto: elige entre
     * gestos MCP y computer-use. Devuelve el resumen final del asistente.
     */
    suspend fun run(prompt: String, user: UserChannel?): String {
        val surface = ui ?: return "Activa el servicio de accesibilidad de Graph"
        val service = surface as? GraphAccessibilityService ?: return "Servicio de accesibilidad inactivo"
        // En paralelo (nunca bloquea la ejecución): si el input enseña algo durable, se recuerda.
        scope.launch(Dispatchers.IO) {
            memoryDistiller.capture(prompt)?.let { note ->
                if (memories.add(note)) {
                    LogBus.log("memory", "🧠 recordado${if (note.app.isNotBlank()) " [${note.app}]" else ""}: ${note.note}")
                    voice.narrate("🧠 Lo recordaré")
                }
            }
        }
        // MCP = gestos de accesibilidad + acciones del sistema por Intent + herramientas aprendidas.
        val mcp = Mcp(service, AndroidSystemApi(service), learnedTools.list(), service, stepDelay, LogBus)
        val engine = ExecutionEngine(
            brain = { newBrain(mcp) },
            phone = surface,
            mcp = mcp,
            user = user,
            voice = voice,
            log = LogBus,
            mode = modeSignal,
            stepDelay = stepDelay,
        )
        return running { engine.run(prompt) }
    }

    /* ---------- Detener la ejecución (botón rojo flotante + notificación) ---------- */

    @Volatile private var runJob: Job? = null

    /** Hay una ejecución autónoma en curso: no es momento de que el aprendizaje interrumpa por voz. */
    val executing get() = runJob != null

    fun stopExecution() {
        LogBus.log("app", "⏹ detención solicitada por el usuario")
        runJob?.cancel(CancellationException("Detenida por ti ✋"))
    }

    /** Envuelve la ejecución: rastrea el Job para cancelarlo y muestra los controles de stop. */
    private suspend fun <T> running(block: suspend () -> T): T {
        runJob = kotlin.coroutines.coroutineContext[Job]
        bubble?.companion(true)
        bubble?.showStop(true)
        notifyRunning(true)
        // Toda ejecución autónoma también aprende: los taps del asistente son señales igual de
        // válidas que los del usuario. Se enciende en silencio y al terminar consolida aparte
        // (en background, para no retrasar la respuesta). Si el 🎓 ya estaba activo, no se toca.
        val autoLearn = !passive.active
        if (autoLearn) passive.start(quiet = true)
        try {
            return block()
        } finally {
            runJob = null
            bubble?.companion(false)
            bubble?.showStop(false)
            notifyRunning(false)
            if (autoLearn && passive.active) scope.launch(Dispatchers.IO) { passive.stop(quiet = true) }
        }
    }

    private fun notifyRunning(on: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!on) { nm.cancel(2); return }
        nm.createNotificationChannel(NotificationChannel("run", "Ejecución", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getBroadcast(
            this, 0, Intent("com.zevcorp.graph.STOP").setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(this, "run")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Graph está ejecutando")
            .setContentText("Toca para detener")
            .setOngoing(true)
            .setColor(0xFFE5534B.toInt())
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "⏹ Detener", stop).build())
            .build())
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("graph", MODE_PRIVATE)
        // Trae los aprendizajes y la memoria de la nube (y sube lo local que falte allá).
        scope.launch(Dispatchers.IO) {
            learnedTools.syncFromCloud()
            memories.syncFromCloud(CloudSync.pullMemory())
        }
    }

    companion object {
        lateinit var instance: GraphApp

        /** API key por defecto (proyecto "Devable AI"), inyectada al compilar — nunca vive en git. */
        const val DEFAULT_API_KEY = BuildConfig.DEFAULT_API_KEY
    }
}
