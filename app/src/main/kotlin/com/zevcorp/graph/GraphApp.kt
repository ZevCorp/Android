package com.zevcorp.graph

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import com.zevcorp.graph.platform.ActiveLearning
import com.zevcorp.graph.platform.AndroidSystemApi
import com.zevcorp.graph.platform.Anticipation
import com.zevcorp.graph.platform.CloudSync
import com.zevcorp.graph.platform.GeminiBrain
import com.zevcorp.graph.platform.GeminiVideo
import com.zevcorp.graph.platform.LearningInquiry
import com.zevcorp.graph.platform.GeminiClickDoctor
import com.zevcorp.graph.platform.MemoryDistiller
import com.zevcorp.graph.platform.OpenAiBrain
import com.zevcorp.graph.platform.UiBugBus
import com.zevcorp.graph.platform.MemoryStore
import com.zevcorp.graph.platform.RemoteConfig
import com.zevcorp.graph.platform.SupabaseAuth
import com.zevcorp.graph.platform.Updater
import com.zevcorp.graph.platform.UsageU
import com.zevcorp.graph.ui.Palette
import com.zevcorp.graph.ui.ThemeMode
import com.zevcorp.graph.voice.IntentDistiller
import com.zevcorp.graph.voice.VoiceMediaSession
import com.zevcorp.graph.platform.GeminiLearning
import com.zevcorp.graph.platform.GeminiWorkflow
import com.zevcorp.graph.platform.GraphAccessibilityService
import com.zevcorp.graph.platform.KnowledgeGraph
import com.zevcorp.graph.platform.LearnedToolRepo
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.platform.WorkflowRepo
import graph.core.application.ExecutionEngine
import graph.core.application.PassiveLearning
import graph.core.application.WorkflowRecorder
import graph.core.application.WorkflowRunner
import graph.core.domain.ExecutionMode
import graph.core.domain.Mcp
import graph.core.domain.Phone
import graph.core.domain.ThreadedBrain
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import graph.core.domain.Workflow
import graph.core.domain.WorkflowStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Una propuesta ("¿quieres que lo haga yo?") o pregunta que el asistente dijo por VOZ y quedó
 * esperando respuesta. Vive como contexto pendiente del hilo unificado: el usuario contesta por
 * CUALQUIER vía de input y run() le inyecta este contexto para que "sí, hazlo" signifique
 * exactamente lo propuesto.
 */
data class PendingVoice(val kind: String, val app: String, val question: String, val task: String, val at: Long)

/** Proveedor del cerebro de computer-use, conmutable desde el panel de Desarrollador. */
enum class Provider { GEMINI, OPENAI }

/** Composition root: une el núcleo (motor mixto + MCP) con los adaptadores Android. */
class GraphApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: SharedPreferences

    /** La superficie del teléfono, viva mientras el servicio de accesibilidad esté activo. */
    @Volatile var ui: Phone? = null

    /**
     * Volumen PROPIO del asistente en el panel de volumen del sistema (independiente del resto de apps):
     * publica una MediaSession con volumen remoto mientras la voz suena. Singleton compartido por la voz
     * de OpenAI y el TTS del sistema. Detener desde el chip de medios silencia la voz en curso.
     */
    val voiceSession: VoiceMediaSession by lazy {
        VoiceMediaSession(this).also { it.onStop = { bubble?.hush() } }
    }

    // Sanitiza la key: un salto de línea pegado por error en el campo tumbaba TODAS las llamadas con
    // "Unexpected char 0x0a in header value" (una key nunca lleva espacios/saltos de línea válidos).
    private val apiKey = {
        resolvedKey("apiKey", "remoteApiKey", DEFAULT_API_KEY)
            .filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()
    }
    private val model = { prefs.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash" }

    /**
     * PROVEEDOR DE COMPUTER-USE (Gemini vs OpenAI), conmutable con un clic desde el panel de
     * Desarrollador. Solo afecta al CEREBRO de ejecución; el análisis de video y los distiladores
     * auxiliares siguen en Gemini (OpenAI no ingiere video). La key y el modelo de OpenAI son propios.
     */
    private val provider = {
        runCatching { Provider.valueOf(prefs.getString("provider", Provider.GEMINI.name)!!) }
            .getOrDefault(Provider.GEMINI)
    }
    private val openAiKey = {
        resolvedKey("openaiKey", "remoteOpenaiKey", DEFAULT_OPENAI_KEY)
            .filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()
    }
    private val openAiModel = { prefs.getString("openaiModel", "gpt-5.6-terra") ?: "gpt-5.6-terra" }
    // Esfuerzo de razonamiento del cerebro OpenAI: "low" acelera cada turno (recomendado para
    // computer-use). Tunable desde prefs: minimal (más rápido) … xhigh (más lento y minucioso).
    private val openAiEffort = { prefs.getString("openaiEffort", "low") ?: "low" }

    /**
     * Resuelve una key en 3 niveles: lo que el usuario puso a mano en el panel de Desarrollador
     * (SIEMPRE gana si no está vacío) > lo último que trajo [RemoteConfig] del backend > el default
     * horneado en build (`apikey.properties`/env var, puede venir vacío si el build no las tenía).
     */
    fun resolvedKey(userPrefKey: String, remotePrefKey: String, buildDefault: String): String =
        prefs.getString(userPrefKey, "")?.ifBlank { null }
            ?: prefs.getString(remotePrefKey, "")?.ifBlank { null }
            ?: buildDefault

    private val bubble get() = (ui as? GraphAccessibilityService)?.bubble

    private val voice = object : Voice {
        override fun narrate(text: String) { bubble?.narrate(text) }
        override fun speak(text: String) { bubble?.speak(text) }
    }

    /** Herramientas aprendidas (se vuelven MCP ejecutables): disco local + nube + grafo Neo4j. */
    val learnedTools by lazy {
        LearnedToolRepo(filesDir) { tool ->
            scope.launch(Dispatchers.IO) { CloudSync.push(tool); KnowledgeGraph.pushTool(tool) }
        }
    }

    /** Workflows aprendidos (el puente consciente ↔ subconsciente): disco local + nube + grafo Neo4j. */
    val workflows by lazy {
        WorkflowRepo(filesDir) { wf ->
            scope.launch(Dispatchers.IO) { CloudSync.pushWorkflow(wf); KnowledgeGraph.pushWorkflow(wf) }
        }
    }
    private val workflowBrain by lazy { GeminiWorkflow(apiKey, model) }

    /**
     * GRABADORA DE WORKFLOWS: ambos modos de enseñanza (pasiva y activa) van guardando el paso a paso
     * de la tarea (la unidad es el clic en el árbol de UI). Al cerrarse la enseñanza —salir de la app
     * en la pasiva, terminar la grabación en la activa— la traza pasa al LLM de post-procesamiento,
     * que limpia, organiza, anota y asigna la vía (MCP subconsciente o consciente) de cada step.
     */
    val recorder by lazy {
        WorkflowRecorder(LogBus) { source, steps ->
            scope.launch(Dispatchers.IO) {
                // El catálogo MCP se lee AQUÍ (fresco): los MCPs de pasadas anteriores —o el que se
                // acaba de consolidar en esta misma pasada— también asignan steps subconscientes.
                val wf = runCatching { workflowBrain.structure(source, steps, workflows.list(), learnedTools.list()) }
                    .getOrElse { LogBus.log("workflow", "post-procesamiento falló: ${it.message}"); null }
                if (wf == null) {
                    LogBus.log("workflow", "la traza no dio un workflow que valga la pena guardar")
                    return@launch
                }
                workflows.save(wf)
                val sub = wf.steps.count { it.subconscious }
                LogBus.log("workflow", "🧭 workflow \"${wf.name}\": ${wf.steps.size} steps ($sub subconscientes, ${wf.steps.size - sub} conscientes)")
                voice.narrate("🧭 Aprendí el flujo \"${wf.name}\" (${wf.steps.size} pasos).")
            }
        }
    }

    /**
     * Enseñanza PASIVA: se activa/desactiva desde la APP PRINCIPAL (antes en la burbuja). Observa los
     * clics del usuario usando el teléfono con normalidad y consolida el MCP de cada app cuando el
     * usuario sale de ella. En el modo iniciado por el usuario, el asistente puede interrumpir y
     * preguntarle cosas por voz.
     */
    val passive by lazy {
        PassiveLearning(GeminiLearning(apiKey, model), learnedTools, voice, LogBus,
            inquirer = LearningInquiry(apiKey, model, memories, scope,
                busy = { executing || bubble?.voiceBusy == true || activeLearning.busy },
                speak = { bubble?.speak(it) },
                pending = ::notePendingVoice),
            recorder = recorder,
            appName = ::appLabel,
            onLearned = { tool -> scope.launch(Dispatchers.IO) { reconcileWorkflows(tool.app) } })
    }

    /* ---------- Propuestas/preguntas por voz: contexto pendiente del hilo unificado ---------- */

    @Volatile private var pendingVoice: PendingVoice? = null

    /** El asistente acaba de proponer o preguntar algo por voz: queda esperando la respuesta. */
    fun notePendingVoice(kind: String, app: String, question: String, task: String) {
        pendingVoice = PendingVoice(kind, app, question, task, System.currentTimeMillis())
    }

    /** Toma (y limpia) lo pendiente si sigue fresco: la respuesta natural llega en minutos, no horas. */
    private fun consumePendingVoice(): PendingVoice? {
        val p = pendingVoice ?: return null
        pendingVoice = null
        return p.takeIf { System.currentTimeMillis() - it.at < 3 * 60_000L }
    }

    /**
     * Aprendizaje continuo, camino inverso: acaba de nacer o refinarse el MCP de una app y pueden
     * existir workflows previos con pasos conscientes que ese mapa ya cubre. Se reconectan aquí,
     * en background, workflow por workflow (solo los de esa app que aún tengan pasos conscientes).
     */
    private suspend fun reconcileWorkflows(app: String) {
        if (app.isBlank()) return
        val affected = workflows.list().filter { wf ->
            wf.steps.any { it.app == app } && wf.steps.any { !it.subconscious }
        }
        if (affected.isEmpty()) return
        val catalog = learnedTools.list()
        for (wf in affected) {
            val better = runCatching { workflowBrain.reconcile(wf, catalog) }
                .getOrElse { LogBus.log("workflow", "reconexión de \"${wf.name}\" falló: ${it.message}"); null }
                ?: continue
            workflows.save(better)
            val sub = better.steps.count { it.subconscious }
            LogBus.log("workflow", "🔗 \"${better.name}\" reconectado al MCP de $app: " +
                "$sub/${better.steps.size} steps ya subconscientes")
        }
    }

    /** Nombre visible de una app desde su paquete ("com.whatsapp" → "WhatsApp"); cae al paquete corto. */
    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    /**
     * Enseñanza ACTIVA: se activa TOCANDO el 🎓 de la burbuja. Comparte pantalla (video + audio), y al
     * terminar procesa TODO el video con Gemini para estructurar conocimiento textual por app en la
     * capa MCP (memoria durable). Fase 1: sin árbol de UI, solo texto de cómo usar las apps.
     */
    val activeLearning by lazy {
        ActiveLearning(this, GeminiVideo(apiKey, model), memories, voice,
            // Pregunta de seguimiento: se dice por voz y se responde en el popup de la burbuja
            // (texto o su micrófono) — el micrófono sticky flotante ya no existe.
            askAloud = { q -> bubble?.let { b -> b.speak(q); b.ask(q) } ?: "" },
            apiKey = apiKey, model = model)
    }

    /**
     * La cuenta del usuario: separa el conocimiento PERSONAL (memoria durable, solo suyo) del mapa
     * de UI de las apps (compartido entre todos). Sin sesión, la memoria vive solo en el teléfono.
     */
    val auth by lazy { SupabaseAuth(prefs) }

    /** Memoria durable: reglas/preferencias destiladas de cualquier input (local + nube POR CUENTA). */
    val memories by lazy {
        MemoryStore(filesDir, owner = { auth.userId }) { note ->
            scope.launch(Dispatchers.IO) { CloudSync.pushMemory(note) }
        }
    }
    private val memoryDistiller by lazy { MemoryDistiller(apiKey, model) }

    /** El doctor de clics: diagnostica con el LLM los fallos de ID ambiguo detectados en tiempo real. */
    private val clickDoctor by lazy { GeminiClickDoctor(apiKey, model) }

    /**
     * La capa de diagnóstico detectó que replicar un clic por su etiqueta caería en otro elemento
     * (IDs duplicados). Se registra en el bus (panel "Bugs de UI") y, en background, el LLM halla el
     * ID único correcto y resume cómo endurecer la detección nativa. Fire-and-forget: nunca bloquea.
     */
    fun diagnoseClickBug(app: String, screen: String, label: String, touched: String, resolved: String, snapshot: String) {
        val bug = UiBugBus.report(app, screen, label, touched, resolved) ?: return // ya diagnosticado
        scope.launch(Dispatchers.IO) {
            val dx = runCatching { clickDoctor.diagnose(app, screen, label, snapshot) }.getOrNull()
            if (dx != null) {
                UiBugBus.attachDiagnosis(bug, dx.panelLine())
                LogBus.log("bug-ui", "🩺 \"$label\" → ${dx.panelLine()}")
            } else {
                UiBugBus.attachDiagnosis(bug, "no se pudo diagnosticar (reintenta al reaparecer)")
            }
        }
    }

    /** El "primer LLM" del pipeline de voz (repo Graph): destila la intención del transcript. */
    val intentDistiller by lazy { IntentDistiller(apiKey, model) }

    /** El cerebro del modo reunión (escucha por esquinas): nota · construye · interviene al cierre. */
    val meetingBrain by lazy { com.zevcorp.graph.voice.MeetingBrain(apiKey, model) }

    /** Pausa entre steps MCP enviados juntos, ajustable con la barra de velocidad de la app. */
    val stepDelay = { prefs.getInt("stepDelayMs", 350).toLong() }

    /* Parpadeos de la carita al CAMBIAR de vía: 1 = consciente (computer-use con screenshots),
       2 = subconsciente (MCP). Si repite la misma vía, no parpadea. */
    @Volatile private var lastSubconscious: Boolean? = null
    private val modeSignal = ExecutionMode { subconscious ->
        // La statusbar de ejecución (negra, arriba) muestra la vía en vivo en cada acción.
        bubble?.execStatus(subconscious)
        if (lastSubconscious != subconscious) {
            lastSubconscious = subconscious
            bubble?.blink(if (subconscious) 2 else 1)
        }
        // Las MCP por Intent no vuelan la burbuja (no hay coordenadas): pulso para que se vea viva.
        if (subconscious) bubble?.pulse()
    }

    /**
     * INTERRUPTOR TEMPORAL — ejecución SUBCONSCIENTE desconectada.
     *
     * Con `false`, la EJECUCIÓN no expone al modelo las herramientas aprendidas del árbol de UI ni los
     * workflows encadenados: el asistente resuelve todo por la vía CONSCIENTE (computer-use) apoyado en
     * el MCP base (gestos de accesibilidad + acciones de sistema, incluido `web_search`). Una sola vía
     * = ejecución más simple y directa.
     *
     * El APRENDIZAJE sigue 100 % vivo: la enseñanza pasiva y activa graban workflows, consolidan los
     * mapas MCP de cada app y todo se persiste y sincroniza (disco + nube + Neo4j). Lo único apagado es
     * el REPLAY de ese conocimiento. Para reactivar la vía subconsciente, poner en `true`.
     */
    private val subconsciousExecution = false

    private fun newBrain(mcp: Mcp): ThreadedBrain {
        val listApps = {
            packageManager.getInstalledApplications(0)
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .joinToString(", ") { packageManager.getApplicationLabel(it).toString() }
        }
        val mem = { memories.promptBlock() }
        return when (provider()) {
            Provider.OPENAI -> OpenAiBrain(openAiKey, openAiModel, mcp.tools, listApps, mem, openAiEffort)
            Provider.GEMINI -> GeminiBrain(apiKey, model, mcp.tools, listApps, memory = mem)
        }
    }

    private val anticipation by lazy { Anticipation(apiKey, model) }

    /** Prompts que componen el objetivo vivo: crecen si llega un audio nuevo durante la ejecución. */
    private val goalPrompts = mutableListOf<String>()

    /**
     * HILO DE CONVERSACIÓN PERSISTENTE (previous_interaction_id). TODAS las activaciones —micrófono
     * flotante, texto, esquinas y botón de encendido— comparten el MISMO contexto para dar
     * continuidad ("¿qué acabas de hacer?", responder a lo anterior…). Las ejecuciones también viven
     * aquí. Se rota a una ventana nueva solo al acercarse al límite (la memoria durable sobrevive).
     */
    @Volatile var conversationId = ""
        private set
    @Volatile private var conversationTokens = 0
    private val maxContextTokens = 400_000

    /**
     * Crea un motor y su cerebro. Con `resume`, el cerebro CONTINÚA el hilo compartido (no reenvía el
     * system prompt: el servidor ya lo tiene) para que haya continuidad entre activaciones; si no,
     * arranca un hilo fresco. Devuelve ambos para poder guardar el id/tokens del hilo al terminar.
     */
    private fun newSession(surface: Phone, service: GraphAccessibilityService, user: UserChannel?, resume: Boolean, maxTurns: Int = 40): Pair<ExecutionEngine, ThreadedBrain> {
        // El runner de workflows: los steps subconscientes salen por MCP (clic por árbol de UI) y los
        // conscientes por un motor acotado a ese step; el switch de vía se señala igual que siempre.
        // Solo se cablea si la vía subconsciente está activa.
        val runner = if (subconsciousExecution) WorkflowRunner(
            player = service,
            elements = { service.elements() }, // árbol de UI vivo: para encadenar y saltar pasos ya cumplidos
            conscious = { wf, step, context -> consciousStep(surface, service, wf, step, context) },
            mode = modeSignal, stepDelay = stepDelay, log = LogBus,
        ) else null
        // Subconsciente OFF: el Mcp no expone herramientas aprendidas ni workflows; solo el MCP base
        // (gestos + sistema). El aprendizaje los sigue grabando y consolidando, pero no se ejecutan.
        val mcp = if (subconsciousExecution)
            Mcp(service, AndroidSystemApi(service), learnedTools.list(), service, stepDelay, LogBus,
                workflows = workflows.list(), workflowExecutor = runner)
        else
            Mcp(service, AndroidSystemApi(service), emptyList(), service, stepDelay, LogBus)
        val brain = newBrain(mcp)
        if (resume) brain.resume(conversationId)
        val engine = ExecutionEngine(
            brain = { brain }, phone = surface, mcp = mcp, user = user,
            voice = voice, log = LogBus, mode = modeSignal, stepDelay = stepDelay, maxTurns = maxTurns,
        )
        return engine to brain
    }

    /**
     * Un step CONSCIENTE de un workflow: un motor acotado cuyo único objetivo es ese paso. La pantalla
     * ya viene posicionada por los steps anteriores; el motor mira, hace el paso y devuelve el control
     * al runner (que sigue con el siguiente step, subconsciente o consciente).
     */
    private suspend fun consciousStep(surface: Phone, service: GraphAccessibilityService, workflow: Workflow, step: WorkflowStep, context: String): Boolean {
        // Sin workflows en este Mcp: un step no puede relanzar workflows (evita la recursión).
        val mcp = Mcp(service, AndroidSystemApi(service), learnedTools.list(), service, stepDelay, LogBus)
        val brain = newBrain(mcp)
        val engine = ExecutionEngine(
            brain = { brain }, phone = surface, mcp = mcp, user = null,
            voice = voice, log = LogBus, mode = modeSignal, stepDelay = stepDelay, maxTurns = 8,
        )
        val goal = buildString {
            append("Estás EN MEDIO del workflow \"${workflow.name}\" (${workflow.description}). ")
            append("La pantalla ya está donde la dejaron los pasos anteriores: NO reinicies la tarea ni vayas al home. ")
            append("Ejecuta SOLO este paso y termina: ${step.action}.")
            if (step.note.isNotBlank()) append(" Contexto del paso: ${step.note}.")
            if (context.isNotBlank()) append(" Datos de esta ejecución: $context.")
        }
        return try { engine.run(goal, announce = false); true }
        catch (ce: CancellationException) { throw ce }
        catch (t: Throwable) { LogBus.log("workflow", "step consciente falló: ${t.message}"); false }
    }

    private fun buildGoal(prompts: List<String>): String =
        if (prompts.size == 1) prompts[0]
        else "El usuario te pidió esto, en orden, mientras ejecutabas:\n" +
            prompts.mapIndexed { i, p -> "${i + 1}) \"$p\"" }.joinToString("\n") +
            "\nReinterpreta TODO como UN SOLO objetivo y complétalo. Las instrucciones posteriores " +
            "pueden anular, modificar o ampliar las anteriores; usa tu criterio para decidir qué hacer con todo."

    /**
     * Pídele algo por texto o voz. Gemini 3.5 Flash lo ejecuta con el motor mixto. Si mientras
     * ejecuta llega otro audio (augmentExecution), se cancela y REINTERPRETA ambos prompts juntos.
     * Al terminar, una cadena de pensamiento breve decide si anticipar una acción segura.
     */
    suspend fun run(prompt: String, user: UserChannel?): String {
        val surface = ui ?: return "Activa el servicio de accesibilidad de Ü"
        val service = surface as? GraphAccessibilityService ?: return "Servicio de accesibilidad inactivo"
        // Rotación de ventana de contexto: al superar el umbral se abre un hilo nuevo (la memoria
        // durable sobrevive). Solo aplica al empezar; no interrumpe nada en curso.
        if (conversationTokens >= maxContextTokens) {
            LogBus.log("run", "🧠 ventana de contexto nueva (el hilo llegó a $conversationTokens tokens)")
            conversationId = ""; conversationTokens = 0
        }
        // En paralelo (nunca bloquea la ejecución): si el input enseña algo durable, se recuerda.
        scope.launch(Dispatchers.IO) {
            memoryDistiller.capture(prompt)?.let { note ->
                if (memories.add(note)) {
                    LogBus.log("memory", "🧠 recordado${if (note.app.isNotBlank()) " [${note.app}]" else ""}: ${note.note}")
                    voice.narrate("Lo recordaré")
                }
            }
        }
        // Hilo unificado: si el asistente acaba de proponer/preguntar algo por VOZ, este prompt
        // puede ser la respuesta (venga del panel, las esquinas o donde sea). Se inyecta como
        // contexto para que "sí, hazlo" signifique EXACTAMENTE lo propuesto.
        val pending = consumePendingVoice()
        if (pending != null) LogBus.log("run", "🔗 contexto pendiente (${pending.kind}): \"${pending.question.take(80)}\"")
        if (pending?.kind == "ask") scope.launch(Dispatchers.IO) {
            memoryDistiller.captureAnswer(pending.app, pending.question, prompt)?.let { note ->
                if (memories.add(note)) LogBus.log("memory", "🧠 aprendido de tu respuesta [${note.app}]: ${note.note}")
            }
        }
        val pendingContext = pending?.let {
            if (it.kind == "offer")
                "CONTEXTO INMEDIATO: hace un momento le PROPUSISTE por voz al usuario: «${it.question}» " +
                    "(la tarea que harías, en la app ${it.app}: «${it.task}»). Si su mensaje ACEPTA la " +
                    "propuesta («sí», «hazlo», «dale»…), tu objetivo es EXACTAMENTE esa tarea, con todos " +
                    "sus detalles. Si pide otra cosa, obedece lo nuevo e ignora la propuesta."
            else
                "CONTEXTO INMEDIATO: hace un momento le PREGUNTASTE por voz al usuario: «${it.question}» " +
                    "(app ${it.app}). Si su mensaje es la RESPUESTA a esa pregunta, no ejecutes nada: " +
                    "agradécele brevemente con speak y termina (su respuesta ya quedó guardada en tu " +
                    "memoria). Si es una orden nueva, ejecútala."
        }
        synchronized(goalPrompts) { goalPrompts.clear(); goalPrompts.add(prompt.trim()) }
        return running {
            var summary = ""
            var round = 0
            // Bucle de reencaminado: cada audio nuevo cancela el motor y se reinterpreta todo junto.
            while (true) {
                val (goalBase, builtCount) = synchronized(goalPrompts) { buildGoal(goalPrompts.toList()) to goalPrompts.size }
                val goal = if (pendingContext != null) "$goalBase\n\n$pendingContext" else goalBase
                bubble?.showExecutionMic(true)
                val (engine, brain) = newSession(surface, service, user, resume = true)
                val holder = arrayOf("")
                val announce = round == 0 // en reencaminados no narra el objetivo largo
                val child = CoroutineScope(kotlin.coroutines.coroutineContext).launch {
                    holder[0] = try { engine.run(goal, announce) }
                        catch (ce: CancellationException) { throw ce }
                        catch (t: Throwable) { LogBus.log("run", "motor: ${t.message}"); "Tuve un problema con eso." }
                }
                engineCanceller = { child.cancel(CancellationException("reencaminar")) }
                child.join()
                engineCanceller = null
                // Persiste el hilo compartido SOLO si terminó limpio. Si quedó con function_calls sin
                // responder (error/500/Stop/maxTurns/cancelación a mitad), el hilo está ENVENENADO:
                // reanudarlo haría fallar cualquier tarea futura con 400 "Each Function Response must
                // be matched to a Function Call by name". En ese caso, la próxima activación arranca
                // en una ventana nueva (la memoria durable sobrevive; solo se pierde el hilo server-side).
                if (brain.hasPendingCalls) {
                    LogBus.log("run", "🧵 hilo con llamadas sin responder; la próxima activación arranca fresca")
                    conversationId = ""
                    conversationTokens = 0
                } else {
                    conversationId = brain.interactionId
                    conversationTokens = brain.totalTokens
                }
                val grew = synchronized(goalPrompts) { goalPrompts.size > builtCount }
                if (!grew) { summary = holder[0]; break }
                round++
                LogBus.log("run", "↻ reencaminando: ahora son ${synchronized(goalPrompts) { goalPrompts.size }} prompts")
                voice.narrate("Ok, lo ajusto sobre la marcha")
            }
            bubble?.showExecutionMic(false)
            // El amigo prevenido: ¿alguna acción de certeza total que convenga hacer justo ahora?
            anticipate(surface, service, user, summary)
            summary
        }
    }

    /** Cadena de pensamiento breve al terminar → acción autónoma segura y/o aviso hablado. */
    private suspend fun anticipate(surface: Phone, service: GraphAccessibilityService, user: UserChannel?, summary: String) {
        val request = synchronized(goalPrompts) { goalPrompts.joinToString(" · ") }
        // Coherente con la vía activa: sin subconsciente, la anticipación solo ve el MCP base.
        val availableLearned = if (subconsciousExecution) learnedTools.list() else emptyList()
        val tools = Mcp(service, AndroidSystemApi(service), availableLearned).tools.joinToString(", ") { it.name }
        val foresight = runCatching { anticipation.consider(request, summary, tools) }.getOrNull() ?: return
        if (foresight.say.isNotBlank()) voice.speak(foresight.say)
        if (foresight.task.isNotBlank()) {
            LogBus.log("run", "🤝 acción anticipada: ${foresight.task}")
            val goal = "ACCIÓN PREVENTIVA AUTÓNOMA (el usuario no la pidió explícito pero es de " +
                "certeza total y le conviene): ${foresight.task}. Hazla de forma directa y para."
            runCatching { newSession(surface, service, user, resume = false, maxTurns = 12).first.run(goal, announce = false) }
                .onFailure { LogBus.log("run", "acción anticipada falló: ${it.message}") }
        }
    }

    /* ---------- Detener la ejecución (botón rojo flotante + notificación) ---------- */

    @Volatile private var runJob: Job? = null
    @Volatile private var engineCanceller: (() -> Unit)? = null

    /** Hay una ejecución autónoma en curso: no es momento de que el aprendizaje interrumpa por voz. */
    val executing get() = runJob != null

    fun stopExecution() {
        LogBus.log("app", "⏹ detención solicitada por el usuario")
        runJob?.cancel(CancellationException("Detenida por ti ✋"))
    }

    /**
     * Un audio nuevo llegó mientras el asistente ejecutaba: NO se encola: se añade al objetivo y el
     * motor se reinterpreta desde cero con AMBOS prompts (uno puede anular o modificar el otro). Si
     * ya no hay ejecución, arranca una normal.
     */
    fun augmentExecution(extra: String) {
        val e = extra.trim()
        if (e.isBlank()) return
        if (executing) {
            synchronized(goalPrompts) { goalPrompts.add(e) }
            LogBus.log("run", "＋ audio durante ejecución: \"${e.take(80)}\"")
            engineCanceller?.invoke()
        } else {
            scope.launch { runCatching { run(e, bubble) } }
        }
    }

    /** Envuelve la ejecución: rastrea el Job para cancelarlo y muestra los controles de stop. */
    private suspend fun <T> running(block: suspend () -> T): T {
        runJob = kotlin.coroutines.coroutineContext[Job]
        bubble?.companion(true)
        bubble?.showStop(true)
        notifyRunning(true)
        // Toda ejecución autónoma también aprende: los taps del asistente son señales igual de
        // válidas que los del usuario. Se enciende en silencio y al terminar consolida aparte
        // (en background, para no retrasar la respuesta). Si el 🎓 ya estaba activo, no se toca.
        val autoLearn = !passive.active
        if (autoLearn) passive.start(quiet = true)
        val startedAt = System.currentTimeMillis()
        try {
            return block()
        } finally {
            runJob = null
            engineCanceller = null
            bubble?.companion(false)
            bubble?.showStop(false)
            bubble?.showExecutionMic(false) // limpia el micrófono de ejecución también si te detienen
            bubble?.stopExecLive(announce = false) // la escucha en vivo muere con su ejecución
            notifyRunning(false)
            if (autoLearn && passive.active) scope.launch(Dispatchers.IO) { passive.stop(quiet = true) }
            // Tiempo que Ü usó el dispositivo por ti → alimenta la gráfica del modo usuario.
            val elapsed = System.currentTimeMillis() - startedAt
            prefs.edit().putLong(UsageU.KEY_U_ACTIVE_MS,
                prefs.getLong(UsageU.KEY_U_ACTIVE_MS, 0L) + elapsed).apply()
        }
    }

    private fun notifyRunning(on: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!on) { nm.cancel(2); return }
        // Importancia BAJA (canal nuevo para forzar la recreación en dispositivos que ya tenían el
        // canal "run" en HIGH): sigue en la bandeja de notificaciones, pero NO aparece como heads-up
        // superpuesto sobre la pantalla mientras ejecuta. La detención vive en la píldora negra en
        // pantalla (showStop); la notificación es solo un recordatorio discreto.
        nm.createNotificationChannel(NotificationChannel("run_silent", "Ejecución", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        })
        val stop = PendingIntent.getBroadcast(
            this, 0, Intent("com.zevcorp.graph.STOP").setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(this, "run_silent")
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle("Ü está ejecutando")
            .setContentText("Toca para detener")
            .setOngoing(true)
            .setColor(0xFFE5534B.toInt())
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "⏹ Detener", stop).build())
            .build())
    }

    /**
     * La sesión cambió (login/logout desde la app). Al ENTRAR: los recuerdos anónimos de este
     * teléfono se adoptan a la cuenta, se baja la memoria de la cuenta y se suben los aprendizajes
     * de UI locales que la nube no tenga (ya hay token para aportar). Al SALIR no hay nada que
     * sincronizar: la memoria de la cuenta queda en la nube y el archivo local de esa cuenta deja
     * de usarse (el prompt vuelve a las notas anónimas del teléfono).
     */
    fun sessionChanged() {
        if (!auth.loggedIn) return
        scope.launch(Dispatchers.IO) {
            memories.adoptAnonymous()
            memories.syncFromCloud(CloudSync.pullMemory())
            learnedTools.syncFromCloud()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashReporter()
        prefs = getSharedPreferences("graph", MODE_PRIVATE)
        // Tema guardado (claro por defecto): cara y app en blanco/negro, sin azul.
        Palette.mode = runCatching { ThemeMode.valueOf(prefs.getString("theme", ThemeMode.LIGHT.name)!!) }
            .getOrDefault(ThemeMode.LIGHT)
        // Las llamadas a la nube viajan con la sesión del usuario (si hay): la memoria personal
        // la exige (RLS por cuenta) y los aprendizajes de UI la usan para poder aportar.
        CloudSync.userToken = { auth.accessToken() }
        // Trae los aprendizajes y la memoria de la nube (y sube lo local que falte allá).
        // Grafo de conocimiento (Neo4j Aura): credenciales en caliente desde prefs.
        // Credenciales del grafo: lo que el usuario ponga en la UI manda; si no, caen a las incrustadas
        // en el APK (BuildConfig, horneadas desde apikey.properties) para que funcione recién instalado.
        KnowledgeGraph.credentials = {
            Triple(
                resolvedKey("neo4jUri", "remoteNeo4jUri", BuildConfig.DEFAULT_NEO4J_URI),
                resolvedKey("neo4jUser", "remoteNeo4jUser", BuildConfig.DEFAULT_NEO4J_USER),
                resolvedKey("neo4jPass", "remoteNeo4jPass", BuildConfig.DEFAULT_NEO4J_PASS),
            )
        }
        // Config remota (keys) del backend: una vez al arrancar, best-effort, nunca bloquea.
        scope.launch(Dispatchers.IO) { RemoteConfig.refresh() }
        scope.launch(Dispatchers.IO) {
            learnedTools.syncFromCloud()
            workflows.syncFromCloud()
            memories.syncFromCloud(CloudSync.pullMemory())
            // Con el conocimiento ya mergeado, se proyecta completo al grafo (si está configurado).
            KnowledgeGraph.syncAll(learnedTools.list(), workflows.list())
        }
        // Sondeo de actualizaciones: al arrancar y cada ~30 min. El proceso sigue vivo por el servicio
        // de accesibilidad, así que el aviso de "hay actualización" llega casi como un push.
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { Updater.checkAndNotify(this@GraphApp) }
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
    }

    /**
     * Si algo revienta (arranque incluido), en vez de cerrarse en silencio se guarda la traza en
     * files/last_crash.txt y se abre CrashActivity mostrándola (en su propio proceso). Así se puede
     * capturar el error exacto aunque no haya cable/logcat.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
            runCatching { java.io.File(filesDir, "last_crash.txt").writeText(trace) }
            runCatching {
                startActivity(
                    Intent(this, com.zevcorp.graph.ui.CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(com.zevcorp.graph.ui.CrashActivity.EXTRA_TRACE, trace))
            }
            runCatching { previous?.uncaughtException(thread, error) }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }
    }

    companion object {
        lateinit var instance: GraphApp

        /** Keys por defecto, incrustadas al compilar. Modificables desde la UI (prefs las sobrescriben). */
        const val DEFAULT_API_KEY = BuildConfig.DEFAULT_API_KEY
        const val DEFAULT_DEEPGRAM_KEY = BuildConfig.DEFAULT_DEEPGRAM_KEY
        const val DEFAULT_OPENAI_KEY = BuildConfig.DEFAULT_OPENAI_KEY
    }
}
