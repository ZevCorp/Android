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
// Acceso TOLERANTE: si el campo no existe o no es un valor simple (viene como objeto/array), devuelve
// "" en vez de reventar. El modelo a veces envuelve un argumento en un objeto y eso abortaba el turno.
private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
private fun JsonElement?.primOrNull() = this as? JsonPrimitive

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
    /** Memoria del usuario (reglas/preferencias destiladas): se inyecta en el system prompt. */
    private val memory: () -> String = { "" },
) : Brain {

    private val mcpNames = tools.map { it.name }.toSet()

    private class Call(val id: String, val name: String, val safety: Boolean)

    private var previousId = ""
    private var startId = ""              // punto de reanudación del hilo de conversación compartido
    private var continuationMessage = ""  // mensaje del usuario a enviar como turno de continuación
    private var pending = listOf<Call>()
    private val internalResults = HashMap<String, JsonObject>() // resueltos localmente (list_apps)
    private var informText = ""
    private var goal = ""

    /** Id de la última interacción (el hilo de conversación server-side para continuar después). */
    val interactionId get() = previousId
    /** Tamaño del contexto del hilo (tokens de la última interacción); gobierna la rotación de ventana. */
    var totalTokens = 0
        private set

    /** Reanuda el hilo de conversación existente (previous_interaction_id) antes de begin(). */
    fun resume(id: String) { startId = id }

    override fun begin(goal: String) {
        this.goal = goal
        // Si hay hilo previo, se CONTINÚA (el servidor ya tiene system prompt + historial): el objetivo
        // nuevo viaja como un turno de usuario más. Si no, arranca fresco con el goalPrompt completo.
        previousId = startId
        continuationMessage = if (startId.isNotBlank()) goal else ""
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
            // Primer turno de un objetivo que continúa el hilo: se envía el mensaje del usuario nuevo
            // (continuationMessage); si no, una respuesta a una duda (informText) o "Continúa.".
            val msg = continuationMessage.ifBlank { informText.ifBlank { "Continúa." } }
            input += textItem(msg + "\n$stateBlock")
            state.screenshotPng?.let { input += image(it) }
            continuationMessage = ""
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
                        // safety_acknowledgement va EMBEBIDO en el JSON del resultado (nunca como campo
                        // de primer nivel del function_result: eso da HTTP 400), para acciones nativas
                        // y para funciones custom (MCP) que traigan safety_decision. Doc oficial:
                        // action_result["safety_acknowledgement"] = true, y ese dict se json.dumps al "text".
                        val resObj = linkedMapOf(
                            "screen" to js(state.screen), "ui" to js(state.uiContext),
                            "result" to js(actionResults.getOrElse(i) { "ok" }))
                        if (call.safety) resObj["safety_acknowledgement"] = JsonPrimitive(true)
                        result += jsonItem(JsonObject(resObj))
                        // imagen solo si el modelo la pidió (tras un tap/type de computer-use)
                        if (i == pending.lastIndex) state.screenshotPng?.let { result += image(it) }
                    }
                }
                // El function_result NO lleva campos fuera de este esquema (type/name/call_id/result):
                // cualquier extra (p.ej. safety_acknowledgement) es rechazado con 400 por la API.
                input += JsonObject(mapOf(
                    "type" to js("function_result"), "name" to js(call.name),
                    "call_id" to js(call.id), "result" to JsonArray(result)))
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
            // Auto-recuperación: si el hilo previo ya no existe/expiró (aún no hicimos nada este turno),
            // se reinicia la ventana y se reintenta FRESCO una vez. Evita quedar atascado tras un
            // reinicio del servidor o un lapso largo. Solo posible en el primer turno de un hilo reanudado.
            if (startId.isNotBlank() && previousId == startId) {
                LogBus.log("gemini", "hilo previo inválido (${res.code}); abro ventana nueva y reintento")
                previousId = ""; startId = ""; continuationMessage = ""
                return@withContext next(state, actionResults)
            }
            LogBus.log("gemini", "ERROR ${res.code}: ${res.body.take(300)}")
            error("Gemini HTTP ${res.code}: ${res.body.take(200)}")
        }
        // Diagnóstico: cuerpo crudo de la respuesta (truncado). Útil para ver qué decidió el modelo.
        LogBus.log("gemini", "raw ← ${res.body.take(1500)}")
        runCatching { parseTurn(Json.parseToJsonElement(res.body).jsonObject, state) }
            .getOrElse { e ->
                LogBus.log("gemini", "PARSE FALLÓ: ${e.message} · body=${res.body.take(1200)}")
                throw e
            }
    }

    private fun parseTurn(body: JsonObject, state: ScreenState): BrainTurn {
        previousId = body.str("id").ifBlank { previousId }
        // Tamaño del contexto del hilo (incluye el historial cacheado): gobierna la rotación de ventana.
        (body["usage"] as? JsonObject)?.get("total_tokens").primOrNull()?.intOrNull?.let { totalTokens = it }
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
                // El API a veces devuelve el texto final envuelto en model_output/message:
                // sin esto el turno parece "vacío" y el motor cae a un fallback genérico.
                "model_output", "message", "output_text" -> text += extractText(item)
                "function_call" -> {
                    val name = item.str("name")
                    val id = item.str("call_id").ifBlank { item.str("id") }.ifBlank { "call_${calls.size}" }
                    val args = (item["arguments"] as? JsonObject) ?: (item["args"] as? JsonObject) ?: JsonObject(emptyMap())
                    // La API EXIGE acuse siempre que una llamada traiga safety_decision, sea nativa de
                    // computer_use o una función custom (MCP aprendida como whatsapp_chat). El acuse va
                    // EMBEBIDO en el JSON del resultado (ver next()), no como campo de primer nivel del
                    // function_result — tal cual la documentación oficial (action_result["safety_acknowledgement"]=true).
                    calls += Call(id, name, args["safety_decision"] != null)
                    if (name !in setOf("ask_user", "speak")) intents += args.str("intent")

                    fun px(key: String, size: Int) =
                        args[key].primOrNull()?.intOrNull?.let { it * size / 1000 } ?: -1
                    when {
                        name in mcpNames -> actions += AgentAction.Mcp(
                            name, args.filterKeys { it != "intent" && it != "safety_decision" }
                                .mapValues { it.value.primOrNull()?.contentOrNull ?: "" })
                        name == "click" -> actions += AgentAction.Tap(px("x", state.width), px("y", state.height))
                        name == "type" -> {
                            actions += AgentAction.Type(px("x", state.width), px("y", state.height), args.str("text"))
                            if (args["press_enter"].primOrNull()?.booleanOrNull == true) actions += AgentAction.Key("enter")
                        }
                        name == "open_app" -> actions += AgentAction.OpenApp(args.str("app_name").ifBlank { args.str("name") })
                        name == "navigate" -> actions += AgentAction.OpenApp(args.str("url"))
                        name == "drag_and_drop" -> actions += AgentAction.Swipe(
                            px("start_x", state.width), px("start_y", state.height),
                            px("end_x", state.width), px("end_y", state.height), 400)
                        name == "long_press" -> {
                            val x = px("x", state.width); val y = px("y", state.height)
                            actions += AgentAction.Swipe(x, y, x, y, (args["seconds"].primOrNull()?.intOrNull ?: 1) * 1000L)
                        }
                        name == "scroll" -> actions += AgentAction.Scroll(args.str("direction") != "up")
                        name == "press_key" -> actions += AgentAction.Key(args.str("key"))
                        name == "go_back" -> actions += AgentAction.Key("back")
                        name == "wait" -> actions += AgentAction.Wait((args["seconds"].primOrNull()?.intOrNull ?: 2) * 1000L)
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

    /** Extrae el texto de un item envuelto (model_output/message): campo text o content anidado. */
    private fun extractText(item: JsonObject): String {
        item.str("text").takeIf { it.isNotBlank() }?.let { return it }
        return when (val content = item["content"] ?: item["output"]) {
            is JsonPrimitive -> content.contentOrNull ?: ""
            is JsonArray -> content.joinToString("") { part -> (part as? JsonObject)?.str("text") ?: "" }
            is JsonObject -> content.str("text")
            else -> ""
        }
    }

    /** Regla de workflows: tareas que YA sabe hacer paso a paso; se llaman enteras, no se re-improvisan. */
    private val workflowRule: String
        get() {
            val wfs = tools.filter { it.via.startsWith("workflow") }
            if (wfs.isEmpty()) return ""
            return """
        WORKFLOWS APRENDIDOS (tareas COMPLETAS que ya sabes hacer paso a paso porque las viste en la
        enseñanza): ${wfs.joinToString(", ") { it.name }}.
        Si el objetivo del usuario coincide con un workflow, llámalo DIRECTO desde la primera respuesta
        y pásale en "context" los datos variables de esta ejecución (nombres, textos, cantidades). El
        motor ejecuta sus steps solo, alternando entre subconsciente (clic por árbol de UI) y
        consciente (mirar la pantalla): NO repitas sus pasos tú mismo ni lo mezcles con computer-use.
        Solo si el workflow reporta steps fallidos, completa TÚ lo que faltó.
            """.trimIndent()
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
        $workflowRule

        En el campo "intent" de cada acción escribe una frase corta y con chispa (ej: "Abro el cajón de apps 📲").
        Usa speak SOLO para avisos importantes. No hables por hablar.
        CUÁNDO PREGUNTAR (ask_user): si algo se te complica así sea MÍNIMAMENTE o depende de un dato
        del usuario que tú no puedes saber ni ver (¿cuál es el chat de Sebastián?, ¿cuál cuenta?,
        ¿a qué hora?), pregunta DE UNA con ask_user: una pregunta corta vale más que una acción
        equivocada. Lo que sí puedas resolver mirando la pantalla o con tu memoria, NO lo preguntes.

        CÓMO HABLAS (importantísimo): eres un compañero, no un manual. Respuestas CORTAS (1-2 frases),
        naturales y en el idioma del usuario. NUNCA enumeres tus herramientas ni uses términos técnicos
        (MCP, computer-use, function calls, árbol de UI, Intents…): al usuario no le interesan. Si te
        explican o enseñan algo, responde breve y humano ("¡Listo, lo tengo! 🙌"). Si preguntan qué
        sabes hacer, dilo en lenguaje cotidiano y en UNA frase (p.ej. "abro apps, pongo música, mando
        mensajes, te ayudo con lo que necesites en el teléfono").
        $memoryBlock
        Cuando el objetivo esté completo, responde SOLO con texto (sin llamar funciones).
        En las capturas puede aparecer una carita blanca flotante (Graph): IGNÓRALA, nunca la toques.

        $stateBlock
    """.trimIndent()

    private val memoryBlock: String
        get() {
            val mem = memory()
            if (mem.isBlank()) return ""
            return """
        MEMORIA DEL USUARIO (reglas y preferencias que te ha enseñado; aplícalas cuando la tarea lo
        amerite, sin que te las repita). Está agrupada por app: cuando vayas a USAR una app (abrirla u
        operarla, p.ej. WhatsApp), aplica AL PIE DE LA LETRA todo lo que aparece bajo esa app — es su
        contexto completo (nombres de contactos, cuentas, preferencias). Nunca ignores ni "aproximes"
        un dato que ya conoces de la app que vas a usar.
        $mem
            """.trimIndent()
        }
}
