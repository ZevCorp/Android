package com.zevcorp.graph

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import com.zevcorp.graph.platform.ActiveLearning
import com.zevcorp.graph.platform.AndroidSystemApi
import com.zevcorp.graph.platform.Anticipation
import com.zevcorp.graph.platform.CloudSync
import com.zevcorp.graph.platform.GeminiBrain
import com.zevcorp.graph.platform.GeminiVideo
import com.zevcorp.graph.platform.LearningInquiry
import com.zevcorp.graph.platform.MemoryDistiller
import com.zevcorp.graph.platform.MemoryStore
import com.zevcorp.graph.platform.Updater
import com.zevcorp.graph.platform.UsageU
import com.zevcorp.graph.ui.Palette
import com.zevcorp.graph.ui.ThemeMode
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
     * Enseñanza PASIVA: se activa/desactiva desde la APP PRINCIPAL (antes en la burbuja). Observa los
     * clics del usuario usando el teléfono con normalidad y consolida el MCP de cada app cuando el
     * usuario sale de ella. En el modo iniciado por el usuario, el asistente puede interrumpir y
     * preguntarle cosas por voz.
     */
    val passive by lazy {
        PassiveLearning(GeminiLearning(apiKey, model), learnedTools, voice, LogBus,
            inquirer = LearningInquiry(apiKey, model, memories, scope,
                busy = { executing || bubble?.voiceBusy == true || activeLearning.busy },
                askByVoice = { q -> bubble?.askAloud(q) ?: "" },
                runTask = { task -> scope.launch { runCatching { run(task, bubble) } } }))
    }

    /**
     * Enseñanza ACTIVA: se activa TOCANDO el 🎓 de la burbuja. Comparte pantalla (video + audio), y al
     * terminar procesa TODO el video con Gemini para estructurar conocimiento textual por app en la
     * capa MCP (memoria durable). Fase 1: sin árbol de UI, solo texto de cómo usar las apps.
     */
    val activeLearning by lazy {
        ActiveLearning(this, GeminiVideo(apiKey, model), memories, voice,
            askAloud = { q -> bubble?.askAloud(q) ?: "" }, apiKey = apiKey, model = model)
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

    private val anticipation by lazy { Anticipation(apiKey, model) }

    /** Prompts que componen el objetivo vivo: crecen si llega un audio nuevo durante la ejecución. */
    private val goalPrompts = mutableListOf<String>()

    /**
     * HILO DE CONVERSACIÓN PERSISTENTE (previous_interaction_id). TODAS las activaciones —micrófono
     * flotante, texto, esquinas y botón de encendido— comparten el MISMO contexto para dar
     * continuidad ("¿qué acabas de hacer?", responder a lo anterior…). Las ejecuciones también viven
     * aquí. Se rota a una ventana nueva solo al acercarse al límite (la memoria durable sobrevive).
     */
    @Volatile var conversationId = ""
        private set
    @Volatile private var conversationTokens = 0
    private val maxContextTokens = 400_000

    /**
     * Crea un motor y su cerebro. Con `resume`, el cerebro CONTINÚA el hilo compartido (no reenvía el
     * system prompt: el servidor ya lo tiene) para que haya continuidad entre activaciones; si no,
     * arranca un hilo fresco. Devuelve ambos para poder guardar el id/tokens del hilo al terminar.
     */
    private fun newSession(surface: Phone, service: GraphAccessibilityService, user: UserChannel?, resume: Boolean, maxTurns: Int = 40): Pair<ExecutionEngine, GeminiBrain> {
        val mcp = Mcp(service, AndroidSystemApi(service), learnedTools.list(), service, stepDelay, LogBus)
        val brain = newBrain(mcp)
        if (resume) brain.resume(conversationId)
        val engine = ExecutionEngine(
            brain = { brain }, phone = surface, mcp = mcp, user = user,
            voice = voice, log = LogBus, mode = modeSignal, stepDelay = stepDelay, maxTurns = maxTurns,
        )
        return engine to brain
    }

    private fun buildGoal(prompts: List<String>): String =
        if (prompts.size == 1) prompts[0]
        else "El usuario te pidió esto, en orden, mientras ejecutabas:\n" +
            prompts.mapIndexed { i, p -> "${i + 1}) \"$p\"" }.joinToString("\n") +
            "\nReinterpreta TODO como UN SOLO objetivo y complétalo. Las instrucciones posteriores " +
            "pueden anular, modificar o ampliar las anteriores; usa tu criterio para decidir qué hacer con todo."

    /**
     * Pídele algo por texto o voz. Gemini 3.5 Flash lo ejecuta con el motor mixto. Si mientras
     * ejecuta llega otro audio (augmentExecution), se cancela y REINTERPRETA ambos prompts juntos.
     * Al terminar, una cadena de pensamiento breve decide si anticipar una acción segura.
     */
    suspend fun run(prompt: String, user: UserChannel?): String {
        val surface = ui ?: return "Activa el servicio de accesibilidad de Ü"
        val service = surface as? GraphAccessibilityService ?: return "Servicio de accesibilidad inactivo"
        // Rotación de ventana de contexto: al superar el umbral se abre un hilo nuevo (la memoria
        // durable sobrevive). Solo aplica al empezar; no interrumpe nada en curso.
        if (conversationTokens >= maxContextTokens) {
            LogBus.log("run", "🧠 ventana de contexto nueva (el hilo llegó a $conversationTokens tokens)")
            conversationId = ""; conversationTokens = 0
        }
        // En paralelo (nunca bloquea la ejecución): si el input enseña algo durable, se recuerda.
        scope.launch(Dispatchers.IO) {
            memoryDistiller.capture(prompt)?.let { note ->
                if (memories.add(note)) {
                    LogBus.log("memory", "🧠 recordado${if (note.app.isNotBlank()) " [${note.app}]" else ""}: ${note.note}")
                    voice.narrate("Lo recordaré")
                }
            }
        }
        synchronized(goalPrompts) { goalPrompts.clear(); goalPrompts.add(prompt.trim()) }
        return running {
            var summary = ""
            var round = 0
            // Bucle de reencaminado: cada audio nuevo cancela el motor y se reinterpreta todo junto.
            while (true) {
                val (goal, builtCount) = synchronized(goalPrompts) { buildGoal(goalPrompts.toList()) to goalPrompts.size }
                bubble?.showExecutionMic(true)
                val (engine, brain) = newSession(surface, service, user, resume = true)
                val holder = arrayOf("")
                val announce = round == 0 // en reencaminados no narra el objetivo largo
                val child = CoroutineScope(kotlin.coroutines.coroutineContext).launch {
                    holder[0] = try { engine.run(goal, announce) }
                        catch (ce: CancellationException) { throw ce }
                        catch (t: Throwable) { LogBus.log("run", "motor: ${t.message}"); "Tuve un problema con eso." }
                }
                engineCanceller = { child.cancel(CancellationException("reencaminar")) }
                child.join()
                engineCanceller = null
                // Persiste el hilo compartido para que la próxima activación continúe la conversación.
                conversationId = brain.interactionId
                conversationTokens = brain.totalTokens
                val grew = synchronized(goalPrompts) { goalPrompts.size > builtCount }
                if (!grew) { summary = holder[0]; break }
                round++
                LogBus.log("run", "↻ reencaminando: ahora son ${synchronized(goalPrompts) { goalPrompts.size }} prompts")
                voice.narrate("Ok, lo ajusto sobre la marcha")
            }
            bubble?.showExecutionMic(false)
            // El amigo prevenido: ¿alguna acción de certeza total que convenga hacer justo ahora?
            anticipate(surface, service, user, summary)
            summary
        }
    }

    /** Cadena de pensamiento breve al terminar → acción autónoma segura y/o aviso hablado. */
    private suspend fun anticipate(surface: Phone, service: GraphAccessibilityService, user: UserChannel?, summary: String) {
        val request = synchronized(goalPrompts) { goalPrompts.joinToString(" · ") }
        val tools = Mcp(service, AndroidSystemApi(service), learnedTools.list()).tools.joinToString(", ") { it.name }
        val foresight = runCatching { anticipation.consider(request, summary, tools) }.getOrNull() ?: return
        if (foresight.say.isNotBlank()) voice.speak(foresight.say)
        if (foresight.task.isNotBlank()) {
            LogBus.log("run", "🤝 acción anticipada: ${foresight.task}")
            val goal = "ACCIÓN PREVENTIVA AUTÓNOMA (el usuario no la pidió explícito pero es de " +
                "certeza total y le conviene): ${foresight.task}. Hazla de forma directa y para."
            runCatching { newSession(surface, service, user, resume = false, maxTurns = 12).first.run(goal, announce = false) }
                .onFailure { LogBus.log("run", "acción anticipada falló: ${it.message}") }
        }
    }

    /* ---------- Detener la ejecución (botón rojo flotante + notificación) ---------- */

    @Volatile private var runJob: Job? = null
    @Volatile private var engineCanceller: (() -> Unit)? = null

    /** Hay una ejecución autónoma en curso: no es momento de que el aprendizaje interrumpa por voz. */
    val executing get() = runJob != null

    fun stopExecution() {
        LogBus.log("app", "⏹ detención solicitada por el usuario")
        runJob?.cancel(CancellationException("Detenida por ti ✋"))
    }

    /**
     * Un audio nuevo llegó mientras el asistente ejecutaba: NO se encola: se añade al objetivo y el
     * motor se reinterpreta desde cero con AMBOS prompts (uno puede anular o modificar el otro). Si
     * ya no hay ejecución, arranca una normal.
     */
    fun augmentExecution(extra: String) {
        val e = extra.trim()
        if (e.isBlank()) return
        if (executing) {
            synchronized(goalPrompts) { goalPrompts.add(e) }
            LogBus.log("run", "＋ audio durante ejecución: \"${e.take(80)}\"")
            engineCanceller?.invoke()
        } else {
            scope.launch { runCatching { run(e, bubble) } }
        }
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
        val startedAt = System.currentTimeMillis()
        try {
            return block()
        } finally {
            runJob = null
            engineCanceller = null
            bubble?.companion(false)
            bubble?.showStop(false)
            bubble?.showExecutionMic(false) // limpia el micrófono de ejecución también si te detienen
            bubble?.stopExecLive(announce = false) // la escucha en vivo muere con su ejecución
            notifyRunning(false)
            if (autoLearn && passive.active) scope.launch(Dispatchers.IO) { passive.stop(quiet = true) }
            // Tiempo que Ü usó el dispositivo por ti → alimenta la gráfica del modo usuario.
            val elapsed = System.currentTimeMillis() - startedAt
            prefs.edit().putLong(UsageU.KEY_U_ACTIVE_MS,
                prefs.getLong(UsageU.KEY_U_ACTIVE_MS, 0L) + elapsed).apply()
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
            .setContentTitle("Ü está ejecutando")
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
        installCrashReporter()
        prefs = getSharedPreferences("graph", MODE_PRIVATE)
        // Tema guardado (claro por defecto): cara y app en blanco/negro, sin azul.
        Palette.mode = runCatching { ThemeMode.valueOf(prefs.getString("theme", ThemeMode.LIGHT.name)!!) }
            .getOrDefault(ThemeMode.LIGHT)
        // Trae los aprendizajes y la memoria de la nube (y sube lo local que falte allá).
        scope.launch(Dispatchers.IO) {
            learnedTools.syncFromCloud()
            memories.syncFromCloud(CloudSync.pullMemory())
        }
        // Sondeo de actualizaciones: al arrancar y cada ~30 min. El proceso sigue vivo por el servicio
        // de accesibilidad, así que el aviso de "hay actualización" llega casi como un push.
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { Updater.checkAndNotify(this@GraphApp) }
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
    }

    /**
     * Si algo revienta (arranque incluido), en vez de cerrarse en silencio se guarda la traza en
     * files/last_crash.txt y se abre CrashActivity mostrándola (en su propio proceso). Así se puede
     * capturar el error exacto aunque no haya cable/logcat.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
            runCatching { java.io.File(filesDir, "last_crash.txt").writeText(trace) }
            runCatching {
                startActivity(
                    Intent(this, com.zevcorp.graph.ui.CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(com.zevcorp.graph.ui.CrashActivity.EXTRA_TRACE, trace))
            }
            runCatching { previous?.uncaughtException(thread, error) }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }
    }

    companion object {
        lateinit var instance: GraphApp

        /** API key por defecto (proyecto "Devable AI"), inyectada al compilar — nunca vive en git. */
        const val DEFAULT_API_KEY = BuildConfig.DEFAULT_API_KEY
    }
}
