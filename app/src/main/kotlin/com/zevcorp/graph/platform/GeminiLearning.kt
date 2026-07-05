package com.zevcorp.graph.platform

import graph.core.domain.LearnedTool
import graph.core.domain.LearningBrain
import graph.core.domain.TeachTurn
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Cerebro de la enseñanza (generateContent con salida JSON). Ve TODO el árbol de UI; la voz y los
 * clics del usuario son SEÑALES para generalizar. Su trabajo es estructurar el mapa MCP completo de
 * la pantalla: agrupar elementos, preguntar iluminando, demostrar y, al cierre, documentarlo todo.
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

    private fun context(transcript: String, screen: String, elements: List<String>) = """
        Pantalla actual: $screen
        TODOS los elementos tocables del árbol de UI (etiquetas EXACTAS): ${elements.joinToString(" | ").ifBlank { "(ninguno)" }}
        Transcripción de la sesión hasta ahora (voz del usuario, sus toques como señales, y respuestas):
        "$transcript"
    """.trimIndent()

    override suspend fun step(transcript: String, screen: String, elements: List<String>): TeachTurn =
        withContext(Dispatchers.IO) {
            val prompt = """
                Eres Graph, aprendiendo una pantalla que el usuario te enseña en su teléfono Android.
                TU META: estructurar el mapa COMPLETO de esta pantalla (qué es cada elemento, cómo se agrupan,
                cómo se componen para tareas). La voz y los TOQUES del usuario son SEÑALES para GENERALIZAR:
                si tocó "5" y "7" y habló de cálculos, infiere que TODOS los dígitos y operadores importan,
                sin que los toque todos. No copies sus clics: entiende la estructura.
                ${context(transcript, screen, elements)}
                Decide UNA cosa para este turno:
                - Si ya entiendes un grupo o el uso general: demuéstralo iluminando (highlight) una secuencia
                  ilustrativa (p.ej. 5 + 7 + 8 =) y di algo corto (say). Propón en test esa secuencia si
                  quieres probarla tocando de verdad.
                - Si te falta UNA cosa por confirmar (p.ej. "¿este es el de borrar?"): pon la pregunta en
                  question (sí/no) e ilumina en highlight el elemento del que hablas.
                - Si aún no tienes suficiente señal: say vacío o una frase breve, y nada más.
                Usa SOLO etiquetas exactas de la lista. Responde SOLO JSON:
                {"say": "", "highlight": [], "question": "", "test": []}
            """.trimIndent()
            runCatching {
                val o = ask(prompt)
                TeachTurn(
                    say = o.str("say"),
                    highlight = o.strList("highlight"),
                    question = o.str("question").ifBlank { null },
                    test = o.strList("test"),
                )
            }.getOrElse { TeachTurn() }
        }

    override suspend fun consolidate(transcript: String, screen: String, elements: List<String>): LearnedTool =
        withContext(Dispatchers.IO) {
            val prompt = """
                La sesión de enseñanza terminó. Estructura TODO lo aprendido como una herramienta MCP.
                ${context(transcript, screen, elements)}
                La herramienta funciona así en ejecución: otro asistente (otra ventana de contexto) leerá tu
                descripción y llamará la herramienta con "taps": las etiquetas a tocar EN ORDEN. Así que tu
                descripción es LA DOCUMENTACIÓN: explica qué hace la pantalla/app, qué es cada grupo de
                elementos (p.ej. dígitos 0-9, operadores, igual, borrar) y cómo componer secuencias para
                tareas típicas, con un ejemplo. En elements incluye TODAS las etiquetas útiles de la pantalla
                (generaliza: no solo las que el usuario tocó), copiadas EXACTAS de la lista.
                Responde SOLO JSON:
                {"name": "nombre_snake_case", "description": "documentación completa", "elements": ["...", "..."]}
            """.trimIndent()
            runCatching {
                val o = ask(prompt)
                LearnedTool(
                    o.str("name").ifBlank { "pantalla_aprendida" },
                    o.str("description"),
                    o.strList("elements").ifEmpty { elements },
                )
            }.getOrElse { LearnedTool("pantalla_aprendida", "Mapa de pantalla aprendido. Toca elementos por etiqueta.", elements) }
        }
}
