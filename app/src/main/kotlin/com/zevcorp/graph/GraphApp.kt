package com.zevcorp.graph

import android.app.Application
import android.content.SharedPreferences
import com.zevcorp.graph.platform.*
import graph.core.application.*
import graph.core.domain.Lesson
import graph.core.domain.RunReporter
import graph.core.domain.UiSurface
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import graph.core.domain.Workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root: une el núcleo multiplataforma con los adaptadores Android. */
class GraphApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: SharedPreferences

    /** La superficie de UI viva mientras el servicio de accesibilidad esté activo. */
    @Volatile var ui: UiSurface? = null

    /** Grabación de tutorial en curso (compartido entre la app y la burbuja flotante). */
    @Volatile var teachingActive = false

    private val apiKey = { prefs.getString("apiKey", DEFAULT_API_KEY)?.ifBlank { DEFAULT_API_KEY } ?: DEFAULT_API_KEY }
    private val model = { prefs.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash" }

    val lessons by lazy { FileLessonRepo(filesDir) }
    val workflows by lazy { FileWorkflowRepo(filesDir) }
    private val newId = { "wf_${System.currentTimeMillis()}" }

    private fun newBrain() = GeminiComputerUse(apiKey, model, listApps = {
        packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .joinToString(", ") { packageManager.getApplicationLabel(it).toString() }
    })

    private val bubble get() = (ui as? GraphAccessibilityService)?.bubble
    // Voz dinámica: siempre reenvía a la burbuja viva del momento (o no-op si no hay).
    private val voice = object : Voice {
        override fun narrate(text: String) { bubble?.narrate(text) }
        override fun speak(text: String) { bubble?.speak(text) }
    }
    val dashboard by lazy { DashboardSync(prefs, scope) }
    private val reporter = RunReporter { wf, active, note -> dashboard.report(wf, active, note) }

    val teaching by lazy {
        TeachingStage(DroidScreenRecorder(this), GeminiTutorialAnalyzer(apiKey, model),
            lessons, workflows, { ui }, newId, GeminiCurator(apiKey, model),
            brain = ::newBrain, voice = voice, user = bubble, log = LogBus)
    }

    private fun execution(user: UserChannel?) = ExecutionStage(
        brain = ::newBrain,
        ui = requireNotNull(ui) { "Servicio de accesibilidad inactivo" },
        user = user,
        workflows = workflows,
        newId = newId,
        voice = voice,
        report = reporter,
        log = LogBus,
    )

    private fun bubbleCompanion(on: Boolean) = bubble?.companion(on)

    /* ---------- Detener la ejecución (botón rojo flotante, notificación, cli/graph stop) ---------- */

    @Volatile private var runJob: kotlinx.coroutines.Job? = null

    fun stopExecution() {
        LogBus.log("app", "⏹ detención solicitada por el usuario")
        runJob?.cancel(kotlinx.coroutines.CancellationException("Detenida por ti ✋"))
    }

    /** Envuelve toda ejecución: rastrea el Job para poder cancelarlo y muestra los controles de stop. */
    private suspend fun <T> running(block: suspend () -> T): T {
        runJob = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        bubbleCompanion(true)
        bubble?.showStop(true)
        notifyRunning(true)
        try {
            return block()
        } finally {
            runJob = null
            bubbleCompanion(false)
            bubble?.showStop(false)
            notifyRunning(false)
        }
    }

    private fun notifyRunning(on: Boolean) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (!on) { nm.cancel(2); return }
        nm.createNotificationChannel(android.app.NotificationChannel(
            "run", "Ejecución", android.app.NotificationManager.IMPORTANCE_HIGH))
        val stop = android.app.PendingIntent.getBroadcast(
            this, 0, android.content.Intent("com.zevcorp.graph.STOP").setPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, android.app.Notification.Builder(this, "run")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Graph está ejecutando")
            .setContentText("Toca para detener")
            .setOngoing(true)
            .setColor(0xFFE5534B.toInt())
            .setContentIntent(stop) // tocar la notificación completa también detiene
            .addAction(android.app.Notification.Action.Builder(null, "⏹ Detener", stop).build())
            .build())
    }

    /** Ejecuta la lección (con burbuja acompañante). Aprende en vivo sobre el workflow. */
    suspend fun runLearning(lesson: Lesson, user: UserChannel): Workflow =
        running { execution(user).runLesson(lesson) }

    /**
     * Pídele algo por texto/voz SIN video previo: se ejecuta con el mismo motor (Gemini computer use)
     * y, como cualquier ejecución, va alimentando el aprendizaje por workflow (steps DRAFT que un
     * learning posterior consolida). Solo cambia la UX: el prompt reemplaza al tutorial en video.
     */
    suspend fun runPrompt(prompt: String, user: UserChannel): Workflow {
        // No se guarda ninguna lección: para un prompt el único artefacto es el workflow.
        val lesson = Lesson(id = "ask_${System.currentTimeMillis()}", goal = prompt, summary = prompt)
        return running { execution(user).runLesson(lesson) }
    }

    /** Ejecución unificada por id (terminal/UI): verdes por árbol de UI, no-verdes con Gemini. */
    suspend fun runWorkflow(
        id: String,
        inputs: Map<String, String>,
        branches: Set<String> = emptySet(),
        depth: Int = Int.MAX_VALUE,
    ): String {
        if (ui == null) return "Servicio de accesibilidad inactivo: actívalo en Ajustes"
        return running { execution(bubble).execute(id, inputs, branches, depth) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("graph", MODE_PRIVATE)
    }

    companion object {
        lateinit var instance: GraphApp

        /** API key por defecto (proyecto "Devable AI"), inyectada al compilar desde apikey.properties — nunca vive en git. */
        const val DEFAULT_API_KEY = BuildConfig.DEFAULT_API_KEY
    }
}
