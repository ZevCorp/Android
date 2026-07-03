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
 * Etapa 2 · Learning: Gemini 3.5 Flash con computer use nativo (Interactions API).
 *
 * Protocolo (ai.google.dev/gemini-api/docs/computer-use):
 *  - POST /v1beta/interactions con header x-goog-api-key, tool {type:"computer_use", environment:"mobile"}.
 *  - El servidor mantiene la conversación: cada turno siguiente sólo envía previous_interaction_id
 *    + los function_result (con call_id, url y screenshot como imagen inline).
 *  - Funciones mobile: click/type/open_app/list_apps/drag_and_drop/long_press/press_key/go_back/wait/
 *    take_screenshot, en coordenadas normalizadas 0-1000. Se añade ask_user como función custom.
 */
class GeminiComputerUse(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val listApps: () -> String,
) : ComputerUseBrain {

    private class Call(val id: String, val name: String, val safety: Boolean)

    private var previousId = ""
    private var pending = listOf<Call>()
    private val internalResults = HashMap<String, JsonObject>() // resultados resueltos localmente (list_apps)
    private var informText = ""
    private var lesson: Lesson? = null

    override fun begin(lesson: Lesson) {
        this.lesson = lesson
        previousId = ""
        pending = emptyList()
        internalResults.clear()
        informText = ""
    }

    override fun inform(message: String) {
        informText = message
    }

    override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn = withContext(Dispatchers.IO) {
        fun image(png: ByteArray) = jo(
            "type" to js("image"), "mime_type" to js("image/png"),
            "data" to js(Base64.encodeToString(png, Base64.NO_WRAP)),
        )

        val input = mutableListOf<JsonElement>()
        if (previousId.isBlank()) {
            input += jo("type" to js("text"), "text" to js(goalPrompt(lesson!!, state)))
            state.screenshotPng?.let { input += image(it) }
        } else {
            pending.forEachIndexed { i, call ->
                val payload = when {
                    call.name == "ask_user" -> jo("answer" to js(informText.ifBlank { "(sin respuesta)" }))
                    internalResults.containsKey(call.id) -> internalResults.getValue(call.id)
                    else -> jo("url" to js(state.screen), "result" to js(actionResults.getOrElse(i) { "ok" }))
                }
                val result = mutableListOf<JsonElement>(
                    jo("type" to js("text"), "text" to js(Json.encodeToString(JsonObject.serializer(), payload))))
                if (i == pending.lastIndex) state.screenshotPng?.let { result += image(it) }

                val fields = mutableListOf(
                    "type" to js("function_result"), "name" to js(call.name),
                    "call_id" to js(call.id), "result" to JsonArray(result))
                // El usuario supervisa el Learning en vivo: se confirman las acciones sensibles.
                if (call.safety) fields += "safety_acknowledgement" to JsonPrimitive(true)
                input += JsonObject(fields.toMap())
            }
            informText = ""
        }
        internalResults.clear()

        val fields = mutableListOf(
            "model" to js(model()),
            "input" to JsonArray(input),
            "tools" to JsonArray(listOf(
                jo("type" to js("computer_use"), "environment" to js("mobile")),
                jo(
                    "type" to js("function"),
                    "name" to js("ask_user"),
                    "description" to js("Pregunta al usuario cuando tengas una duda real sobre cómo seguir el tutorial. El usuario responde con texto, voz o demostrándolo en pantalla."),
                    "parameters" to jo(
                        "type" to js("object"),
                        "properties" to jo("question" to jo("type" to js("string"))),
                        "required" to JsonArray(listOf(js("question"))),
                    ),
                ),
            )),
        )
        if (previousId.isNotBlank()) fields += "previous_interaction_id" to js(previousId)

        val res = http(
            "POST", "$BASE/v1beta/interactions",
            mapOf("Content-Type" to "application/json", "x-goog-api-key" to apiKey()),
            Json.encodeToString(JsonObject.serializer(), JsonObject(fields.toMap())).toByteArray(),
        )
        if (res.code >= 300) LogBus.log("gemini", "HTTP ${res.code}: ${res.body.take(300)}")
        res.orThrow()
        parseTurn(Json.parseToJsonElement(res.body).jsonObject, state)
    }

    private fun parseTurn(body: JsonObject, state: ScreenState): BrainTurn {
        previousId = body.str("id").ifBlank { previousId }
        val items = (body["steps"] ?: body["outputs"] ?: body["output"])?.jsonArray.orEmpty()

        val actions = mutableListOf<AgentAction>()
        val calls = mutableListOf<Call>()
        var question: String? = null
        var text = ""
        for (item in items.map { it.jsonObject }) {
            when (item.str("type")) {
                "text" -> text += item.str("text")
                "function_call" -> {
                    val name = item.str("name")
                    val id = item.str("call_id").ifBlank { item.str("id") }.ifBlank { "call_${calls.size}" }
                    val args = item["arguments"]?.jsonObject ?: item["args"]?.jsonObject ?: JsonObject(emptyMap())
                    calls += Call(id, name, args["safety_decision"] != null)

                    fun px(key: String, size: Int) =
                        args[key]?.jsonPrimitive?.intOrNull?.let { it * size / 1000 } ?: -1
                    when (name) {
                        "click" -> actions += AgentAction.ClickAt(px("x", state.width), px("y", state.height))
                        "type" -> {
                            // sin x/y escribe en el campo enfocado (px devuelve -1 → fallback en UiSurface)
                            actions += AgentAction.TypeAt(px("x", state.width), px("y", state.height), args.str("text"))
                            if (args["press_enter"]?.jsonPrimitive?.booleanOrNull == true) actions += AgentAction.Key("enter")
                        }
                        "open_app" -> actions += AgentAction.OpenApp(args.str("app_name").ifBlank { args.str("name") })
                        "navigate" -> actions += AgentAction.OpenApp(args.str("url"))
                        "drag_and_drop" -> actions += AgentAction.Swipe(
                            px("start_x", state.width), px("start_y", state.height),
                            px("end_x", state.width), px("end_y", state.height), 400)
                        "long_press" -> {
                            val x = px("x", state.width); val y = px("y", state.height)
                            actions += AgentAction.Swipe(x, y, x, y,
                                (args["seconds"]?.jsonPrimitive?.intOrNull ?: 1) * 1000L)
                        }
                        "scroll" -> actions += AgentAction.Scroll(args.str("direction") != "up")
                        "press_key" -> actions += AgentAction.Key(args.str("key"))
                        "go_back" -> actions += AgentAction.Key("back")
                        "wait" -> actions += AgentAction.Wait((args["seconds"]?.jsonPrimitive?.intOrNull ?: 2) * 1000L)
                        "take_screenshot" -> {} // el screenshot va en todos los function_result
                        "list_apps" -> internalResults[id] = jo("apps" to js(listApps()))
                        "ask_user" -> question = args.str("question")
                    }
                }
            }
        }
        pending = calls
        LogBus.log("gemini", if (calls.isEmpty()) "turno final: ${text.take(120)}"
        else "turno: ${calls.joinToString(", ") { it.name }}" + (question?.let { " · pregunta" } ?: ""))
        return BrainTurn(actions, question, done = calls.isEmpty(), text = text)
    }

    private fun goalPrompt(lesson: Lesson, state: ScreenState) = """
        Controlas un teléfono Android REAL. Tu objetivo es reproducir este tutorial que el usuario enseñó en video:
        META: ${lesson.goal}
        APP: ${lesson.app}
        RESUMEN: ${lesson.summary}
        PASOS: ${lesson.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(" ")}
        Usa open_app para abrir apps. Si tienes una duda real sobre cómo continuar, usa ask_user (el usuario puede responderte por texto, voz o demostrándotelo en pantalla).
        Cuando el objetivo esté completo, responde SOLO con texto (sin llamar funciones) describiendo el resultado.
        Pantalla actual: ${state.screen}
    """.trimIndent()
}
