package com.zevcorp.graph.platform

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Una fila del historial tal como la devuelve la Edge Function (incluye metadatos de solo lectura). */
@Serializable
data class InteractionView(
    val id: Long = 0,
    val device: String = "",
    val user_id: String? = null,
    val email: String = "",
    val input: String = "",
    val output: String = "",
    val app: String = "",
    val version: String = "",
    val created_at: String = "",
)

/**
 * LECTURA DEL HISTORIAL (solo para el DUEÑO). La tabla `graph_interactions` no es legible con la key
 * pública (RLS sin policy de SELECT): el historial de un usuario es privado frente a los demás. El
 * dueño lo consulta AQUÍ, por la Edge Function `graph-interactions`, que valida el token de admin del
 * lado servidor y responde con la service role. Mismo patrón que `Updater.publish`.
 */
object InteractionsAdmin {

    private const val BASE = "https://zyvfamlhlmztliexvmej.supabase.co"
    private const val ANON = "sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI"
    private const val FN = "$BASE/functions/v1/graph-interactions"

    private val json = Json { ignoreUnknownKeys = true }
    private val rowsSerializer = kotlinx.serialization.builtins.ListSerializer(InteractionView.serializer())

    /** Resultado de la consulta: filas si OK, o un mensaje de error legible. */
    data class Result(val rows: List<InteractionView>?, val error: String?)

    /** Trae el historial (más recientes primero). `search` filtra por input/output/email/app. */
    suspend fun fetch(adminToken: String, search: String = "", limit: Int = 200): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("admin_token", adminToken)
                    put("limit", limit)
                    if (search.isNotBlank()) put("search", search)
                })
                val c = (URL(FN).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000; readTimeout = 30_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("apikey", ANON)
                    setRequestProperty("Authorization", "Bearer $ANON")
                    doOutput = true
                }
                c.outputStream.use { it.write(payload.toByteArray()) }
                val code = c.responseCode
                val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                c.disconnect()
                if (code >= 300) {
                    val msg = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: "HTTP $code"
                    return@withContext Result(null, if (code == 401) "Token de administrador incorrecto" else msg)
                }
                val rows = runCatching {
                    val arr = json.parseToJsonElement(body).jsonObject["rows"]?.toString() ?: "[]"
                    json.decodeFromString(rowsSerializer, arr)
                }.getOrDefault(emptyList())
                Result(rows, null)
            }.getOrElse { Result(null, "No se pudo cargar: ${it.message}") }
        }
}
