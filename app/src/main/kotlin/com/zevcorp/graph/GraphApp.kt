package com.zevcorp.graph

import android.app.Application
import android.content.SharedPreferences
import com.zevcorp.graph.platform.*
import graph.core.application.*
import graph.core.domain.Lesson
import graph.core.domain.UiSurface
import graph.core.domain.UserChannel
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

    val teaching by lazy {
        TeachingStage(DroidScreenRecorder(this), GeminiTutorialAnalyzer(apiKey, model),
            lessons, workflows, { ui }, newId, LogBus)
    }

    private fun bubbleCompanion(on: Boolean) = (ui as? GraphAccessibilityService)?.bubble?.companion(on)

    /** Etapa 2 con la burbuja en modo acompañante (vuela a cada clic, pass-through, se restaura al final). */
    suspend fun runLearning(lesson: Lesson, user: UserChannel): Workflow {
        val stage = LearningStage(
            brain = newBrain(),
            ui = requireNotNull(ui) { "Servicio de accesibilidad inactivo" },
            user = user,
            workflows = workflows,
            newId = newId,
            log = LogBus,
        )
        bubbleCompanion(true)
        try {
            return stage.run(lesson)
        } finally {
            bubbleCompanion(false)
        }
    }

    suspend fun runWorkflow(id: String, inputs: Map<String, String>): String {
        val surface = ui ?: return "Servicio de accesibilidad inactivo: actívalo en Ajustes"
        bubbleCompanion(true)
        try {
            return SubconsciousStage(surface, workflows, ::newBrain, LogBus).run(id, inputs)
        } finally {
            bubbleCompanion(false)
        }
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
