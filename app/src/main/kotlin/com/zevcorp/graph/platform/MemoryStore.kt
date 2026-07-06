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
 * ("cada vez que te pida el parlante → wifi + Spotify + transmitir"). Es la knowledge-base PERSONAL:
 * pertenece a la CUENTA del usuario (a diferencia del mapa de UI de las apps, que es compartido).
 * Se inyecta completa al cerebro de ejecución para que las aplique sin que se las repitan.
 *
 * Local: un archivo por cuenta (files/memory-<userId>.json); sin sesión se usa files/memory.json
 * (notas anónimas, solo de este teléfono) y al iniciar sesión esas notas se ADOPTAN a la cuenta.
 * Nube: graph_memory, protegida por RLS — solo viaja/baja con la sesión del usuario.
 */
class MemoryStore(
    private val root: File,
    private val owner: () -> String = { "" },
    private val pushToCloud: (MemoryNote) -> Unit = {},
) {

    /** El archivo de la cuenta activa (o el anónimo si no hay sesión). */
    private val file: File
        get() = owner().let { if (it.isBlank()) File(root, "memory.json") else File(root, "memory-$it.json") }
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

    /** Arranque/login: baja la nube y sube lo local que falte allá. Llamar desde IO. */
    fun syncFromCloud(remote: List<MemoryNote>) {
        remote.forEach { addLocal(it) }
        val remoteNotes = remote.map { it.note.lowercase() }.toSet()
        all().filter { it.note.lowercase() !in remoteNotes }.forEach { pushToCloud(it) }
        if (remote.isNotEmpty()) LogBus.log("cloud", "☁ ${remote.size} recuerdos sincronizados")
    }

    /**
     * Al iniciar sesión: lo que el asistente aprendió de ti ANTES de que tuvieras cuenta (notas
     * anónimas de este teléfono) pasa a ser tuyo — se mueve al archivo de la cuenta y se sube.
     * Llamar desde IO, con la sesión ya activa.
     */
    @Synchronized
    fun adoptAnonymous() {
        if (owner().isBlank()) return
        val anonymous = File(root, "memory.json")
        val notes = runCatching { json.decodeFromString(serializer, anonymous.readText()) }.getOrElse { emptyList() }
        if (notes.isEmpty()) return
        val adopted = notes.count { add(it) }
        anonymous.delete()
        LogBus.log("memory", "🧠 $adopted recuerdos de este teléfono ahora son de tu cuenta")
    }
}
