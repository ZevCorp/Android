package com.zevcorp.graph.platform

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * El registro vivo de una reunión del modo escucha por esquinas: transcripción con hora, notas
 * importantes y tareas lanzadas al motor. Se persiste como markdown en files/meetings/ en cada
 * cambio, para que el modo usuario pueda abrir la transcripción después (pantalla futura).
 */
class MeetingLog(root: File) {

    val startedAt: Long = System.currentTimeMillis()
    @Volatile var closingDone = false

    private val dir = File(root, "meetings").apply { mkdirs() }
    private val file = File(dir, "reunion_${stamp("yyyyMMdd_HHmm", startedAt)}.md")

    private val segments = mutableListOf<Pair<Long, String>>()
    private val notesList = mutableListOf<String>()
    /** "⏳ en cola: …" · "▶ en curso: …" · "✔ …  → resultado" · "✖ …  → error" */
    private val taskLines = mutableListOf<String>()

    private fun stamp(pattern: String, at: Long) = SimpleDateFormat(pattern, Locale.US).format(Date(at))

    fun elapsedMin(): Long = (System.currentTimeMillis() - startedAt) / 60_000L

    @Synchronized fun notes(): List<String> = notesList.toList()
    @Synchronized fun tasks(): List<String> = taskLines.toList()

    @Synchronized fun addSegment(text: String) {
        segments += System.currentTimeMillis() to text
        persist()
    }

    @Synchronized fun addNotes(notes: List<String>) {
        val fresh = notes.filter { it !in notesList }
        if (fresh.isEmpty()) return
        notesList += fresh
        persist()
    }

    @Synchronized fun taskQueued(task: String) {
        taskLines += "⏳ en cola: $task"
        persist()
    }

    @Synchronized fun taskStarted(task: String) {
        val i = taskLines.indexOfFirst { it == "⏳ en cola: $task" }
        val line = "▶ en curso: $task"
        if (i >= 0) taskLines[i] = line else taskLines += line
        persist()
    }

    @Synchronized fun taskDone(task: String, result: String, ok: Boolean) {
        val i = taskLines.indexOfFirst { it == "▶ en curso: $task" }
        val line = "${if (ok) "✔" else "✖"} $task → ${result.take(300)}"
        if (i >= 0) taskLines[i] = line else taskLines += line
        persist()
    }

    @Synchronized fun persist() {
        runCatching {
            val sb = StringBuilder()
            sb.append("# Reunión · ${stamp("yyyy-MM-dd HH:mm", startedAt)}\n\n")
            sb.append("## Notas\n")
            if (notesList.isEmpty()) sb.append("_(sin notas todavía)_\n")
            else notesList.forEach { sb.append("- $it\n") }
            sb.append("\n## Lo que hizo Ü\n")
            if (taskLines.isEmpty()) sb.append("_(ninguna tarea lanzada)_\n")
            else taskLines.forEach { sb.append("- $it\n") }
            sb.append("\n## Transcripción\n")
            segments.forEach { (at, t) -> sb.append("**[${stamp("HH:mm", at)}]** $t\n\n") }
            file.writeText(sb.toString())
        }.onFailure { LogBus.log("meeting", "no pude guardar la reunión: ${it.message}") }
    }
}
