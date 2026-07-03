package com.zevcorp.graph

import android.app.Application
import android.content.SharedPreferences
import com.zevcorp.graph.platform.*
import graph.core.application.*
import graph.core.domain.UiSurface
import graph.core.domain.UserChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Composition root: une el núcleo multiplataforma con los adaptadores Android. */
class GraphApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: SharedPreferences

    /** La superficie de UI viva mientras el servicio de accesibilidad esté activo. */
    @Volatile var ui: UiSurface? = null

    private val apiKey = { prefs.getString("apiKey", "") ?: "" }
    private val model = { prefs.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash" }

    val lessons by lazy { FileLessonRepo(filesDir) }
    val workflows by lazy { FileWorkflowRepo(filesDir) }
    val teaching by lazy { TeachingStage(DroidScreenRecorder(this), GeminiTutorialAnalyzer(apiKey, model), lessons) }

    fun learning(user: UserChannel) = LearningStage(
        brain = GeminiComputerUse(apiKey, model, listApps = {
            packageManager.getInstalledApplications(0)
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .joinToString(", ") { packageManager.getApplicationLabel(it).toString() }
        }),
        ui = requireNotNull(ui) { "Servicio de accesibilidad inactivo" },
        user = user,
        workflows = workflows,
        newId = { "wf_${System.currentTimeMillis()}" },
    )

    suspend fun runWorkflow(id: String, inputs: Map<String, String>): String {
        val surface = ui ?: return "Servicio de accesibilidad inactivo: actívalo en Ajustes"
        return SubconsciousStage(surface, workflows).run(id, inputs)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("graph", MODE_PRIVATE)
    }

    companion object { lateinit var instance: GraphApp }
}
