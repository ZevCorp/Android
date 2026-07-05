package com.zevcorp.graph.platform

import android.util.Base64
import graph.core.domain.*
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private const val BASE = "https://generativelanguage.googleapis.com"

private class HttpRes(val code: Int, val body: String)

private fun http(url: String, headers: Map<String, String>, body: ByteArray): HttpRes {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.connectTimeout = 30_000
    c.readTimeout = 300_000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    c.doOutput = true
    c.outputStream.use { it.write(body) }
    val code = c.responseCode
    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
    c.disconnect()
    return HttpRes(code, text)
}

private fun js(s: String) = JsonPrimitive(s)
private fun jo(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())
private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull ?: ""

/**
 * Gemini 3.5 Flash con computer-use nativo (Interactions API) MÁS las herramientas MCP declaradas.
 *
 * Protocolo (ai.google.dev/gemini-api/docs/computer-use): POST /v1beta/interactions con
 * tool {type:"computer_use", environment:"mobile"}; el servidor mantiene la conversación vía
 * previous_interaction_id y cada turno reenvía los function_result (con screenshot inline).
 * Las herramientas MCP se añaden como funciones custom junto a ask_user y speak; el modelo elige
 * en cada turno entre un gesto MCP y las acciones de computer-use.
 */
class GeminiBrain(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val tools: List<McpTool>,
    private val listApps: () -> String,
) : Brain {

    private val mcpNames = tools.map { it.name }.toSet()

    private class Call(val id: String, val name: String, val safety: Boolean)

    private var previousId = ""
    private var pending = listOf<Call>()
    private val internalResults = HashMap<String, JsonObject>() // resueltos localmente (list_apps)
    private var informText = ""
    private var goal = ""

    override fun begin(goal: String) {
        this.goal = goal
        previousId = ""
        pending = emptyList()
        internalResults.clear()
        informText = ""
    }

    override fun inform(message: String) {
        informText = message
    }

    /** Declaración de función para el API a partir de una herramienta MCP (params string, con enum). */
    private fun mcpFn(t: McpTool) = buildJsonObject {
        put("type", "function")
        put("name", t.name)
        put("description", t.description)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                t.params.forEach { p ->
                    putJsonObject(p.name) {
                        put("type", "string")
                        put("description", p.description)
                        if (p.options.isNotEmpty()) putJsonArray("enum") { p.options.forEach { add(it) } }
                    }
                }
            }
            putJsonArray("required") { t.params.forEach { add(it.name) } }
        }
    }

    private fun customFn(name: String, description: String, arg: String) = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") { putJsonObject(arg) { put("type", "string") } }
            putJsonArray("required") { add(arg) }
        }
    }

    override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn = withContext(Dispatchers.IO) {
        fun image(png: ByteArray) = jo(
            "type" to js("image"), "mime_type" to js("image/png"),
            "data" to js(Base64.encodeToString(png, Base64.NO_WRAP)),
        )

        fun textItem(t: String) = jo("type" to js("text"), "text" to js(t))
        fun jsonItem(o: JsonObject) = textItem(Json.encodeToString(JsonObject.serializer(), o))
        // Bloque de georreferenciación: dónde está el asistente, en texto (sin imagen).
        val stateBlock = "Pantalla actual: ${state.screen}\nDónde estás (árbol de UI de Android):\n${state.uiContext}"

        val input = mutableListOf<JsonElement>()
        if (previousId.isBlank()) {
            input += textItem(goalPrompt(state, stateBlock))
            state.screenshotPng?.let { input += image(it) }
        } else if (pending.isEmpty()) {
            input += textItem(informText.ifBlank { "Continúa." } + "\n$stateBlock")
            state.screenshotPng?.let { input += image(it) }
            informText = ""
        } else {
            pending.forEachIndexed { i, call ->
                val result = mutableListOf<JsonElement>()
                when {
                    // take_screenshot: ES el momento de mandar la imagen (computer-use bajo demanda).
                    call.name == "take_screenshot" -> {
                        result += textItem("Captura de la pantalla actual.")
                        state.screenshotPng?.let { result += image(it) }
                    }
                    call.name == "ask_user" -> result += jsonItem(jo("answer" to js(informText.ifBlank { "(sin respuesta)" })))
                    internalResults.containsKey(call.id) -> result += jsonItem(internalResults.getValue(call.id))
                    else -> {
                        result += jsonItem(jo("screen" to js(state.screen), "ui" to js(state.uiContext),
                            "result" to js(actionResults.getOrElse(i) { "ok" })))
                        // imagen solo si el modelo la pidió (tras un tap/type de computer-use)
                        if (i == pending.lastIndex) state.screenshotPng?.let { result += image(it) }
                    }
                }
                val fields = mutableListOf(
                    "type" to js("function_result"), "name" to js(call.name),
                    "call_id" to js(call.id), "result" to JsonArray(result))
                if (call.safety) fields += "safety_acknowledgement" to JsonPrimitive(true)
                input += JsonObject(fields.toMap())
            }
            informText = ""
        }
        internalResults.clear()

        val toolDecls = mutableListOf<JsonElement>(jo("type" to js("computer_use"), "environment" to js("mobile")))
        tools.forEach { toolDecls += mcpFn(it) }
        toolDecls += customFn("ask_user", "Pregunta al usuario cuando tengas una duda real e importante. Responde con texto o voz.", "question")
        toolDecls += customFn("speak", "Di algo en voz alta con tu personalidad. Solo para lo importante; no narres cada paso.", "text")

        val fields = mutableListOf(
            "model" to js(model()),
            "input" to JsonArray(input),
            "tools" to JsonArray(toolDecls),
        )
        if (previousId.isNotBlank()) fields += "previous_interaction_id" to js(previousId)

        val kb = (state.screenshotPng?.size ?: 0) / 1024
        val t0 = android.os.SystemClock.elapsedRealtime()
        val res = http(
            "$BASE/v1beta/interactions",
            mapOf("Content-Type" to "application/json", "x-goog-api-key" to apiKey()),
            Json.encodeToString(JsonObject.serializer(), JsonObject(fields.toMap())).toByteArray(),
        )
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        LogBus.log("gemini", "interacción → HTTP ${res.code} · ${ms}ms · envié ${kb}KB de pantalla · recibí ${res.body.length}B")
        if (res.code >= 300) {
            LogBus.log("gemini", "ERROR ${res.code}: ${res.body.take(300)}")
            error("Gemini HTTP ${res.code}: ${res.body.take(200)}")
        }
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
                    when {
                        name in mcpNames -> actions += AgentAction.Mcp(
                            name, args.filterKeys { it != "intent" }.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" })
                        name == "click" -> actions += AgentAction.Tap(px("x", state.width), px("y", state.height))
                        name == "type" -> {
                            actions += AgentAction.Type(px("x", state.width), px("y", state.height), args.str("text"))
                            if (args["press_enter"]?.jsonPrimitive?.booleanOrNull == true) actions += AgentAction.Key("enter")
                        }
                        name == "open_app" -> actions += AgentAction.OpenApp(args.str("app_name").ifBlank { args.str("name") })
                        name == "navigate" -> actions += AgentAction.OpenApp(args.str("url"))
                        name == "drag_and_drop" -> actions += AgentAction.Swipe(
                            px("start_x", state.width), px("start_y", state.height),
                            px("end_x", state.width), px("end_y", state.height), 400)
                        name == "long_press" -> {
                            val x = px("x", state.width); val y = px("y", state.height)
                            actions += AgentAction.Swipe(x, y, x, y, (args["seconds"]?.jsonPrimitive?.intOrNull ?: 1) * 1000L)
                        }
                        name == "scroll" -> actions += AgentAction.Scroll(args.str("direction") != "up")
                        name == "press_key" -> actions += AgentAction.Key(args.str("key"))
                        name == "go_back" -> actions += AgentAction.Key("back")
                        name == "wait" -> actions += AgentAction.Wait((args["seconds"]?.jsonPrimitive?.intOrNull ?: 2) * 1000L)
                        name == "take_screenshot" -> {} // el screenshot va en cada function_result
                        name == "list_apps" -> internalResults[id] = jo("apps" to js(listApps()))
                        name == "ask_user" -> question = args.str("question")
                        name == "speak" -> speech = args.str("text")
                    }
                }
            }
        }
        pending = calls
        speech?.let { s -> calls.firstOrNull { it.name == "speak" }?.let { internalResults[it.id] = jo("said" to js("true")) } }
        // El próximo turno adjunta screenshot si el modelo pidió ver o va a tocar por coordenadas.
        val needsShot = calls.any { it.name == "take_screenshot" } ||
            actions.any { it is AgentAction.Tap || it is AgentAction.Type }
        when {
            calls.isNotEmpty() ->
                LogBus.log("gemini", "decide: ${calls.joinToString(", ") { it.name }}" + (question?.let { " · pregunta" } ?: ""))
            text.isNotBlank() ->
                LogBus.log("gemini", "responde con texto (${text.length} chars): ${text.take(160)}")
            else -> // ni acciones ni texto: diagnóstico del "final vacío"
                LogBus.log("gemini", "final VACÍO · items recibidos: [${items.joinToString(", ") { it.jsonObject.str("type") }}]")
        }
        return BrainTurn(actions, question, done = calls.isEmpty(), text = text, needsScreenshot = needsShot,
            narration = intents.firstOrNull { it.isNotBlank() } ?: "", speech = speech, intents = intents)
    }

    /** Regla para apps con mapa aprendido: cadena completa desde el primer turno, sin "abrir y mirar". */
    private val learnedRule: String
        get() {
            val learned = tools.filter { it.via.startsWith("aprendido") }
            if (learned.isEmpty()) return ""
            return """
        HERRAMIENTAS APRENDIDAS (mapas de apps que YA conoces): ${learned.joinToString(", ") { it.name }}.
        REGLA DE ORO con apps aprendidas: si la tarea es en una app cuya herramienta aprendida existe
        (su descripción dice [app: paquete] y documenta la pantalla), NO abras la app "a ver qué hay":
        encadena DESDE LA PRIMERA RESPUESTA launch_app + la herramienta aprendida con la secuencia
        COMPLETA de taps (varias function_call juntas). Su documentación ya te dice exactamente qué
        elementos hay; confía en ella y solo cae a computer-use si la herramienta reporta pasos fallidos.
            """.trimIndent()
        }

    private fun goalPrompt(state: ScreenState, stateBlock: String) = """
        Eres Graph, un asistente con PERSONALIDAD viva y divertida que controla un teléfono Android REAL.
        Objetivo del usuario: $goal

        CÓMO VES LA PANTALLA: por defecto NO recibes una imagen, sino una descripción de TEXTO del árbol de
        UI (paquete, tipo de pantalla, etiquetas visibles). Con eso ubícate (home, cajón de apps, una app,
        notificaciones…) y decide. Solo cuando necesites tocar un elemento visual concreto, llama
        take_screenshot para obtener una imagen y en el siguiente turno haz click con coordenadas.

        DOS formas de actuar, elige la más directa:
        1) HERRAMIENTAS MCP (function-calling directo, sin imagen): gestos de navegación (home, cajón,
           notificaciones, paneo, scroll) y ACCIONES DEL SISTEMA por Intent/API — abrir apps, alarmas,
           timers, llamar, SMS, correo, calendario, buscar en web, mapas/rutas, cámara, ajustes,
           portapapeles. Herramientas: ${tools.joinToString(", ") { it.name }}.
        2) COMPUTER-USE (requiere imagen): solo para tocar elementos concretos DENTRO de una app;
           llama take_screenshot y luego click/type.
        REGLA: para cualquier tarea del sistema (alarma, timer, llamada, abrir app, buscar, calendario,
        ajustes…) usa SIEMPRE la herramienta MCP correspondiente, NO computer-use: es directa y sin UI.
        Si el plan son varias herramientas MCP encadenadas y predecibles (p.ej. ir al home y luego abrir
        el cajón), LLÁMALAS TODAS EN UNA SOLA RESPUESTA (varias function_call juntas) para ahorrar turnos.
        $learnedRule

        En el campo "intent" de cada acción escribe una frase corta y con chispa (ej: "Abro el cajón de apps 📲").
        Usa speak SOLO para avisos importantes y ask_user SOLO para dudas reales. No hables por hablar.
        Si el usuario solo quiere saber qué puedes hacer, respóndele con TEXTO describiendo tus herramientas (sin actuar).
        Cuando el objetivo esté completo, responde SOLO con texto (sin llamar funciones).
        En las capturas puede aparecer una carita blanca flotante (Graph): IGNÓRALA, nunca la toques.

        $stateBlock
    """.trimIndent()
}
