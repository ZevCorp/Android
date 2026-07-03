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
MUY IMPORTANTE: si el usuario indica (por narración o por contexto) que alguna parte es opcional, situacional o de primera vez (p.ej. "esto solo se configura una vez", diálogos que no siempre aparecen), dilo explícitamente en summary y en el step correspondiente.
Responde SOLO con JSON: {"goal": "objetivo de la tarea en una frase", "app": "app o apps usadas", "summary": "resumen de lo enseñado (incluye qué partes son opcionales/situacionales)", "steps": ["paso 1", "paso 2 (opcional: cuándo)", ...]}. Los steps deben ser accionables y en orden."""
    }
}

/**
 * Capa de inteligencia previa al guardado: con la lección (incluida la narración del video) organiza
 * los steps capturados en TRONCO (siempre) y RAMAS situacionales que se activan con --branch en la CLI.
 */
class GeminiCurator(
    private val apiKey: () -> String,
    private val model: () -> String,
) : WorkflowCurator {

    override suspend fun curate(lesson: Lesson, workflow: Workflow): Workflow = withContext(Dispatchers.IO) {
        val stepsText = workflow.steps.joinToString("\n") {
            "- order=${it.order} ${it.action} label=\"${it.label}\"" +
                (if (it.value.isNotBlank()) " value=\"${it.value.take(40)}\"" else "") +
                " screen=\"${it.screen}\""
        }
        val prompt = """
            Eres el organizador de workflows de Graph. Un usuario enseñó una tarea en video y estos son los pasos capturados del árbol de UI de Android.
            CONTEXTO DEL TUTORIAL (análisis del video, incluye lo que el usuario narró):
            META: ${lesson.goal}
            RESUMEN: ${lesson.summary}
            PASOS NARRADOS: ${lesson.steps.joinToString(" | ")}
            PASOS CAPTURADOS:
            $stepsText
            Identifica el TRONCO (pasos centrales, siempre necesarios) y las RAMAS opcionales/situacionales: configuraciones de primera vez, diálogos que no siempre aparecen, decisiones según contexto (p.ej. "configurar_direccion" en una app de comida). Una rama agrupa pasos consecutivos y luego el flujo se reincorpora al tronco. Solo crea ramas con evidencia real; si todo es central, no crees ninguna.
            Responde SOLO JSON:
            {"branches": [{"name": "snake_case_corto", "description": "cuándo activarla"}],
             "assignments": {"<order>": "nombre_de_rama"}}
            En assignments incluye SOLO los steps que pertenecen a una rama (los demás son tronco).
        """.trimIndent()

        val req = jo(
            "contents" to JsonArray(listOf(jo("role" to js("user"), "parts" to JsonArray(listOf(jtext(prompt)))))),
            "generationConfig" to jo("responseMimeType" to js("application/json")),
        )
        val res = http(
            "POST", "$BASE/v1beta/models/${model()}:generateContent",
            mapOf("Content-Type" to "application/json", "x-goog-api-key" to apiKey()),
            Json.encodeToString(JsonObject.serializer(), req).toByteArray(),
        ).orThrow()
        val text = Json.parseToJsonElement(res.body).jsonObject["candidates"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
            .firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        val obj = Json.parseToJsonElement(text).jsonObject

        val branches = obj["branches"]?.jsonArray.orEmpty().map { it.jsonObject }
            .mapNotNull { b -> b.str("name").takeIf { it.isNotBlank() }?.let { Branch(it, b.str("description")) } }
        val assignments = obj["assignments"]?.jsonObject.orEmpty()
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to (v.jsonPrimitive.contentOrNull ?: "") } }
            .toMap()
        val valid = branches.map { it.name }.toSet()
        LogBus.log("curador", "ramas: ${branches.joinToString { "${it.name} (${assignments.count { a -> a.value == it.name }} steps)" }.ifBlank { "ninguna" }}")
        workflow.copy(
            branches = branches,
            steps = workflow.steps.map { s ->
                s.copy(branch = assignments[s.order]?.takeIf { it in valid } ?: "")
            },
        )
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
    private var instructions = ""

    override fun begin(lesson: Lesson, instructions: String) {
        this.lesson = lesson
        this.instructions = instructions
        previousId = ""
        pending = emptyList()
        internalResults.clear()
        informText = ""
    }

    override fun inform(message: String) {
        informText = message
    }

    private fun fn(name: String, description: String, arg: String) = jo(
        "type" to js("function"),
        "name" to js(name),
        "description" to js(description),
        "parameters" to jo(
            "type" to js("object"),
            "properties" to jo(arg to jo("type" to js("string"))),
            "required" to JsonArray(listOf(js(arg))),
        ),
    )

    override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn = withContext(Dispatchers.IO) {
        fun image(png: ByteArray) = jo(
            "type" to js("image"), "mime_type" to js("image/png"),
            "data" to js(Base64.encodeToString(png, Base64.NO_WRAP)),
        )

        val input = mutableListOf<JsonElement>()
        if (previousId.isBlank()) {
            input += jo("type" to js("text"), "text" to js(goalPrompt(lesson!!, state)))
            state.screenshotPng?.let { input += image(it) }
        } else if (pending.isEmpty()) {
            // sin function-calls pendientes: mensaje de instrucción (repair / step delegado)
            input += jo("type" to js("text"), "text" to js(informText.ifBlank { "Continúa." }))
            state.screenshotPng?.let { input += image(it) }
            informText = ""
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
                fn("ask_user", "Pregunta al usuario cuando tengas una duda real e importante. El usuario responde con texto, voz o demostrándolo en pantalla.", "question"),
                fn("speak", "Di algo en voz alta al usuario con tu personalidad divertida. Úsalo SOLO para lo importante (avisos, dudas). No narres cada paso.", "text"),
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
        val intents = mutableListOf<String>()
        var question: String? = null
        var speech: String? = null
        var text = ""
        for (item in items.map { it.jsonObject }) {
            when (item.str("type")) {
                "text" -> text += item.str("text")
                "function_call" -> {
                    val name = item.str("name")
                    val id = item.str("call_id").ifBlank { item.str("id") }.ifBlank { "call_${calls.size}" }
                    val args = item["arguments"]?.jsonObject ?: item["args"]?.jsonObject ?: JsonObject(emptyMap())
                    calls += Call(id, name, args["safety_decision"] != null)
                    if (name !in setOf("ask_user", "speak")) intents += args.str("intent")

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
                        "speak" -> speech = args.str("text")
                    }
                }
            }
        }
        pending = calls
        // speak/ask_user no llevan screenshot en su function_result: se resuelven aquí.
        speech?.let { internalResults[calls.first { c -> c.name == "speak" }.id] = jo("said" to js("true")) }
        LogBus.log("gemini", if (calls.isEmpty()) "turno final: ${text.take(120)}"
        else "turno: ${calls.joinToString(", ") { it.name }}" + (question?.let { " · pregunta" } ?: ""))
        return BrainTurn(actions, question, done = calls.isEmpty(), text = text,
            narration = intents.firstOrNull { it.isNotBlank() } ?: "", speech = speech, intents = intents)
    }

    /** Supervisor del learning: mira la captura tras un step del workflow y decide si quedó bien. */
    override suspend fun judge(goal: String, step: Step, performed: Boolean, state: ScreenState): Verdict =
        withContext(Dispatchers.IO) {
            runCatching {
                val desc = "${step.action} \"${step.label.ifBlank { step.selector.short() }}\"" +
                    if (step.value.isNotBlank()) " con valor \"${step.value.take(60)}\"" else ""
                val parts = mutableListOf<JsonElement>()
                state.screenshotPng?.let {
                    parts += jo("inlineData" to jo("mimeType" to js("image/png"),
                        "data" to js(Base64.encodeToString(it, Base64.NO_WRAP))))
                }
                parts += jtext(
                    "Tarea: $goal. Se acaba de ejecutar por accesibilidad el paso ${step.order}: $desc " +
                        "(resultado técnico: ${if (performed) "ejecutado" else "falló"})." +
                        (step.branch.takeIf { it.isNotBlank() }
                            ?.let { " Este paso pertenece a la rama opcional \"$it\" y puede no aplicar en esta ejecución." } ?: "") +
                        " Observa la captura: ¿el paso quedó aplicado correctamente y la pantalla está en el estado esperado para continuar? " +
                        "Si el paso simplemente no aplica en este contexto (p.ej. un diálogo de primera vez que no apareció), marca applicable=false. " +
                        """Responde SOLO JSON: {"valid": true|false, "applicable": true|false, "reason": "por qué"}"""
                )
                val req = jo(
                    "contents" to JsonArray(listOf(jo("role" to js("user"), "parts" to JsonArray(parts)))),
                    "generationConfig" to jo("responseMimeType" to js("application/json")),
                )
                val res = http(
                    "POST", "$BASE/v1beta/models/${model()}:generateContent",
                    mapOf("Content-Type" to "application/json", "x-goog-api-key" to apiKey()),
                    Json.encodeToString(JsonObject.serializer(), req).toByteArray(),
                ).orThrow()
                val text = Json.parseToJsonElement(res.body).jsonObject["candidates"]!!.jsonArray[0]
                    .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
                    .firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                val obj = Json.parseToJsonElement(text).jsonObject
                Verdict(
                    obj["valid"]?.jsonPrimitive?.booleanOrNull ?: performed,
                    obj.str("reason"),
                    obj["applicable"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }.getOrElse { Verdict(performed, "supervisor no disponible: ${it.message?.take(80)}") }
                .also { LogBus.log("gemini", "judge step ${step.order} → ${if (it.valid) "válido" else "inválido"} · ${it.reason.take(80)}") }
        }

    /** Observador en vivo del Teaching: solo mira, puede hablar/preguntar (sin computer_use). */
    override suspend fun observe(lesson: Lesson, state: ScreenState, recentActions: List<String>): BrainTurn? =
        withContext(Dispatchers.IO) {
            runCatching {
                val parts = mutableListOf<JsonElement>()
                state.screenshotPng?.let {
                    parts += jo("inlineData" to jo("mimeType" to js("image/png"),
                        "data" to js(Base64.encodeToString(it, Base64.NO_WRAP))))
                }
                parts += jtext(
                    "Eres Graph, un asistente con personalidad viva y divertida que está APRENDIENDO viendo al " +
                        "usuario enseñarte una tarea en su teléfono. Acciones recientes del usuario: " +
                        recentActions.joinToString("; ").ifBlank { "(ninguna)" } + ". " +
                        "Mira la pantalla. Si tienes una DUDA IMPORTANTE sobre lo que enseña (p.ej. \"¿y si el " +
                        "restaurante está cerrado?\"), formúlala. Si no, quédate callado. No comentes por comentar. " +
                        """Responde SOLO JSON: {"say": "algo corto que decir en voz o vacío", "question": "pregunta importante o vacío"}"""
                )
                val req = jo(
                    "contents" to JsonArray(listOf(jo("role" to js("user"), "parts" to JsonArray(parts)))),
                    "generationConfig" to jo("responseMimeType" to js("application/json")),
                )
                val res = http(
                    "POST", "$BASE/v1beta/models/${model()}:generateContent",
                    mapOf("Content-Type" to "application/json", "x-goog-api-key" to apiKey()),
                    Json.encodeToString(JsonObject.serializer(), req).toByteArray(),
                ).orThrow()
                val text = Json.parseToJsonElement(res.body).jsonObject["candidates"]!!.jsonArray[0]
                    .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
                    .firstNotNullOf { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                val obj = Json.parseToJsonElement(text).jsonObject
                val say = obj.str("say"); val q = obj.str("question")
                if (say.isBlank() && q.isBlank()) null
                else BrainTurn(question = q.ifBlank { null }, speech = say.ifBlank { null })
            }.getOrNull()
        }

    private fun goalPrompt(lesson: Lesson, state: ScreenState) = """
        Eres Graph, un asistente con PERSONALIDAD viva y divertida que controla un teléfono Android REAL.
        Tu objetivo es reproducir este tutorial que el usuario enseñó en video:
        META: ${lesson.goal}
        APP: ${lesson.app}
        RESUMEN: ${lesson.summary}
        PASOS: ${lesson.steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString(" ")}
        En el campo "intent" de cada acción escribe una frase corta y con chispa de lo que haces (ej: "Abro el reloj ⏰", "Busco la mejor pizza para ti 🍕"): eso se le narra al usuario.
        Usa speak SOLO para avisos importantes y ask_user SOLO para dudas reales (p.ej. "¿el Uber es para ti o para tu mamá?"). No hables por hablar.
        Usa open_app para abrir apps. Cuando el objetivo esté completo, responde SOLO con texto (sin llamar funciones).
        IMPORTANTE: en las capturas aparece una pequeña carita blanca flotante (el asistente Graph). IGNÓRALA por completo: no es parte de la app, nunca la toques ni interactúes con ella.
        ${instructions.takeIf { it.isNotBlank() }?.let { "MODO ESPECIAL: $it" } ?: ""}
        Pantalla actual: ${state.screen}
    """.trimIndent()
}
