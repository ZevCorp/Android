package com.zevcorp.graph.platform

import android.util.Base64
import graph.core.domain.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private const val BASE = "https://generativelanguage.googleapis.com"

private class HttpRes(val code: Int, val body: String, val headers: Map<String, String>)

private fun http(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpRes {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = 30_000
    c.readTimeout = 300_000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    if (body != null) {
        c.doOutput = true
        c.outputStream.use { it.write(body) }
    }
    val code = c.responseCode
    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
    val hs = c.headerFields.filterKeys { it != null }
        .map { it.key.lowercase() to it.value.joinToString(",") }.toMap()
    c.disconnect()
    return HttpRes(code, text, hs)
}

private fun HttpRes.orThrow(): HttpRes =
    if (code < 300) this else error("Gemini HTTP $code: ${body.take(400)}")

/* --- helpers JSON --- */
private fun js(s: String) = JsonPrimitive(s)
private fun jo(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())
private fun jtext(t: String) = jo("text" to js(t))
private fun jimg(png: ByteArray) = jo(
    "inlineData" to jo("mimeType" to js("image/png"), "data" to js(Base64.encodeToString(png, Base64.NO_WRAP)))
)

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull ?: ""

/**
 * Etapa 1 · Teaching: sube el video grabado y le pide al LLM que lo entienda como tutorial.
 */
class GeminiTutorialAnalyzer(
    private val apiKey: () -> String,
    private val model: () -> String,
) : TutorialAnalyzer {

    override suspend fun analyze(videoPath: String): Lesson = withContext(Dispatchers.IO) {
        val file = File(videoPath)
        val bytes = file.readBytes()

        val start = http(
            "POST", "$BASE/upload/v1beta/files?key=${apiKey()}",
            mapOf(
                "X-Goog-Upload-Protocol" to "resumable",
                "X-Goog-Upload-Command" to "start",
                "X-Goog-Upload-Header-Content-Length" to bytes.size.toString(),
                "X-Goog-Upload-Header-Content-Type" to "video/mp4",
                "Content-Type" to "application/json",
            ),
            """{"file":{"display_name":"${file.name}"}}""".toByteArray(),
        ).orThrow()
        val uploadUrl = start.headers["x-goog-upload-url"] ?: error("Files API no devolvió upload url")

        var fileInfo = Json.parseToJsonElement(
            http("POST", uploadUrl, mapOf("X-Goog-Upload-Command" to "upload, finalize", "X-Goog-Upload-Offset" to "0"), bytes)
                .orThrow().body
        ).jsonObject["file"]!!.jsonObject
        while (fileInfo.str("state") == "PROCESSING") {
            delay(3000)
            fileInfo = Json.parseToJsonElement(
                http("GET", "$BASE/v1beta/${fileInfo.str("name")}?key=${apiKey()}").orThrow().body
            ).jsonObject
        }

        val req = jo(
            "contents" to JsonArray(listOf(jo("role" to js("user"), "parts" to JsonArray(listOf(
                jo("fileData" to jo("mimeType" to js("video/mp4"), "fileUri" to js(fileInfo.str("uri")))),
                jtext(ANALYZE_PROMPT),
            ))))),
            "generationConfig" to jo("responseMimeType" to js("application/json")),
        )
        val res = http(
            "POST", "$BASE/v1beta/models/${model()}:generateContent?key=${apiKey()}",
            mapOf("Content-Type" to "application/json"), Json.encodeToString(JsonObject.serializer(), req).toByteArray(),
        ).orThrow()

        val parts = Json.parseToJsonElement(res.body).jsonObject["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
        val obj = Json.parseToJsonElement(
            parts.firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        ).jsonObject

        Lesson(
            id = "lesson_${System.currentTimeMillis()}",
            goal = obj.str("goal"),
            app = obj.str("app"),
            summary = obj.str("summary"),
            steps = obj["steps"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            videoPath = videoPath,
        )
    }

    private companion object {
        const val ANALYZE_PROMPT = """Este video es un tutorial que un usuario grabó con la pantalla de su teléfono Android (el audio puede incluir su narración). Analiza QUÉ quiso enseñar, como si fuera una clase.
Responde SOLO con JSON: {"goal": "objetivo de la tarea en una frase", "app": "app o apps usadas", "summary": "resumen de lo enseñado", "steps": ["paso 1", "paso 2", ...]}. Los steps deben ser accionables y en orden."""
    }
}

/**
 * Etapa 2 · Learning: Gemini 3.5 Flash con computer use controla el teléfono.
 * Protocolo: screenshot → functionCall (click_at/type_text_at/... en coordenadas 0-999) → functionResponse.
 * Se añaden funciones propias: open_app (apps Android) y ask_user (dudas → feedback del usuario).
 */
class GeminiComputerUse(
    private val apiKey: () -> String,
    private val model: () -> String,
) : ComputerUseBrain {

    private val contents = mutableListOf<JsonElement>()
    private var pendingCalls = listOf<String>()
    private var informText = ""
    private var lesson: Lesson? = null

    override fun begin(lesson: Lesson) {
        this.lesson = lesson
        contents.clear()
        pendingCalls = emptyList()
        informText = ""
    }

    override fun inform(message: String) {
        informText = message
    }

    override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn = withContext(Dispatchers.IO) {
        val parts = mutableListOf<JsonElement>()
        if (contents.isEmpty()) {
            parts += jtext(goalPrompt(lesson!!, state))
        } else {
            pendingCalls.forEachIndexed { i, name ->
                val response = if (name == "ask_user")
                    jo("answer" to js(informText.ifBlank { "(sin respuesta)" }))
                else
                    jo("url" to js(state.screen), "result" to js(actionResults.getOrElse(i) { "ok" }))
                parts += jo("functionResponse" to jo("name" to js(name), "response" to response))
            }
            informText = ""
        }
        state.screenshotPng?.let { parts += jimg(it) } ?: run { parts += jtext("(captura de pantalla no disponible)") }
        contents += jo("role" to js("user"), "parts" to JsonArray(parts))

        val req = jo(
            "contents" to JsonArray(contents),
            "tools" to JsonArray(listOf(
                jo("computerUse" to jo("environment" to js("ENVIRONMENT_BROWSER"))),
                jo("functionDeclarations" to JsonArray(listOf(
                    fn("open_app", "Abre una app de Android por nombre/paquete, o una URL", "name"),
                    fn("ask_user", "Pregunta al usuario cuando tengas dudas sobre cómo seguir el tutorial", "question"),
                ))),
            )),
        )
        val res = http(
            "POST", "$BASE/v1beta/models/${model()}:generateContent?key=${apiKey()}",
            mapOf("Content-Type" to "application/json"), Json.encodeToString(JsonObject.serializer(), req).toByteArray(),
        ).orThrow()

        val content = Json.parseToJsonElement(res.body).jsonObject["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject
        contents += content
        parseTurn(content, state)
    }

    private fun parseTurn(content: JsonObject, state: ScreenState): BrainTurn {
        val actions = mutableListOf<AgentAction>()
        val callNames = mutableListOf<String>()
        var question: String? = null
        var text = ""
        for (part in content["parts"]?.jsonArray.orEmpty().map { it.jsonObject }) {
            part["text"]?.jsonPrimitive?.contentOrNull?.let { text += it }
            val call = part["functionCall"]?.jsonObject ?: continue
            val name = call.str("name")
            callNames += name
            val args = call["args"]?.jsonObject ?: JsonObject(emptyMap())
            fun px(key: String, size: Int) = (args[key]?.jsonPrimitive?.intOrNull ?: 0) * size / 1000
            when (name) {
                "click_at" -> actions += AgentAction.ClickAt(px("x", state.width), px("y", state.height))
                "type_text_at" -> {
                    actions += AgentAction.TypeAt(px("x", state.width), px("y", state.height), args.str("text"))
                    if (args["press_enter"]?.jsonPrimitive?.booleanOrNull == true) actions += AgentAction.Key("enter")
                }
                "scroll_document", "scroll_at" -> actions += AgentAction.Scroll(args.str("direction") != "up")
                "key_combination" -> actions += AgentAction.Key(args.str("keys"))
                "wait_5_seconds" -> actions += AgentAction.Wait(5000)
                "go_back" -> actions += AgentAction.Key("back")
                "navigate" -> actions += AgentAction.OpenApp(args.str("url"))
                "open_web_browser" -> actions += AgentAction.OpenApp("https://www.google.com")
                "open_app" -> actions += AgentAction.OpenApp(args.str("name"))
                "ask_user" -> question = args.str("question")
            }
        }
        pendingCalls = callNames
        return BrainTurn(actions, question, done = callNames.isEmpty(), text = text)
    }

    private fun fn(name: String, description: String, arg: String) = jo(
        "name" to js(name),
        "description" to js(description),
        "parameters" to jo(
            "type" to js("OBJECT"),
            "properties" to jo(arg to jo("type" to js("STRING"))),
            "required" to JsonArray(listOf(js(arg))),
        ),
    )

    private fun goalPrompt(lesson: Lesson, state: ScreenState) = """
        Controlas un teléfono Android REAL mediante capturas de pantalla y acciones (click_at, type_text_at, scroll_document, open_app, ask_user...). Las coordenadas van normalizadas a 0-999.
        Tu objetivo es reproducir este tutorial que el usuario enseñó en video:
        META: ${lesson.goal}
        APP: ${lesson.app}
        RESUMEN: ${lesson.summary}
        PASOS: ${lesson.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(" ")}
        Usa open_app para abrir apps. Si tienes una duda real sobre cómo continuar, usa ask_user (el usuario puede responderte o demostrártelo en pantalla).
        Cuando el objetivo esté completo, responde SOLO con texto (sin llamar funciones) describiendo el resultado.
        Pantalla actual: ${state.screen}
    """.trimIndent()
}
