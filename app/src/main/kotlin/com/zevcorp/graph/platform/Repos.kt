package com.zevcorp.graph.platform

import graph.core.domain.*
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

private class JsonDir<T>(private val dir: File, private val serializer: KSerializer<T>) {
    init { dir.mkdirs() }
    fun save(id: String, value: T) = File(dir, "$id.json").writeText(json.encodeToString(serializer, value))
    fun get(id: String): T? = File(dir, "$id.json").takeIf { it.exists() }
        ?.let { json.decodeFromString(serializer, it.readText()) }
    fun list(): List<T> = dir.listFiles { f -> f.extension == "json" }
        ?.sortedBy { it.name }?.map { json.decodeFromString(serializer, it.readText()) } ?: emptyList()
}

class FileLessonRepo(root: File) : LessonRepository {
    private val store = JsonDir(File(root, "lessons"), Lesson.serializer())
    override fun save(lesson: Lesson) = store.save(lesson.id, lesson)
    override fun list(): List<Lesson> = store.list()
}

class FileWorkflowRepo(private val root: File) : WorkflowRepository {
    private val store = JsonDir(File(root, "workflows"), Workflow.serializer())
    override fun get(id: String): Workflow? = store.get(id)
    override fun list(): List<Workflow> = store.list()

    override fun save(workflow: Workflow) {
        store.save(workflow.id, workflow)
        File(root, "WORKFLOWS.md").writeText(catalog(list()))
    }

    /** Catálogo legible, mismo formato que WORKFLOWS.md del repo Graph. */
    private fun catalog(all: List<Workflow>) = buildString {
        appendLine("# Registered Workflows")
        for (w in all) {
            appendLine("\n## ${w.id}\n")
            appendLine("- Purpose: ${w.purpose}")
            appendLine("- CLI: `graph run ${w.id} ${w.variables.joinToString(" ") { "--${it.name}=\"...\"" }}`")
            if (w.variables.isNotEmpty()) {
                appendLine("\n### Variables")
                w.variables.forEach { appendLine("- `${it.name}`: field=\"${it.field}\" (default: `${it.default}`)") }
            }
            appendLine("\n### Steps")
            w.steps.forEach { s ->
                val value = if (s.value.isNotBlank()) " | value=\"${s.value}\"" else ""
                appendLine("- ${s.order}. ${s.action} ${s.selector.short()} | label=\"${s.label}\"$value | screen=${s.screen} | source=${s.source}")
            }
        }
    }
}
