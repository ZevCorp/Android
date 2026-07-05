package com.zevcorp.graph.platform

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Una regla/preferencia que el usuario le enseñó al asistente (con la app a la que aplica, si hay). */
@Serializable
data class MemoryNote(val app: String = "", val note: String = "")

/**
 * Memoria durable del asistente: reglas y preferencias destiladas de CUALQUIER input del usuario
 * ("cada vez que te pida el parlante → wifi + Spotify + transmitir"). Es la knowledge-base personal:
 * se inyecta completa al cerebro de ejecución para que las aplique sin que se las repitan.
 * Local (files/memory.json) + copia en la nube (graph_memory).
 */
class MemoryStore(root: File, private val pushToCloud: (MemoryNote) -> Unit = {}) {

    private val file = File(root, "memory.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val serializer = ListSerializer(MemoryNote.serializer())

    @Synchronized
    fun all(): List<MemoryNote> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyList() }

    /** Agrega una nota (dedup por texto). Devuelve false si ya existía. */
    @Synchronized
    fun add(note: MemoryNote): Boolean {
        if (!addLocal(note)) return false
        pushToCloud(note)
        return true
    }

    @Synchronized
    fun addLocal(note: MemoryNote): Boolean {
        val current = all()
        if (current.any { it.note.equals(note.note, ignoreCase = true) }) return false
        file.writeText(json.encodeToString(serializer, current + note))
        return true
    }

    /**
     * Bloque para el system prompt del motor de ejecución (vacío si no hay memoria). Se agrupa por
     * app y las notas de una app NUNCA se recortan: cuando el asistente vaya a usar esa app debe
     * tener su contexto COMPLETO (p.ej. todo lo que sabe de WhatsApp). Solo las notas generales
     * (sin app) se limitan si fueran muchísimas.
     */
    fun promptBlock(): String {
        val notes = all()
        if (notes.isEmpty()) return ""
        val general = notes.filter { it.app.isBlank() }.takeLast(40)
        val byApp = notes.filter { it.app.isNotBlank() }.groupBy { it.app }
        return buildString {
            general.forEach { appendLine("- ${it.note}") }
            byApp.forEach { (app, ns) ->
                appendLine("· $app:")
                ns.forEach { appendLine("   - ${it.note}") }
            }
        }.trim()
    }

    /** Todas las notas de una app concreta, sin recortar (contexto completo al abrir esa app). */
    fun notesForApp(app: String): List<MemoryNote> =
        all().filter { it.app.isNotBlank() && it.app.equals(app, ignoreCase = true) }

    /** Arranque: baja la nube y sube lo local que falte allá. Llamar desde IO. */
    fun syncFromCloud(remote: List<MemoryNote>) {
        remote.forEach { addLocal(it) }
        val remoteNotes = remote.map { it.note.lowercase() }.toSet()
        all().filter { it.note.lowercase() !in remoteNotes }.forEach { pushToCloud(it) }
        if (remote.isNotEmpty()) LogBus.log("cloud", "☁ ${remote.size} recuerdos sincronizados")
    }
}
