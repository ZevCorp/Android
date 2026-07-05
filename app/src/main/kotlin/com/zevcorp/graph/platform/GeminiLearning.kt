package com.zevcorp.graph.platform

import graph.core.domain.LearnedTool
import graph.core.domain.LearningBrain
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Cerebro de la enseñanza PASIVA (generateContent con salida JSON). Se invoca cuando el usuario
 * sale de una app con el modo enseñanza activo: recibe los clics que hizo dentro (señales de valor)
 * y el catálogo del árbol de UI, y estructura el mapa MCP — pero con criterio estricto: solo guarda
 * lo que entiende con certeza muy alta y tiene valor real para el usuario según ese uso.
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

    override suspend fun consolidate(
        app: String,
        screen: String,
        clicks: List<String>,
        elements: List<String>,
        previous: LearnedTool?,
    ): LearnedTool? = withContext(Dispatchers.IO) {
        val previousBlock = if (previous != null) """
            YA EXISTE un mapa de esta app llamado "${previous.name}":
            - descripción actual: ${previous.description}
            - elementos actuales: ${previous.elements.joinToString(", ").ifBlank { "(ninguno)" }}
            REFÍNALO: conserva lo que sigue siendo válido, corrige lo que ahora entiendas mejor y añade
            SOLO lo nuevo que cumpla el criterio. Mantén el mismo nombre.
        """.trimIndent() else "No existía mapa previo de esta app: si guardas, elige un nombre snake_case descriptivo."

        val prompt = """
            Eres Graph. Tu modo de ENSEÑANZA PASIVA observó el uso de una app en el teléfono Android
            y esa app acaba de cerrarse. Los clics pueden venir del usuario usándola con normalidad
            O de ti mismo ejecutando una tarea que el usuario pidió: ambas son señales igual de
            válidas de qué importa. Tu trabajo: estructurar lo observado como herramienta MCP — o
            decidir que NO hay nada confiable que guardar.

            App (paquete): $app
            Pantalla: $screen
            CLICS observados EN ORDEN (el uso real; ESTA es la señal de qué importa):
            ${clicks.joinToString(" → ").ifBlank { "(ninguno)" }}
            Elementos tocables vistos en el árbol de UI (etiquetas EXACTAS):
            ${elements.joinToString(" | ").ifBlank { "(ninguno)" }}
            $previousBlock

            CRITERIO ESTRICTO (calidad sobre cantidad):
            - Incluye SOLO elementos cuya función entiendas con certeza MUY ALTA (>90%). Ante la duda, fuera.
            - Incluye SOLO lo VALIOSO para el usuario según sus clics: generaliza al grupo completo cuando
              la estructura sea obvia (tocó "5" y "7" en una calculadora → todos los dígitos y operadores),
              pero NO metas zonas de la app que ni tocó ni se relacionan con lo que hizo.
            - DESCARTA el ruido: barras del sistema, decoraciones, banners, elementos ambiguos.
            - Si las señales fueron pocas o ambiguas y no hay nada que cumpla lo anterior, responde
              {"worth": false} y nada más: guardar basura es peor que no guardar.

            Si SÍ vale la pena: la herramienta se usa así en ejecución: otro asistente (otra ventana de
            contexto) leerá tu descripción y llamará la herramienta con "taps": las etiquetas a tocar EN
            ORDEN. Tu descripción es LA DOCUMENTACIÓN: qué hace la app, qué es cada grupo de elementos y
            cómo componer secuencias para tareas típicas, con un ejemplo. Copia las etiquetas EXACTAS.
            Responde SOLO JSON:
            {"worth": true, "name": "nombre_snake_case", "description": "documentación completa", "elements": ["...", "..."]}
        """.trimIndent()

        runCatching {
            val o = ask(prompt)
            if (o["worth"]?.jsonPrimitive?.booleanOrNull != true) null
            else LearnedTool(
                name = o.str("name").ifBlank { previous?.name ?: "app_aprendida" },
                description = o.str("description"),
                elements = o.strList("elements"),
                app = app,
            ).takeIf { it.elements.isNotEmpty() && it.description.isNotBlank() }
        }.getOrElse { LogBus.log("learn", "consolidate error: ${it.message}"); null }
    }
}
