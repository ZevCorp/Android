package com.zevcorp.graph.platform

import graph.core.domain.Workflow
import graph.core.domain.WorkflowRepository
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Persistencia de los workflows aprendidos: un JSON por workflow en files/workflows/ (fuente en
 * caliente) + copia en la nube vía CloudSync (push al guardar, pull/merge al arrancar).
 */
class WorkflowRepo(root: File, private val pushToCloud: (Workflow) -> Unit = {}) : WorkflowRepository {

    private val dir = File(root, "workflows").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override fun save(workflow: Workflow) {
        saveLocal(workflow)
        pushToCloud(workflow)
    }

    fun saveLocal(workflow: Workflow) {
        File(dir, "${sanitize(workflow.name)}.json")
            .writeText(json.encodeToString(Workflow.serializer(), workflow))
    }

    override fun list(): List<Workflow> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString(Workflow.serializer(), it.readText()) }.getOrNull() }
            ?: emptyList()

    /** Sincronización de arranque (llamar desde IO): la nube gana; sube lo local que falte allá. */
    fun syncFromCloud() {
        val remote = CloudSync.pullWorkflows()
        remote.forEach { saveLocal(it) }
        val remoteNames = remote.map { it.name }.toSet()
        list().filter { it.name !in remoteNames }.forEach { pushToCloud(it) }
        if (remote.isNotEmpty()) LogBus.log("cloud", "☁ ${remote.size} workflows sincronizados desde la nube")
    }

    private fun sanitize(s: String) =
        s.trim().lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("").trim('_').ifBlank { "workflow_${System.currentTimeMillis()}" }
}
