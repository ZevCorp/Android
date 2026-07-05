package com.zevcorp.graph.platform

import graph.core.domain.LearnedTool
import graph.core.domain.LearnedToolRepository
import java.io.File
import kotlinx.serialization.json.Json

/** Persistencia simple de herramientas aprendidas: un JSON por herramienta en files/learned/. */
class LearnedToolRepo(root: File) : LearnedToolRepository {

    private val dir = File(root, "learned").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override fun save(tool: LearnedTool) {
        File(dir, "${sanitize(tool.name)}.json")
            .writeText(json.encodeToString(LearnedTool.serializer(), tool))
    }

    override fun list(): List<LearnedTool> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { json.decodeFromString(LearnedTool.serializer(), it.readText()) }.getOrNull() }
            ?: emptyList()

    private fun sanitize(s: String) =
        s.trim().lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("").trim('_').ifBlank { "tool_${System.currentTimeMillis()}" }
}
