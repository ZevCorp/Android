package com.zevcorp.graph.platform

import graph.core.domain.WebSearch
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Búsqueda web AGÉNTICA con Google Search grounding de Gemini (ai.google.dev/.../grounding).
 *
 * En una sola llamada a generateContent con la tool nativa `google_search`, Gemini 3.5 Flash decide
 * qué consultas lanzar a Google, LEE los resultados y devuelve una síntesis en lenguaje natural con
 * sus fuentes. Esto es lo que recibe el asistente como resultado de la herramienta MCP `web_search`:
 * texto sobre el que puede razonar y actuar en el mismo run (no abre el navegador ni "dispara y olvida").
 *
 * Estable por diseño: si algo falla (red, cuota, parseo) NUNCA lanza — devuelve una frase corta para
 * que el run del asistente continúe. Cancelable de verdad: si se pulsa Stop mientras espera, se corta
 * la conexión al instante (igual que GeminiBrain). Sin filtros ni bloqueos de consultas: 100% autónomo.
 */
class GeminiSearch(
    private val apiKey: () -> String,
    private val model: () -> String,
) : WebSearch {

    override suspend fun search(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "no había nada que buscar"
        val req = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", q) } }
                }
            }
            // Tool nativa de grounding: el modelo busca en Google por su cuenta y cita fuentes.
            putJsonArray("tools") { addJsonObject { putJsonObject("google_search") {} } }
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        return try {
            val res = post(
                "$BASE/v1beta/models/${model()}:generateContent",
                Json.encodeToString(JsonObject.serializer(), req).toByteArray(),
            )
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            if (res.code >= 300) {
                LogBus.log("search", "HTTP ${res.code}: ${res.body.take(200)}")
                return "no pude completar la búsqueda ahora (error ${res.code})"
            }
            val out = extract(res.body)
            LogBus.log("search", "\"${q.take(60)}\" → ${ms}ms · ${out.length} chars")
            out.ifBlank { "la búsqueda no devolvió resultados útiles" }
        } catch (e: Throwable) {
            LogBus.log("search", "falló: ${e.message}")
            "no pude completar la búsqueda ahora mismo"
        }
    }

    /** Síntesis del modelo + lista compacta de fuentes (título — url) del groundingMetadata. */
    private fun extract(body: String): String {
        val cand = Json.parseToJsonElement(body).jsonObject["candidates"]
            ?.jsonArray?.getOrNull(0)?.jsonObject ?: return ""
        val answer = cand["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")?.trim().orEmpty()
        val sources = cand["groundingMetadata"]?.jsonObject?.get("groundingChunks")?.jsonArray
            ?.mapNotNull { it.jsonObject["web"]?.jsonObject }
            ?.mapNotNull { w ->
                val uri = w["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = w["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { uri } ?: uri
                title to uri
            }
            ?.distinctBy { it.second }
            ?.take(5)
            .orEmpty()
        if (answer.isBlank()) return ""
        if (sources.isEmpty()) return answer
        return buildString {
            append(answer)
            append("\n\nFuentes:")
            sources.forEach { (title, uri) -> append("\n- $title — $uri") }
        }
    }

    private class Res(val code: Int, val body: String)

    /** POST bloqueante pero CANCELABLE: si el Job se cancela (Stop), disconnect() corta la lectura ya. */
    private suspend fun post(url: String, body: ByteArray): Res = suspendCancellableCoroutine { cont ->
        val c = URL(url).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { runCatching { c.disconnect() } }
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey())
            c.doOutput = true
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            if (cont.isActive) cont.resumeWith(Result.success(Res(code, text)))
        } catch (e: Throwable) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }
    }

    private companion object {
        const val BASE = "https://generativelanguage.googleapis.com"
    }
}
