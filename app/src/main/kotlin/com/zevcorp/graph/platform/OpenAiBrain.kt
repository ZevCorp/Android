package com.zevcorp.graph.platform

import android.graphics.BitmapFactory
import android.util.Base64
import graph.core.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL

private const val OA_BASE = "https://api.openai.com"

private class OaRes(val code: Int, val body: String)

/**
 * POST a la Responses API con las mismas dos garantías que el cliente de Gemini: cancelable de verdad
 * (disconnect() al cancelar el Job) y reintento con backoff en códigos transitorios (429/5xx).
 */
private suspend fun oaHttp(url: String, headers: Map<String, String>, body: ByteArray): OaRes {
    var wait = 800L
    var attempt = 1
    while (true) {
        val res = oaHttpOnce(url, headers, body)
        if (!GeminiHttp.transient(res.code) || attempt >= 4) return res
        LogBus.log("openai", "HTTP ${res.code} transitorio · reintento $attempt/3 en ${wait}ms")
        delay(wait)
        wait = (wait * 2).coerceAtMost(8000L)
        attempt++
    }
}

private suspend fun oaHttpOnce(url: String, headers: Map<String, String>, body: ByteArray): OaRes =
    suspendCancellableCoroutine { cont ->
        val c = URL(url).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { runCatching { c.disconnect() } }
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 30_000
            c.readTimeout = 300_000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.doOutput = true
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            if (cont.isActive) cont.resumeWith(Result.success(OaRes(code, text)))
        } catch (e: Throwable) {
            if (cont.isActive) cont.resumeWith(Result.failure(e))
        }
    }

private fun ojs(s: String) = JsonPrimitive(s)
private fun ojo(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())
private fun JsonObject.ostr(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonElement?.oprim() = this as? JsonPrimitive

/**
 * OpenAI GPT-5.6 (por defecto el nivel **Terra**) con computer-use nativo sobre la **Responses API**,
 * MÁS las mismas herramientas MCP declaradas. Es el segundo proveedor intercambiable con `GeminiBrain`:
 * implementa `ThreadedBrain`, así que el motor y el composition root no cambian.
 *
 * Protocolo (developers.openai.com/api/docs/guides/tools-computer-use):
 *  - POST /v1/responses con header `Authorization: Bearer <key>` y tool {type:"computer"}.
 *  - El servidor mantiene la conversación vía `previous_response_id`; cada turno reenvía los
 *    `computer_call_output` (screenshot como data-URI) y/o `function_call_output` (resultado MCP).
 *  - Las acciones (click/type/scroll/drag/keypress/wait…) vienen en PÍXELES ABSOLUTOS del screenshot
 *    enviado; aquí se reescalan a la resolución real del dispositivo (a diferencia del 0-1000 de Gemini).
 *  - `pending_safety_checks` se acusan de vuelta en `acknowledged_safety_checks` (el usuario supervisa
 *    el Learning en vivo, igual que con Gemini).
 */
class OpenAiBrain(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val tools: List<McpTool>,
    private val listApps: () -> String,
    private val memory: () -> String = { "" },
) : ThreadedBrain {

    private val mcpNames = tools.map { it.name }.toSet()

    private class Call(
        val id: String,
        val name: String,          // "computer" para computer_call; el nombre de la función si es function_call
        val safety: JsonArray,     // pending_safety_checks a acusar (vacío si no hay)
        val isComputer: Boolean,
    )

    private var previousId = ""
    private var startId = ""
    private var continuationMessage = ""
    private var pending = listOf<Call>()
    private val internalResults = HashMap<String, JsonObject>() // resueltos localmente (list_apps)
    private var informText = ""
    private var goal = ""

    override val interactionId get() = previousId
    override val hasPendingCalls: Boolean get() = pending.isNotEmpty()
    override var totalTokens = 0
        private set

    override fun resume(id: String) { startId = id }

    override fun begin(goal: String) {
        this.goal = goal
        previousId = startId
        continuationMessage = if (startId.isNotBlank()) goal else ""
        pending = emptyList()
        internalResults.clear()
        informText = ""
    }

    override fun inform(message: String) { informText = message }

    /** Declaración de función (Responses API) a partir de una herramienta MCP, con enum en las opciones. */
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

    private fun dataUri(png: ByteArray) = "data:image/png;base64,${Base64.encodeToString(png, Base64.NO_WRAP)}"

    override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn = withContext(Dispatchers.IO) {
        val stateBlock = "Pantalla actual: ${state.screen}\nDónde estás (árbol de UI de Android):\n${state.uiContext}"

        val input = mutableListOf<JsonElement>()
        fun userMessage(text: String) {
            val content = mutableListOf<JsonElement>(ojo("type" to ojs("input_text"), "text" to ojs(text)))
            state.screenshotPng?.let {
                content += ojo("type" to ojs("input_image"), "image_url" to ojs(dataUri(it)), "detail" to ojs("original"))
            }
            input += ojo("type" to ojs("message"), "role" to ojs("user"), "content" to JsonArray(content))
        }

        if (previousId.isBlank()) {
            userMessage(goalPrompt(stateBlock))
        } else if (pending.isEmpty()) {
            userMessage(continuationMessage.ifBlank { informText.ifBlank { "Continúa." } } + "\n$stateBlock")
            continuationMessage = ""
            informText = ""
        } else {
            pending.forEachIndexed { i, call ->
                when {
                    call.isComputer -> {
                        // La respuesta a un computer_call ES el screenshot actual (más los acuses de seguridad).
                        val out = ojo(
                            "type" to ojs("computer_screenshot"),
                            "image_url" to ojs(state.screenshotPng?.let { dataUri(it) } ?: ""),
                            "detail" to ojs("original"),
                        )
                        val fields = linkedMapOf<String, JsonElement>(
                            "type" to ojs("computer_call_output"),
                            "call_id" to ojs(call.id),
                            "output" to out,
                        )
                        if (call.safety.isNotEmpty()) fields["acknowledged_safety_checks"] = call.safety
                        input += JsonObject(fields)
                    }
                    call.name == "ask_user" -> input += functionOutput(call.id, informText.ifBlank { "(sin respuesta)" })
                    internalResults.containsKey(call.id) ->
                        input += functionOutput(call.id, Json.encodeToString(JsonObject.serializer(), internalResults.getValue(call.id)))
                    call.name == "speak" -> input += functionOutput(call.id, "ok")
                    else -> input += functionOutput(call.id, actionResults.getOrElse(i) { "ok" })
                }
            }
            informText = ""
        }
        internalResults.clear()

        val toolDecls = mutableListOf<JsonElement>(ojo("type" to ojs("computer")))
        tools.forEach { toolDecls += mcpFn(it) }
        toolDecls += customFn("ask_user", "Pregunta al usuario cuando tengas una duda real e importante. Responde con texto o voz.", "question")
        toolDecls += customFn("speak", "Di algo en voz alta con tu personalidad. Solo para lo importante; no narres cada paso.", "text")

        val fields = mutableListOf(
            "model" to ojs(model()),
            "input" to JsonArray(input),
            "tools" to JsonArray(toolDecls),
            "truncation" to ojs("auto"),
        )
        if (previousId.isNotBlank()) fields += "previous_response_id" to ojs(previousId)

        val kb = (state.screenshotPng?.size ?: 0) / 1024
        val t0 = android.os.SystemClock.elapsedRealtime()
        val res = oaHttp(
            "$OA_BASE/v1/responses",
            mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${apiKey()}"),
            Json.encodeToString(JsonObject.serializer(), JsonObject(fields.toMap())).toByteArray(),
        )
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        LogBus.log("openai", "respuesta → HTTP ${res.code} · ${ms}ms · envié ${kb}KB de pantalla · recibí ${res.body.length}B")
        if (res.code >= 300) {
            // Si el hilo previo ya no existe/expiró (aún no hicimos nada este turno), abre ventana nueva y reintenta.
            if (startId.isNotBlank() && previousId == startId) {
                LogBus.log("openai", "hilo previo inválido (${res.code}); abro ventana nueva y reintento")
                previousId = ""; startId = ""; continuationMessage = ""
                return@withContext next(state, actionResults)
            }
            LogBus.log("openai", "ERROR ${res.code}: ${res.body.take(300)}")
            error("OpenAI HTTP ${res.code}: ${res.body.take(200)}")
        }
        LogBus.log("openai", "raw ← ${res.body.take(1500)}")
        runCatching { parseTurn(Json.parseToJsonElement(res.body).jsonObject, state) }
            .getOrElse { e ->
                LogBus.log("openai", "PARSE FALLÓ: ${e.message} · body=${res.body.take(1200)}")
                throw e
            }
    }

    private fun functionOutput(callId: String, output: String) = ojo(
        "type" to ojs("function_call_output"),
        "call_id" to ojs(callId),
        "output" to ojs(output),
    )

    /** Dimensiones reales del PNG enviado, para reescalar de píxeles-de-screenshot a píxeles-de-pantalla. */
    private fun shotSize(png: ByteArray?): Pair<Int, Int>? {
        png ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun parseTurn(body: JsonObject, state: ScreenState): BrainTurn {
        previousId = body.ostr("id").ifBlank { previousId }
        (body["usage"] as? JsonObject)?.let { u ->
            (u["total_tokens"] ?: u["input_tokens"]).oprim()?.intOrNull?.let { totalTokens = it }
        }
        val items = (body["output"] ?: body["outputs"])?.jsonArray.orEmpty()

        // Reescalado screenshot→pantalla: OpenAI da píxeles del screenshot enviado, no 0-1000 como Gemini.
        val shot = shotSize(state.screenshotPng)
        val sx = if (shot != null && shot.first > 0) state.width.toDouble() / shot.first else 1.0
        val sy = if (shot != null && shot.second > 0) state.height.toDouble() / shot.second else 1.0
        fun px(a: JsonObject, key: String, scale: Double) =
            a[key].oprim()?.let { (it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt()) }?.let { (it * scale).toInt() } ?: -1

        val actions = mutableListOf<AgentAction>()
        val calls = mutableListOf<Call>()
        val intents = mutableListOf<String>()
        var question: String? = null
        var speech: String? = null
        var text = ""

        for (item in items.map { it.jsonObject }) {
            when (item.ostr("type")) {
                "message" -> text += extractMessage(item)
                "output_text" -> text += item.ostr("text")
                "computer_call" -> {
                    val id = item.ostr("call_id").ifBlank { item.ostr("id") }.ifBlank { "call_${calls.size}" }
                    val safety = (item["pending_safety_checks"] as? JsonArray) ?: JsonArray(emptyList())
                    calls += Call(id, "computer", safety, isComputer = true)
                    val a = (item["action"] as? JsonObject) ?: JsonObject(emptyMap())
                    when (a.ostr("type")) {
                        "click", "double_click" -> actions += AgentAction.Tap(px(a, "x", sx), px(a, "y", sy))
                        "type" -> actions += AgentAction.Type(px(a, "x", sx), px(a, "y", sy), a.ostr("text"))
                        "keypress" -> {
                            val keys = (a["keys"] as? JsonArray)?.mapNotNull { it.oprim()?.contentOrNull } ?: emptyList()
                            actions += AgentAction.Key(mapKey(keys))
                        }
                        "scroll" -> {
                            val dy = (a["scroll_y"] ?: a["scrollY"]).oprim()?.intOrNull ?: 1
                            actions += AgentAction.Scroll(dy >= 0)
                        }
                        "drag" -> {
                            val path = (a["path"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                            val p0 = path.firstOrNull() ?: JsonObject(emptyMap())
                            val p1 = path.lastOrNull() ?: p0
                            actions += AgentAction.Swipe(px(p0, "x", sx), px(p0, "y", sy), px(p1, "x", sx), px(p1, "y", sy), 400)
                        }
                        "wait" -> actions += AgentAction.Wait(((a["ms"].oprim()?.intOrNull) ?: 1000).toLong())
                        "move", "screenshot" -> {} // move no aplica en móvil; el screenshot ya viaja en cada output
                    }
                }
                "function_call" -> {
                    val name = item.ostr("name")
                    val id = item.ostr("call_id").ifBlank { item.ostr("id") }.ifBlank { "call_${calls.size}" }
                    val safety = (item["pending_safety_checks"] as? JsonArray) ?: JsonArray(emptyList())
                    val args = runCatching { Json.parseToJsonElement(item.ostr("arguments")).jsonObject }
                        .getOrDefault((item["arguments"] as? JsonObject) ?: JsonObject(emptyMap()))
                    calls += Call(id, name, safety, isComputer = false)
                    if (name !in setOf("ask_user", "speak")) intents += args.ostr("intent")
                    when (name) {
                        in mcpNames -> actions += AgentAction.Mcp(
                            name, args.filterKeys { it != "intent" }.mapValues { it.value.oprim()?.contentOrNull ?: "" })
                        "list_apps" -> internalResults[id] = ojo("apps" to ojs(listApps()))
                        "ask_user" -> question = args.ostr("question")
                        "speak" -> speech = args.ostr("text")
                    }
                }
            }
        }
        pending = calls
        // Siempre pedimos ver la pantalla el próximo turno: computer-use de OpenAI razona sobre el screenshot.
        val needsShot = calls.any { it.isComputer } || actions.any { it is AgentAction.Tap || it is AgentAction.Type }
        when {
            calls.isNotEmpty() ->
                LogBus.log("openai", "decide: ${calls.joinToString(", ") { if (it.isComputer) "computer" else it.name }}" + (question?.let { " · pregunta" } ?: ""))
            text.isNotBlank() -> LogBus.log("openai", "responde con texto (${text.length} chars): ${text.take(160)}")
            else -> LogBus.log("openai", "final VACÍO · items: [${items.joinToString(", ") { it.jsonObject.ostr("type") }}]")
        }
        return BrainTurn(actions, question, done = calls.isEmpty(), text = text, needsScreenshot = needsShot,
            narration = intents.firstOrNull { it.isNotBlank() } ?: "", speech = speech, intents = intents)
    }

    /** Une los keys de un keypress a lo que espera UiSurface.pressKey (enter/back), o el primero en minúscula. */
    private fun mapKey(keys: List<String>): String {
        val up = keys.map { it.uppercase() }
        return when {
            "ENTER" in up || "RETURN" in up -> "enter"
            "ESC" in up || "ESCAPE" in up -> "back"
            else -> keys.firstOrNull()?.lowercase() ?: ""
        }
    }

    /** Texto de un item message: content[].output_text / text. */
    private fun extractMessage(item: JsonObject): String =
        when (val content = item["content"]) {
            is JsonArray -> content.joinToString("") { part ->
                (part as? JsonObject)?.let { it.ostr("text").ifBlank { it.ostr("output_text") } } ?: ""
            }
            is JsonPrimitive -> content.contentOrNull ?: ""
            else -> item.ostr("text")
        }

    private val workflowRule: String
        get() {
            val wfs = tools.filter { it.via.startsWith("workflow") }
            if (wfs.isEmpty()) return ""
            return """
        WORKFLOWS APRENDIDOS (tareas COMPLETAS que YA sabes hacer): ${wfs.joinToString(", ") { it.name }}.
        REGLA DE ORO: si el objetivo coincide con un workflow, tu PRIMERA Y ÚNICA acción es LLAMARLO
        (workflow_…) pasándole en "context" los datos variables. NO abras la app tú mismo: el workflow
        ya incluye abrirla y todos los pasos. Solo si reporta steps fallidos, completa tú lo que faltó.
            """.trimIndent()
        }

    private val learnedRule: String
        get() {
            val learned = tools.filter { it.via.startsWith("aprendido") }
            if (learned.isEmpty()) return ""
            return """
        HERRAMIENTAS APRENDIDAS (mapas de apps que YA conoces): ${learned.joinToString(", ") { it.name }}.
        Si la tarea es en una app con herramienta aprendida, encadena launch_app + la herramienta con la
        secuencia COMPLETA de taps desde la primera respuesta; cae a computer-use solo si reporta fallos.
            """.trimIndent()
        }

    private fun goalPrompt(stateBlock: String) = """
        Eres Ü, un asistente con PERSONALIDAD viva y divertida que controla un teléfono Android REAL.
        Objetivo del usuario: $goal

        CÓMO VES LA PANTALLA: recibes una descripción de TEXTO del árbol de UI y, cuando hace falta tocar
        algo visual, un screenshot. Ubícate con el texto (home, cajón de apps, una app, notificaciones…) y
        decide. Para tocar un elemento concreto, usa computer-use (click/type con coordenadas del screenshot).

        DOS formas de actuar, elige la más directa:
        1) HERRAMIENTAS (function-calling, sin imagen): gestos de navegación y ACCIONES DEL SISTEMA por
           Intent/API — abrir apps, alarmas, timers, llamar, SMS, correo, calendario, buscar en web, mapas,
           cámara, ajustes, portapapeles. Herramientas: ${tools.joinToString(", ") { it.name }}.
        2) COMPUTER-USE: para tocar elementos concretos DENTRO de una app (click/type sobre el screenshot).
        REGLA: para cualquier tarea del sistema (alarma, timer, llamada, abrir app, buscar…) usa SIEMPRE la
        herramienta correspondiente, NO computer-use: es directa y sin UI.
        $learnedRule
        $workflowRule

        En el campo "intent" de cada llamada a función escribe una frase corta y con chispa (ej: "Abro el
        cajón de apps 📲"). Usa speak SOLO para avisos importantes. No hables por hablar.
        CUÁNDO PREGUNTAR (ask_user): si algo depende de un dato del usuario que no puedes saber ni ver
        (¿cuál es el chat de Sebastián?, ¿cuál cuenta?, ¿a qué hora?), pregunta DE UNA. Lo que sí puedas
        resolver mirando la pantalla o con tu memoria, NO lo preguntes.

        CÓMO HABLAS: eres un compañero, no un manual. Respuestas CORTAS (1-2 frases), naturales, en el
        idioma del usuario. NUNCA enumeres tus herramientas ni uses términos técnicos.
        $memoryBlock
        Cuando el objetivo esté completo, responde SOLO con texto (sin llamar funciones).
        En las capturas puede aparecer una carita blanca flotante (Ü): IGNÓRALA, nunca la toques.

        $stateBlock
    """.trimIndent()

    private val memoryBlock: String
        get() {
            val mem = memory()
            if (mem.isBlank()) return ""
            return """
        MEMORIA DEL USUARIO (reglas y preferencias que te ha enseñado; aplícalas sin que te las repita).
        Agrupada por app: cuando vayas a usar una app, aplica al pie de la letra todo lo que aparece bajo
        ella (nombres de contactos, cuentas, preferencias). Nunca "aproximes" un dato que ya conoces.
        $mem
            """.trimIndent()
        }
}
