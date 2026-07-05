package com.zevcorp.graph.platform

import graph.core.domain.LearnedTool
import graph.core.domain.LearnedToolRepository
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Persistencia de los mapas aprendidos: un JSON por herramienta en files/learned/ (fuente en
 * caliente) + copia en la nube vía CloudSync (push al guardar, pull/merge al arrancar).
 */
class LearnedToolRepo(root: File, private val pushToCloud: (LearnedTool) -> Unit = {}) : LearnedToolRepository {

    private val dir = File(root, "learned").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override fun save(tool: LearnedTool) {
        saveLocal(tool)
        pushToCloud(tool)
    }

    fun saveLocal(tool: LearnedTool) {
        File(dir, "${sanitize(tool.name)}.json")
            .writeText(json.encodeToString(LearnedTool.serializer(), tool))
    }

    override fun list(): List<LearnedTool> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString(LearnedTool.serializer(), it.readText()) }.getOrNull() }
            ?: emptyList()

    /**
     * Sincronización de arranque (llamar desde IO): baja la nube y la escribe en local (la nube
     * gana: es lo último consolidado desde cualquier dispositivo), y sube lo local que la nube
     * aún no tenga (p.ej. aprendizajes hechos sin conexión).
     */
    fun syncFromCloud() {
        val remote = CloudSync.pull()
        remote.forEach { saveLocal(it) }
        val remoteNames = remote.map { it.name }.toSet()
        list().filter { it.name !in remoteNames }.forEach { pushToCloud(it) }
        if (remote.isNotEmpty()) LogBus.log("cloud", "☁ ${remote.size} aprendizajes sincronizados desde la nube")
    }

    private fun sanitize(s: String) =
        s.trim().lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("").trim('_').ifBlank { "tool_${System.currentTimeMillis()}" }
}
