package com.zevcorp.graph.platform

import graph.core.domain.LearnedTool
import graph.core.domain.LearningBrain
import graph.core.domain.Proposal
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Cerebro de la sesión de enseñanza (generateContent con salida JSON). Convierte la explicación hablada
 * del usuario + el árbol de UI en una secuencia de toques, y al final la organiza en una herramienta MCP.
 */
class GeminiLearning(
    private val apiKey: () -> String,
    private val model: () -> String,
) : LearningBrain {

    private fun ask(prompt: String): JsonObject = run {
        val base = "https://generativelanguage.googleapis.com"
        val req = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt) } }
                }
            }
            putJsonObject("generationConfig") { put("responseMimeType", "application/json") }
        }
        val c = URL("$base/v1beta/models/${model()}:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30_000; c.readTimeout = 120_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", apiKey())
        c.doOutput = true
        c.outputStream.use { it.write(Json.encodeToString(JsonObject.serializer(), req).toByteArray()) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        if (code >= 300) { LogBus.log("learn", "HTTP $code: ${body.take(200)}"); error("Gemini HTTP $code") }
        val text = Json.parseToJsonElement(body).jsonObject["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            .firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.strList(k: String) = this[k]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    override suspend fun propose(explanation: String, screen: String, elements: List<String>): Proposal =
        withContext(Dispatchers.IO) {
            val prompt = """
                Eres Graph, aprendiendo una tarea que el usuario te explica en su teléfono. Tu meta es convertir
                su explicación + el árbol de UI actual en una SECUENCIA concreta de toques sobre elementos.
                Explicación acumulada del usuario: "$explanation"
                Pantalla actual: $screen
                Elementos tocables visibles (etiquetas EXACTAS): ${elements.joinToString(" | ").ifBlank { "(ninguno)" }}
                ¿Ya entiendes una secuencia concreta usando SOLO esas etiquetas? Si te falta información, understood=false.
                La secuencia debe usar etiquetas EXACTAS de la lista, en el orden de ejecución.
                Responde SOLO JSON: {"understood": true|false, "name": "nombre corto", "description": "qué hace",
                "sequence": ["etiqueta1","etiqueta2",...], "say": "frase corta y con chispa para el usuario"}
            """.trimIndent()
            runCatching {
                val o = ask(prompt)
                Proposal(
                    understood = o["understood"]?.jsonPrimitive?.booleanOrNull ?: false,
                    name = o.str("name"),
                    description = o.str("description"),
                    sequence = o.strList("sequence"),
                    say = o.str("say"),
                )
            }.getOrElse { Proposal(understood = false, say = "") }
        }

    override suspend fun consolidate(explanation: String, sequence: List<String>): LearnedTool =
        withContext(Dispatchers.IO) {
            val prompt = """
                El usuario terminó de enseñarte. Organiza lo aprendido en una herramienta reutilizable.
                Explicación del usuario: "$explanation"
                Secuencia confirmada de toques (etiquetas del árbol de UI): ${sequence.joinToString(" | ")}
                Responde SOLO JSON: {"name": "nombre_en_snake_case", "description": "qué hace; menciona que se
                ejecuta tocando elementos del árbol de UI", "steps": ["etiqueta1","etiqueta2",...]}
                steps normalmente es la misma secuencia confirmada.
            """.trimIndent()
            runCatching {
                val o = ask(prompt)
                val steps = o.strList("steps").ifEmpty { sequence }
                LearnedTool(o.str("name").ifBlank { "tarea_aprendida" }, o.str("description"), steps)
            }.getOrElse { LearnedTool("tarea_aprendida", "Secuencia aprendida de toques.", sequence) }
        }
}
