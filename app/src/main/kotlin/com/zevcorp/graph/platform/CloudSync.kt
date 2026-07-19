package com.zevcorp.graph.platform

import graph.core.domain.LearnedTool
import graph.core.domain.Workflow
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Una interacción usuario↔asistente que se registra en la nube (tabla `graph_interactions`): el
 * prompt del usuario (`input`) y la respuesta final del asistente (`output`), más el contexto útil
 * (device, cuenta si hay, app en primer plano, versión). No lleva `user_id`: cuando la subida viaja
 * con la sesión del usuario, el servidor lo llena solo con `auth.uid()`.
 */
@Serializable
data class InteractionRow(
    val device: String = "",
    val email: String = "",
    val input: String = "",
    val output: String = "",
    val app: String = "",
    val version: String = "",
)

/**
 * Copia en la NUBE de las dos capas de conocimiento (Supabase/Postgres), con reglas distintas:
 *
 *  · graph_learned_tools — el mapa de UI de las apps. Capa TRANSVERSAL: la lee cualquiera (lo que
 *    un usuario le enseña de una app les sirve a todos) y aportar/refinar requiere sesión.
 *  · graph_memory — la knowledge-base PERSONAL. Solo viaja con el token del usuario: el servidor
 *    (RLS por user_id) garantiza que cada quien ve únicamente sus recuerdos. Sin sesión, la
 *    memoria vive solo en el teléfono.
 *
 * El disco local sigue siendo la fuente en caliente (offline-first); la nube se sincroniza en
 * segundo plano: push al guardar y pull completo al arrancar / iniciar sesión.
 */
object CloudSync {

    private const val BASE = "https://zyvfamlhlmztliexvmej.supabase.co/rest/v1/graph_learned_tools"
    private const val MEMORY = "https://zyvfamlhlmztliexvmej.supabase.co/rest/v1/graph_memory"
    private const val WORKFLOWS = "https://zyvfamlhlmztliexvmej.supabase.co/rest/v1/graph_workflows"
    private const val INTERACTIONS = "https://zyvfamlhlmztliexvmej.supabase.co/rest/v1/graph_interactions"
    private const val KEY = "sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI" // publishable (cliente)

    /** Token de la sesión del usuario (lo cablea GraphApp con SupabaseAuth). Null = sin sesión. */
    @Volatile var userToken: () -> String? = { null }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(LearnedTool.serializer())
    private val memorySerializer = ListSerializer(MemoryNote.serializer())
    private val workflowSerializer = ListSerializer(Workflow.serializer())
    private val interactionSerializer = ListSerializer(InteractionRow.serializer())

    /**
     * Registra una interacción en el historial central (solo INSERT; nadie la lee con la key pública,
     * el dueño la ve por la Edge Function `graph-interactions`). Si hay sesión, la subida viaja con el
     * bearer del usuario y el servidor llena `user_id` con la cuenta; sin sesión, queda solo el device.
     * Fire-and-forget: nunca bloquea ni lanza.
     */
    fun pushInteraction(row: InteractionRow) {
        runCatching {
            http("POST", INTERACTIONS, json.encodeToString(interactionSerializer, listOf(row)),
                userToken(), "Prefer" to "return=minimal")
        }.onFailure { LogBus.log("cloud", "☁ no registré la interacción: ${it.message}") }
    }

    /** Sube (upsert por nombre) una herramienta a la capa compartida. Requiere sesión; nunca lanza. */
    fun push(tool: LearnedTool) {
        val token = userToken() ?: run {
            LogBus.log("cloud", "☁ ${tool.name} quedó local: inicia sesión para compartir aprendizajes")
            return
        }
        runCatching {
            http("POST", "$BASE?on_conflict=name",
                json.encodeToString(listSerializer, listOf(tool)), token,
                "Prefer" to "resolution=merge-duplicates")
            LogBus.log("cloud", "☁ subido: ${tool.name}")
        }.onFailure { LogBus.log("cloud", "☁ no pude subir ${tool.name}: ${it.message}") }
    }

    /** Baja TODAS las herramientas compartidas (lectura pública). Nunca lanza (vacío si falla). */
    fun pull(): List<LearnedTool> = runCatching {
        json.decodeFromString(listSerializer, http("GET", "$BASE?select=name,app,description,elements", null, null))
    }.getOrElse { LogBus.log("cloud", "☁ no pude bajar: ${it.message}"); emptyList() }

    /** Sube (upsert por usuario+texto) un recuerdo PERSONAL. Sin sesión queda solo local. */
    fun pushMemory(note: MemoryNote) {
        val token = userToken() ?: return
        runCatching {
            http("POST", "$MEMORY?on_conflict=user_id,note",
                json.encodeToString(memorySerializer, listOf(note)), token,
                "Prefer" to "resolution=merge-duplicates")
        }.onFailure { LogBus.log("cloud", "☁ no pude subir el recuerdo: ${it.message}") }
    }

    /** Baja la memoria PERSONAL del usuario con sesión (RLS filtra por su cuenta). */
    fun pullMemory(): List<MemoryNote> {
        val token = userToken() ?: return emptyList()
        return runCatching {
            json.decodeFromString(memorySerializer, http("GET", "$MEMORY?select=app,note", null, token))
        }.getOrElse { LogBus.log("cloud", "☁ no pude bajar la memoria: ${it.message}"); emptyList() }
    }

    /**
     * Sube (upsert por nombre) un workflow a la capa compartida. Como el mapa de UI, un workflow es
     * "cómo se hace una tarea": transversal a todos. Requiere sesión para aportar; nunca lanza.
     */
    fun pushWorkflow(workflow: Workflow) {
        val token = userToken() ?: run {
            LogBus.log("cloud", "☁ el workflow ${workflow.name} quedó local: inicia sesión para compartirlo")
            return
        }
        runCatching {
            http("POST", "$WORKFLOWS?on_conflict=name",
                json.encodeToString(workflowSerializer, listOf(workflow)), token,
                "Prefer" to "resolution=merge-duplicates")
            LogBus.log("cloud", "☁ workflow subido: ${workflow.name}")
        }.onFailure { LogBus.log("cloud", "☁ no pude subir el workflow ${workflow.name}: ${it.message}") }
    }

    /** Baja todos los workflows compartidos (lectura pública). Nunca lanza (vacío si falla). */
    fun pullWorkflows(): List<Workflow> = runCatching {
        json.decodeFromString(workflowSerializer, http("GET", "$WORKFLOWS?select=name,description,steps,source", null, null))
    }.getOrElse { LogBus.log("cloud", "☁ no pude bajar los workflows: ${it.message}"); emptyList() }

    /** Borra un workflow de la nube (al borrarlo el usuario en la app). Requiere sesión; nunca lanza. */
    fun deleteWorkflow(name: String) {
        val token = userToken() ?: return
        runCatching {
            http("DELETE", "$WORKFLOWS?name=eq." + java.net.URLEncoder.encode(name, "UTF-8"), null, token)
            LogBus.log("cloud", "☁ workflow borrado: $name")
        }.onFailure { LogBus.log("cloud", "☁ no pude borrar el workflow $name: ${it.message}") }
    }

    private fun http(method: String, url: String, body: String?, bearer: String?, vararg headers: Pair<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("apikey", KEY)
        c.setRequestProperty("Authorization", "Bearer ${bearer ?: KEY}")
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        if (code >= 300) error("HTTP $code: ${text.take(160)}")
        return text
    }
}
