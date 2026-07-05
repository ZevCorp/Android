package com.zevcorp.graph.platform

import graph.core.domain.LearnedTool
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Copia en la NUBE de los aprendizajes (Supabase/Postgres, tabla graph_learned_tools): los MCPs
 * aprendidos sobreviven reinstalaciones y se comparten entre dispositivos. El disco local sigue
 * siendo la fuente en caliente (offline-first); la nube se sincroniza en segundo plano:
 * push al guardar cada herramienta y pull completo al arrancar la app.
 */
object CloudSync {

    private const val BASE = "https://zyvfamlhlmztliexvmej.supabase.co/rest/v1/graph_learned_tools"
    private const val KEY = "sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI" // publishable (cliente)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(LearnedTool.serializer())

    /** Sube (upsert por nombre) una herramienta. Llamar desde IO; nunca lanza. */
    fun push(tool: LearnedTool) {
        runCatching {
            http("POST", "$BASE?on_conflict=name",
                json.encodeToString(listSerializer, listOf(tool)),
                "Prefer" to "resolution=merge-duplicates")
            LogBus.log("cloud", "☁ subido: ${tool.name}")
        }.onFailure { LogBus.log("cloud", "☁ no pude subir ${tool.name}: ${it.message}") }
    }

    /** Baja todas las herramientas de la nube. Llamar desde IO; nunca lanza (vacío si falla). */
    fun pull(): List<LearnedTool> = runCatching {
        json.decodeFromString(listSerializer, http("GET", "$BASE?select=name,app,description,elements"))
    }.getOrElse { LogBus.log("cloud", "☁ no pude bajar: ${it.message}"); emptyList() }

    private fun http(method: String, url: String, body: String? = null, vararg headers: Pair<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("apikey", KEY)
        c.setRequestProperty("Authorization", "Bearer $KEY")
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
