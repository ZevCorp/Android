package com.zevcorp.graph.platform

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Cerebro del aprendizaje ACTIVO (compartir pantalla). Al terminar la grabación sube el mp4 a la
 * Files API de Gemini, espera a que quede ACTIVE y estructura CONOCIMIENTO TEXTUAL por app: hechos y
 * preferencias reutilizables que el usuario demostró en pantalla y explicó con su voz (p.ej. "el
 * contacto de su mamá en WhatsApp se llama 'Ale', no 'mamá'").
 *
 * FASE 1: NO estructura el árbol de UI — solo texto para la knowledge-base MCP (MemoryStore). Puede
 * además devolver preguntas de seguimiento para que el asistente las haga por voz al terminar.
 */
class GeminiVideo(
    private val apiKey: () -> String,
    private val model: () -> String,
) {
    class Result(val notes: List<MemoryNote>, val questions: List<String>, val summary: String)

    private class Uploaded(val name: String, val uri: String)

    private val base = "https://generativelanguage.googleapis.com"

    /** Sube el video, lo procesa y devuelve las notas por app + preguntas de seguimiento. */
    suspend fun structure(video: File): Result = withContext(Dispatchers.IO) {
        val up = runCatching { upload(video) }.getOrElse {
            LogBus.log("teach", "subida del video falló: ${it.message}")
            return@withContext Result(emptyList(), emptyList(), "")
        }
        runCatching { waitActive(up.name) }.onFailure {
            LogBus.log("teach", "el video no quedó listo: ${it.message}")
            return@withContext Result(emptyList(), emptyList(), "")
        }
        val o = runCatching { generate(up.uri) }.getOrElse {
            LogBus.log("teach", "estructuración falló: ${it.message}")
            return@withContext Result(emptyList(), emptyList(), "")
        }
        val notes = o["items"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val note = obj["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (note.isBlank()) null
            else MemoryNote(app = obj["app"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(), note = note)
        }.orEmpty()
        val questions = o["questions"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { q -> q.isNotBlank() } }
            .orEmpty()
        val summary = o["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        Result(notes, questions, summary)
    }

    /* ---------- Files API: subida resumable del mp4 ---------- */

    private fun upload(video: File): Uploaded {
        val len = video.length()
        val start = (URL("$base/upload/v1beta/files").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000; readTimeout = 60_000
            setRequestProperty("x-goog-api-key", apiKey())
            setRequestProperty("X-Goog-Upload-Protocol", "resumable")
            setRequestProperty("X-Goog-Upload-Command", "start")
            setRequestProperty("X-Goog-Upload-Header-Content-Length", len.toString())
            setRequestProperty("X-Goog-Upload-Header-Content-Type", "video/mp4")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        start.outputStream.use { it.write("""{"file":{"display_name":"graph_teach"}}""".toByteArray()) }
        if (start.responseCode >= 300) {
            val err = start.errorStream?.bufferedReader()?.readText().orEmpty()
            start.disconnect(); error("start HTTP ${start.responseCode}: ${err.take(160)}")
        }
        val uploadUrl = start.getHeaderField("X-Goog-Upload-URL") ?: run {
            start.disconnect(); error("sin X-Goog-Upload-URL")
        }
        start.disconnect()

        val up = (URL(uploadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000; readTimeout = 300_000
            setRequestProperty("x-goog-api-key", apiKey())
            setRequestProperty("X-Goog-Upload-Offset", "0")
            setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
            setFixedLengthStreamingMode(len)
            doOutput = true
        }
        video.inputStream().use { input -> up.outputStream.use { out -> input.copyTo(out, 64 * 1024) } }
        val code = up.responseCode
        val body = (if (code < 400) up.inputStream else up.errorStream)?.bufferedReader()?.readText().orEmpty()
        up.disconnect()
        if (code >= 300) error("upload HTTP $code: ${body.take(160)}")
        val file = Json.parseToJsonElement(body).jsonObject["file"]!!.jsonObject
        val name = file["name"]!!.jsonPrimitive.content
        val uri = file["uri"]!!.jsonPrimitive.content
        LogBus.log("teach", "☁ video subido (${len / 1024}KB) → $name")
        return Uploaded(name, uri)
    }

    /** Espera a que el archivo salga de PROCESSING; falla si queda FAILED o se agota el tiempo. */
    private suspend fun waitActive(name: String) {
        repeat(45) {
            val c = (URL("$base/v1beta/$name").openConnection() as HttpURLConnection).apply {
                setRequestProperty("x-goog-api-key", apiKey())
                connectTimeout = 15_000; readTimeout = 30_000
            }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            c.disconnect()
            val state = runCatching {
                Json.parseToJsonElement(body).jsonObject["state"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            when (state) {
                "ACTIVE" -> return
                "FAILED" -> error("el video quedó FAILED en el servidor")
                else -> delay(2000)
            }
        }
        error("el video sigue en PROCESSING tras el tiempo máximo")
    }

    /* ---------- Estructuración: video → JSON de notas por app ---------- */

    private fun generate(fileUri: String): JsonObject {
        val prompt = """
            Eres Ü, un asistente que controla el teléfono Android del usuario. El usuario acaba de
            COMPARTIR SU PANTALLA y enseñarte, con su voz, cosas sobre cómo usa sus apps: te mostró
            datos, contactos, preferencias o formas de hacer las cosas que quiere que RECUERDES para
            cuando tú operes el teléfono por él.

            Mira TODO el video (imagen + audio) y extrae CONOCIMIENTO TEXTUAL durable y reutilizable,
            organizado POR APP. Ejemplos del tipo de nota que buscamos:
            - "El contacto de su mamá en WhatsApp está guardado como 'Ale', no como 'mamá'."
            - "En su banco, la cuenta de ahorros que usa para pagar es la que termina en 4321."

            REGLAS ESTRICTAS (fase 1, calidad sobre cantidad):
            - Cada nota: UNA frase, auto-contenida, con los nombres y datos CONCRETOS que viste/oíste.
            - Incluye SOLO lo que entiendas con certeza MUY ALTA y tenga valor real para operar la app
              después. Ante la duda, fuera. NO inventes datos que no aparezcan.
            - "app": el nombre visible de la app a la que aplica la nota (p.ej. "WhatsApp"). Si la nota
              es general y no pertenece a una app, usa "".
            - NO describas el árbol de UI ni pasos de navegación; solo el conocimiento/preferencia.
            - Si algo importante quedó ambiguo y necesitas confirmarlo, agrégalo en "questions" (una
              pregunta corta y natural que le harás por voz al usuario). Máximo 3. Si no hace falta
              preguntar nada, deja la lista vacía.
            - Si el video no contiene nada confiable que guardar, devuelve items y questions vacíos.

            Además, escribe un "summary": un resumen CORTO (1-3 frases), en primera persona y en tono
            cálido, de lo que ENTENDISTE del video, para decírselo al usuario en voz alta (p.ej.
            "Entendí que a tu mamá la tienes en WhatsApp como 'Ale' y que le escribes por las mañanas").
            Es lo que le explicarás de lo aprendido; si no aprendiste nada, dilo con naturalidad.

            Responde SOLO JSON:
            {"summary": "...", "items": [{"app": "WhatsApp", "note": "..."}], "questions": ["..."]}
        """.trimIndent()

        val req = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("fileData") {
                                put("mimeType", "video/mp4")
                                put("fileUri", fileUri)
                            }
                        }
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
            putJsonObject("generationConfig") { put("responseMimeType", "application/json") }
        }
        val reqBytes = Json.encodeToString(JsonObject.serializer(), req).toByteArray()
        val (code, body) = GeminiHttp.withRetry("teach") {
            val c = (URL("$base/v1beta/models/${model()}:generateContent").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000; readTimeout = 300_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey())
                doOutput = true
            }
            c.outputStream.use { it.write(reqBytes) }
            val status = c.responseCode
            val respBody = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            c.disconnect()
            status to respBody
        }
        if (code >= 300) { LogBus.log("teach", "HTTP $code: ${body.take(200)}"); error("Gemini HTTP $code") }
        val text = Json.parseToJsonElement(body).jsonObject["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            .firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        // Tolera ```fences``` y basura antes/después (un "}" de más reventaba el parseo estricto).
        return GeminiJson.firstJsonObject(text)
    }
}
