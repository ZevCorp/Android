package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val NO_LOG = GraphLog { _, _ -> }
private val NO_REPORT = RunReporter { _, _, _ -> }
private val NO_VOICE = object : Voice {
    override fun narrate(text: String) {}
    override fun speak(text: String) {}
}

/**
 * Estado con el que nace un step recién registrado, sea el ejecutor Gemini o el usuario (demo en video):
 * 🟢 verde si es reproducible sin LLM (selector sólido del árbol de UI, o abrir app/escribir),
 * 🔴 rojo si solo se pudo por coordenadas crudas. No hay paso de borrador: se aprende desde el registro.
 */
internal fun learnedStatus(step: Step): StepStatus =
    if (step.action == ActionType.LAUNCH || step.action == ActionType.INPUT || step.selector.solid())
        StepStatus.CONFIRMED else StepStatus.LLM

private fun describe(step: Step, value: String = step.value) =
    "${step.action} \"${step.label.ifBlank { step.selector.short() }}\"" +
        (if (value.isNotBlank()) " valor=\"${value.take(60)}\"" else "") +
        (step.screen.takeIf { it.isNotBlank() }?.let { " (pantalla: $it)" } ?: "")

/**
 * Etapa 1 · TEACHING: el usuario graba su pantalla enseñando la tarea. Se capturan sus clics/tecleos
 * del árbol de UI como workflow BORRADOR (100% DRAFT). Un observador opcional (Gemini) mira en vivo y
 * puede hablar o preguntar algo importante mientras el usuario enseña. Al detener, el LLM analiza el
 * video (Lesson), el curador separa tronco/ramas y el borrador queda ligado a la lección.
 */
class TeachingStage(
    private val recorder: ScreenRecorder,
    private val analyzer: TutorialAnalyzer,
    private val lessons: LessonRepository,
    private val workflows: WorkflowRepository,
    private val ui: () -> UiSurface?,
    private val newId: () -> String,
    private val curator: WorkflowCurator? = null,
    private val brain: (() -> ComputerUseBrain)? = null,
    private val voice: Voice = NO_VOICE,
    private val user: UserChannel? = null,
    private val log: GraphLog = NO_LOG,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captured = mutableListOf<Step>()
    private var collector: Job? = null
    private var observer: Job? = null

    fun startRecording() {
        recorder.start()
        captured.clear()
        val surface = ui() ?: return
        collector = scope.launch { surface.userActions.collect { captured += it } }
        surface.setCapturing(true)
        log.log("teaching", "grabando video + capturando clics del árbol de UI")
        // Observador en vivo: el asistente puede intervenir con voz mientras aprende.
        brain?.let { factory ->
            val observerBrain = factory()
            observer = scope.launch {
                val lesson = Lesson(id = "live", goal = "aprender lo que el usuario está enseñando")
                var lastActions = 0
                while (true) {
                    delay(9000) // ritmo tranquilo para no gastar tokens ni interrumpir de más
                    val recent = captured.drop(lastActions).map { "${it.action} ${it.label.ifBlank { it.selector.short() }}" }
                    lastActions = captured.size
                    val turn = runCatching { observerBrain.observe(lesson, surface.state(), recent) }.getOrNull() ?: continue
                    if (turn.speech != null) voice.speak(turn.speech)
                    else if (turn.narration.isNotBlank()) voice.narrate(turn.narration)
                    turn.question?.let { q -> voice.speak(q); user?.ask(q) }
                }
            }
        }
    }

    suspend fun stopAndAnalyze(): Lesson {
        observer?.cancel()
        ui()?.setCapturing(false)
        delay(300)
        collector?.cancel()
        val video = recorder.stop()
        log.log("teaching", "video: $video · ${captured.size} acciones capturadas · analizando…")
        val lesson = analyzer.analyze(video)
        lessons.save(lesson)
        if (captured.isNotEmpty()) {
            // El usuario es el ejecutor: sus acciones del árbol de UI se registran YA aprendidas
            // (verdes), igual que cuando ejecuta Gemini. Sin paso de borrador.
            val steps = captured.mapIndexed { i, s ->
                s.copy(order = i + 1, source = StepSource.USER_DEMO, status = learnedStatus(s))
            }
            var wf = Workflow(
                id = newId(),
                name = lesson.goal.take(60),
                purpose = lesson.summary.ifBlank { lesson.goal },
                lessonId = lesson.id,
                steps = steps,
                variables = Workflow.deriveVariables(steps),
            )
            // Capa de inteligencia: acomoda tronco/ramas y elimina pasos que sobren.
            curator?.let { c ->
                wf = runCatching { c.curate(lesson, wf) }
                    .getOrElse { e -> log.log("teaching", "curación falló (${e.message?.take(80)}), se guarda sin curar"); wf }
            }
            workflows.save(wf)
            log.log("teaching", "workflow ${wf.id}: ${wf.steps.size} steps (${wf.learnedPct()}% aprendido) · " +
                "${wf.branches.size} ramas ${wf.branches.joinToString { it.name }}")
        }
        log.log("teaching", "lección: ${lesson.goal}")
        return lesson
    }
}

/**
 * Corre turnos de computer use para una instrucción puntual (reparar un step, ejecutar un step rojo)
 * hasta que el modelo devuelve el control respondiendo solo texto. Narra y habla según el turno.
 */
internal class AgentRunner(
    private val brain: ComputerUseBrain,
    private val ui: UiSurface,
    private val user: UserChannel?,
    private val voice: Voice,
    private val log: GraphLog,
) {
    private var begun = false

    suspend fun instruct(lesson: Lesson, intro: String, instruction: String, maxTurns: Int = 8) {
        if (!begun) { brain.begin(lesson, intro); begun = true }
        brain.inform(instruction)
        var results = emptyList<String>()
        repeat(maxTurns) {
            val turn = brain.next(ui.state(), results)
            if (turn.narration.isNotBlank()) voice.narrate(turn.narration)
            if (turn.speech != null) voice.speak(turn.speech)
            if (turn.done) { log.log("agente", "devuelve el control: ${turn.text.take(100)}"); return }
            val out = mutableListOf<String>()
            turn.actions.forEachIndexed { idx, action ->
                turn.intents.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let { voice.narrate(it) }
                val ok = when (action) {
                    is AgentAction.ClickAt -> ui.tapAt(action.x, action.y) != null
                    is AgentAction.TypeAt -> ui.typeAt(action.x, action.y, action.text) != null
                    is AgentAction.OpenApp -> ui.launch(action.name) != null
                    is AgentAction.Scroll -> ui.scroll(action.down)
                    is AgentAction.Swipe -> ui.swipe(action.x1, action.y1, action.x2, action.y2, action.ms)
                    is AgentAction.Key -> ui.pressKey(action.key)
                    is AgentAction.Wait -> { delay(action.ms); true }
                }
                out += if (ok) "ok" else "no se encontró un elemento para la acción"
            }
            results = out
            turn.question?.let { q ->
                voice.speak(q)
                val answer = user?.ask(q)
                when {
                    answer == null -> brain.inform("No hay usuario disponible; usa tu mejor criterio.")
                    answer.demo -> { user.awaitDemoEnd(); brain.inform("El usuario lo demostró en pantalla; continúa desde el estado actual.") }
                    else -> brain.inform("Respuesta del usuario: ${answer.text}")
                }
            }
            delay(600)
        }
        log.log("agente", "límite de turnos alcanzado para la instrucción")
    }
}

/**
 * ETAPA UNIFICADA · EJECUCIÓN. Ya no hay "consciente" vs "subconsciente" como modos separados: es una
 * sola ejecución donde conviven ambos según el ESTADO de cada step.
 *  - 🟢 CONFIRMED  → se ejecuta por árbol de UI, sin LLM (subconsciente, rápido y barato).
 *  - 🟡 DRAFT/🔴 LLM → Gemini 3.5 Flash lo intenta/supervisa; si queda bien pasa a verde (aprende en vivo).
 * La primera ejecución de una grabación está 100% en borrador; cada corrida sube el % aprendido.
 * `depth` limita cuántos steps avanzar (ejecución por tramos); `cursor` recuerda dónde seguir.
 */
class ExecutionStage(
    private val brain: () -> ComputerUseBrain,
    private val ui: UiSurface,
    private val user: UserChannel?,
    private val workflows: WorkflowRepository,
    private val newId: () -> String,
    private val voice: Voice = NO_VOICE,
    private val report: RunReporter = NO_REPORT,
    private val log: GraphLog = NO_LOG,
    private val maxTurns: Int = 40,
) {
    /** Ejecuta la lección: usa su workflow borrador si existe; si no, exploración libre con el agente. */
    suspend fun runLesson(lesson: Lesson): Workflow {
        val draft = workflows.list().lastOrNull { it.lessonId == lesson.id && it.steps.isNotEmpty() }
        return if (draft != null) execute(draft.id).let { workflows.get(draft.id) ?: draft }
        else freeRun(lesson)
    }

    /**
     * Corre el workflow (id) con ramas activas y profundidad. Devuelve un texto de resultado (para la CLI).
     * Aprende en vivo: marca verdes los steps que el árbol de UI aplica bien según el supervisor.
     */
    suspend fun execute(
        id: String,
        inputs: Map<String, String> = emptyMap(),
        branches: Set<String> = emptySet(),
        depth: Int = Int.MAX_VALUE,
    ): String {
        val wf0 = workflows.get(id) ?: return "Workflow $id no encontrado"
        branches.firstOrNull { name -> wf0.branches.none { it.name == name } }
            ?.let { return "Rama '$it' no existe. Uso: ${wf0.usage()}" }

        val steps = wf0.steps.toMutableList()
        val start = if (wf0.cursor in 1 until steps.size) wf0.cursor else 0
        var supervisor: ComputerUseBrain? = null
        var runner: AgentRunner? = null
        val lesson = Lesson(id = wf0.id, goal = wf0.name, summary = wf0.purpose)
        val intro = "Ejecutas y consolidas pasos de un workflow en un teléfono Android real. " +
            "Haz solo lo que se te pida y devuelve el control respondiendo únicamente con texto. " +
            "Puedes hablar (speak) o preguntar (ask_user) solo cosas importantes."

        fun persist(cursor: Int) = workflows.save(
            wf0.copy(steps = steps, variables = Workflow.deriveVariables(steps), cursor = cursor))
        fun emit(active: Int, note: String) = report.report(
            wf0.copy(steps = steps), steps.getOrNull(active)?.order ?: -1, note)

        log.log("ejecucion", "▶ ${wf0.id} '${wf0.name}' · ${wf0.learnedPct()}% aprendido · desde step ${start + 1}" +
            (if (branches.isEmpty()) " · solo tronco" else " · ramas: ${branches.joinToString(",")}") +
            (if (depth != Int.MAX_VALUE) " · profundidad $depth" else ""))
        voice.narrate("Vamos con ${wf0.name} 🚀")
        emit(start, "inicio")

        var advanced = 0
        var i = start
        while (i < steps.size && advanced < depth) {
            val step = steps[i]
            if (step.branch.isNotBlank() && step.branch !in branches) { i++; continue } // rama desactivada
            emit(i, "ejecutando")

            val value = when {
                step.action == ActionType.INPUT ->
                    inputs["input_${step.order}"]
                        ?: wf0.variables.firstOrNull { it.name == "input_${step.order}" }?.default
                        ?: step.value
                // CLICK con paralelos: si se pide una variante (pick_N) se ejecuta ESA; si no, la aprendida.
                step.action == ActionType.CLICK && step.peers.isNotEmpty() ->
                    inputs["pick_${step.order}"] ?: step.label
                else -> step.value
            }

            // 🟢 subconsciente: árbol de UI sin LLM
            if (step.status == StepStatus.CONFIRMED) {
                voice.narrate(narrationFor(step))
                var ok = false; var t = 0
                while (!ok && t++ < 6) { ok = ui.perform(step, value); if (!ok) delay(500) }
                log.log("ejecucion", "step ${step.order} 🟢 ${describe(step, value)} → ${if (ok) "ok" else "falló"}")
                if (!ok) {
                    steps[i] = step.copy(status = StepStatus.DRAFT) // se desmarca: el próximo paso lo re-consolida
                    emit(i, "falló-verde")
                } else emit(i, "ok-verde")
                if (ok) { advanced++; i++; persist(i); delay(200); continue }
            }

            // 🟡/🔴 o verde caído: intento por árbol de UI + supervisión de Gemini para aprender
            voice.narrate(narrationFor(step))
            val performed = ui.perform(step, value)
            delay(500)
            if (!performed && step.branch.isNotBlank()) {
                log.log("ejecucion", "step ${step.order} (rama ${step.branch}) no aplica ahora → 🟡")
                advanced++; i++; continue
            }
            val sup = supervisor ?: brain().also { supervisor = it }
            val verdict = if (!performed) Verdict(false, "el árbol de UI no encontró o no pudo ejecutar el elemento")
            else sup.judge(lesson.goal, step, true, ui.state())
            if (!verdict.applicable && step.branch.isNotBlank()) {
                log.log("ejecucion", "step ${step.order} (rama ${step.branch}) no aplicable → 🟡")
                advanced++; i++; continue
            }
            if (verdict.valid) {
                steps[i] = step.copy(status = StepStatus.CONFIRMED)
                log.log("ejecucion", "step ${step.order} CONSOLIDADO 🟢 ${describe(step, value)}")
                emit(i, "aprendido-verde")
            } else {
                steps[i] = step.copy(status = StepStatus.LLM)
                log.log("ejecucion", "step ${step.order} 🔴 (${verdict.reason.take(70)}) → el agente toma el control")
                emit(i, "reparando-rojo")
                voice.narrate("Mmm, esto no salió; déjame intentarlo yo 🤔")
                val r = runner ?: AgentRunner(sup, ui, user, voice, log).also { runner = it }
                r.instruct(lesson, intro,
                    "El paso ${step.order} (${describe(step, value)}) falló: ${verdict.reason}. " +
                        "Si la pantalla quedó mal retrocede primero y hazlo tú. Al terminar responde solo texto.")
                emit(i, "rojo")
            }
            persist(i + 1)
            advanced++; i++
            delay(300)
        }

        val done = i >= steps.size
        persist(if (done) 0 else i) // al terminar, el cursor vuelve al inicio
        val green = steps.count { it.status == StepStatus.CONFIRMED }
        val result = "OK · $advanced steps ejecutados · ${green * 100 / maxOf(1, steps.size)}% aprendido" +
            (if (!done) " · pausado en step ${i + 1} (usa --depth para continuar)" else "") + " (${wf0.id})"
        log.log("ejecucion", "■ $result")
        voice.narrate(if (done) "¡Listo! 🎉" else "Pausa por profundidad ✋")
        emit(if (done) 0 else i, "fin")
        return result
    }

    private fun narrationFor(step: Step): String {
        val what = step.label.ifBlank { step.selector.short() }
        return when (step.action) {
            ActionType.LAUNCH -> "Abriendo $what 📲"
            ActionType.CLICK -> "Toco \"$what\" 👆"
            ActionType.INPUT -> "Escribo en \"$what\" ⌨️"
            ActionType.SCROLL -> "Deslizo para ver más 📜"
            ActionType.KEY -> "Tecla $what"
            ActionType.WAIT -> "Espero un momento ⏳"
        }
    }

    /* ---------- modo libre: pides algo nuevo, Gemini lo hace y construye el subconsciente ---------- */

    private suspend fun freeRun(lesson: Lesson): Workflow = coroutineScope {
        log.log("ejecucion", "ejecución nueva (sin workflow previo): Gemini la hace y aprende sobre la marcha")
        val brain = brain()
        val wfId = lesson.id.takeIf { it.startsWith("wf_") } ?: newId()
        val steps = mutableListOf<Step>()
        val agentKeys = ArrayDeque<String>()
        val demoCaptured = mutableListOf<Step>()
        var demoMode = false
        fun key(s: Step) = "${s.action}|${s.selector.viewId}|${s.selector.contentDesc}|${s.selector.text}|${s.label}"
        fun snapshot() = Workflow(id = wfId, name = lesson.goal.take(60), purpose = lesson.summary.ifBlank { lesson.goal },
            lessonId = lesson.id, steps = steps, variables = Workflow.deriveVariables(steps))
        fun record(step: Step?, source: StepSource = StepSource.AGENT) {
            step ?: return
            val status = learnedStatus(step)
            val green = status == StepStatus.CONFIRMED
            val s = step.copy(order = steps.size + 1, source = source, status = status)
            steps += s
            if (source == StepSource.AGENT) {
                agentKeys.addLast(key(s)); if (agentKeys.size > 24) agentKeys.removeFirst()
            }
            log.log("ejecucion", "step ${s.order} ${if (green) "🟢" else "🔴"} [${source.name}] ${s.action} ${s.label.ifBlank { s.selector.short() }}")
            report.report(snapshot(), s.order, if (green) "aprendido-verde" else "rojo")
        }

        ui.setCapturing(true)
        val collector = launch {
            ui.userActions.collect { event ->
                when {
                    demoMode -> { record(event, StepSource.USER_DEMO); demoCaptured += event }
                    !agentKeys.remove(key(event)) -> record(event)
                }
            }
        }

        brain.begin(lesson)
        var results = emptyList<String>()
        var summary = ""
        var error: String? = null
        try {
            var turns = 0
            while (turns++ < maxTurns) {
                val turn = brain.next(ui.state(), results)
                if (turn.narration.isNotBlank()) voice.narrate(turn.narration)
                if (turn.speech != null) voice.speak(turn.speech)
                if (turn.text.isNotBlank()) summary = turn.text
                if (turn.done) break
                val out = mutableListOf<String>()
                turn.actions.forEachIndexed { idx, action ->
                    turn.intents.getOrNull(idx)?.takeIf { it.isNotBlank() }?.let { voice.narrate(it) }
                    val step = when (action) {
                        is AgentAction.ClickAt -> ui.tapAt(action.x, action.y)
                        is AgentAction.TypeAt -> ui.typeAt(action.x, action.y, action.text)
                        is AgentAction.OpenApp -> ui.launch(action.name)
                        is AgentAction.Scroll -> { ui.scroll(action.down); null }
                        is AgentAction.Swipe -> { ui.swipe(action.x1, action.y1, action.x2, action.y2, action.ms); null }
                        is AgentAction.Key -> { ui.pressKey(action.key); null }
                        is AgentAction.Wait -> { delay(action.ms); null }
                    }
                    record(step)
                    out += if (step != null || action !is AgentAction.ClickAt && action !is AgentAction.TypeAt && action !is AgentAction.OpenApp)
                        "ok" else "no se encontró un elemento para la acción"
                }
                results = out
                turn.question?.let { question ->
                    voice.speak(question)
                    val answer = user?.ask(question) ?: Answer("usa tu mejor criterio")
                    if (answer.demo) {
                        demoCaptured.clear(); demoMode = true
                        user?.awaitDemoEnd()
                        ui.setCapturing(false); ui.setCapturing(true); delay(300); demoMode = false
                        brain.inform("El usuario lo demostró: " +
                            demoCaptured.joinToString("; ") { "${it.action} ${it.label.ifBlank { it.selector.short() }}" }.ifBlank { "(nada)" } +
                            ". Continúa desde el estado actual.")
                    } else brain.inform("Respuesta del usuario: ${answer.text}")
                }
                delay(600)
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString(); log.log("ejecucion", "ERROR: $error")
        }
        ui.setCapturing(false); delay(300); collector.cancel()
        if (steps.isEmpty() && error != null) throw IllegalStateException(error)
        val workflow = snapshot().copy(
            purpose = summary.ifBlank { lesson.goal } + (error?.let { " (interrumpido: ${it.take(80)})" } ?: ""))
        workflows.save(workflow)
        val green = steps.count { it.status == StepStatus.CONFIRMED }
        log.log("ejecucion", "workflow ${workflow.id}: ${steps.size} steps · ${workflow.learnedPct()}% aprendido ($green verdes)")
        report.report(workflow, 0, "fin")
        workflow
    }
}
