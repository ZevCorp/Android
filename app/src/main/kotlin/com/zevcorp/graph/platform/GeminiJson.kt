package com.zevcorp.graph.platform

import java.net.HttpURLConnection
import java.net.URL
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

/** Llamada mínima a Gemini generateContent con salida JSON, compartida por los destiladores. */
object GeminiJson {

    /**
     * `thinkingBudget = 0` desactiva el razonamiento interno del modelo: para decisiones JSON de
     * una frase (propuestas, destiladores) el campo "reasoning" del propio JSON ES la cadena de
     * pensamiento, y el thinking por defecto solo agrega segundos de latencia. Si el modelo no
     * acepta el parámetro, se reintenta una vez sin él (nunca rompe el pipeline).
     */
    fun ask(apiKey: String, model: String, prompt: String, tag: String = "gemini", thinkingBudget: Int? = null): JsonObject =
        try {
            askOnce(apiKey, model, prompt, tag, thinkingBudget)
        } catch (t: Throwable) {
            if (thinkingBudget == null) throw t
            LogBus.log(tag, "reintento sin thinkingConfig: ${t.message}")
            askOnce(apiKey, model, prompt, tag, null)
        }

    private fun askOnce(apiKey: String, model: String, prompt: String, tag: String, thinkingBudget: Int?): JsonObject {
        val req = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt) } }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                if (thinkingBudget != null) putJsonObject("thinkingConfig") { put("thinkingBudget", thinkingBudget) }
            }
        }
        val reqBytes = Json.encodeToString(JsonObject.serializer(), req).toByteArray()
        val (code, body) = GeminiHttp.withRetry(tag) {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 30_000; c.readTimeout = 120_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.doOutput = true
            c.outputStream.use { it.write(reqBytes) }
            val status = c.responseCode
            val respBody = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            status to respBody
        }
        if (code >= 300) { LogBus.log(tag, "HTTP $code: ${body.take(200)}"); error("Gemini HTTP $code") }
        val text = Json.parseToJsonElement(body).jsonObject["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            .firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        // El modelo a veces envuelve el JSON en ```fences``` o le añade basura (p.ej. un "}" de más),
        // lo que reventaba el parseo estricto. Extrae el primer objeto JSON balanceado.
        return firstJsonObject(text)
    }

    /**
     * Extrae el primer objeto JSON BALANCEADO de la respuesta del modelo, tolerando ```fences``` y
     * cualquier basura antes o después (un "}" extra, texto suelto…). Respeta strings y escapes para
     * no cortar donde no debe. Lanza si no hay ningún objeto.
     */
    fun firstJsonObject(raw: String): JsonObject {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```").let { if (it.startsWith("json")) it.drop(4) else it }
                .substringBeforeLast("```").trim()
        }
        val start = s.indexOf('{')
        require(start >= 0) { "sin objeto JSON en la respuesta" }
        var depth = 0; var inStr = false; var esc = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return Json.parseToJsonElement(s.substring(start, i + 1)).jsonObject }
            }
            i++
        }
        error("objeto JSON sin cerrar en la respuesta")
    }

    /** Igual que ask() pero devuelve TEXTO libre (respuestas conversacionales). "" si falla. */
    fun askText(apiKey: String, model: String, prompt: String, tag: String = "gemini"): String {
        val req = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt) } }
                }
            }
        }
        val reqBytes = Json.encodeToString(JsonObject.serializer(), req).toByteArray()
        return runCatching {
            val (code, body) = GeminiHttp.withRetry(tag) {
                val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 30_000; c.readTimeout = 120_000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", apiKey)
                c.doOutput = true
                c.outputStream.use { it.write(reqBytes) }
                val status = c.responseCode
                val respBody = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                c.disconnect()
                status to respBody
            }
            if (code >= 300) { LogBus.log(tag, "HTTP $code: ${body.take(200)}"); return "" }
            Json.parseToJsonElement(body).jsonObject["candidates"]!!.jsonArray[0]
                .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
                .firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.trim()
        }.getOrElse { LogBus.log(tag, "chat falló: ${it.message}"); "" }
    }
}
