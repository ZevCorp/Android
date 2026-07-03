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

private fun describe(step: Step, value: String = step.value) =
    "${step.action} \"${step.label.ifBlank { step.selector.short() }}\"" +
        (if (value.isNotBlank()) " valor=\"${value.take(60)}\"" else "") +
        (step.screen.takeIf { it.isNotBlank() }?.let { " (pantalla: $it)" } ?: "")

/**
 * Etapa 1 · TEACHING: el usuario graba su pantalla enseñando la tarea. Mientras tanto se capturan
 * sus clics/tecleos del árbol de UI como workflow BORRADOR (steps DRAFT). Al detener, el LLM analiza
 * el video como tutorial (Lesson) y el borrador queda ligado a esa lección para el learning.
 */
class TeachingStage(
    private val recorder: ScreenRecorder,
    private val analyzer: TutorialAnalyzer,
    private val lessons: LessonRepository,
    private val workflows: WorkflowRepository,
    private val ui: () -> UiSurface?,
    private val newId: () -> String,
    private val log: GraphLog = NO_LOG,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captured = mutableListOf<Step>()
    private var collector: Job? = null

    fun startRecording() {
        recorder.start()
        captured.clear()
        ui()?.let { surface ->
            collector = scope.launch { surface.userActions.collect { captured += it } }
            surface.setCapturing(true)
            log.log("teaching", "grabando video + capturando clics del árbol de UI")
        }
    }

    suspend fun stopAndAnalyze(): Lesson {
        ui()?.setCapturing(false) // flush de inputs pendientes
        delay(300)
        collector?.cancel()
        val video = recorder.stop()
        log.log("teaching", "video: $video · ${captured.size} acciones capturadas · analizando…")
        val lesson = analyzer.analyze(video)
        lessons.save(lesson)
        if (captured.isNotEmpty()) {
            val steps = captured.mapIndexed { i, s ->
                s.copy(order = i + 1, source = StepSource.USER_DEMO, status = StepStatus.DRAFT)
            }
            val draft = Workflow(
                id = newId(),
                name = lesson.goal.take(60),
                purpose = lesson.summary.ifBlank { lesson.goal },
                lessonId = lesson.id,
                steps = steps,
                variables = Workflow.deriveVariables(steps),
            )
            workflows.save(draft)
            log.log("teaching", "workflow borrador ${draft.id}: ${steps.size} steps DRAFT")
        }
        log.log("teaching", "lección: ${lesson.goal}")
        return lesson
    }
}

/**
 * Corre turnos de computer use para una instrucción puntual (reparar un step, ejecutar un step rojo)
 * hasta que el modelo devuelve el control respondiendo solo texto.
 */
internal class AgentRunner(
    private val brain: ComputerUseBrain,
    private val ui: UiSurface,
    private val user: UserChannel?,
    private val log: GraphLog,
) {
    private var begun = false

    suspend fun instruct(lesson: Lesson, intro: String, instruction: String, maxTurns: Int = 8) {
        if (!begun) { brain.begin(lesson, intro); begun = true }
        brain.inform(instruction)
        var results = emptyList<String>()
        repeat(maxTurns) {
            val turn = brain.next(ui.state(), results)
            if (turn.done) { log.log("agente", "devuelve el control: ${turn.text.take(100)}"); return }
            val out = mutableListOf<String>()
            for (action in turn.actions) {
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
 * Etapa 2 · LEARNING (consciente): consolidación supervisada, como el aprendizaje humano.
 *
 * Si la lección tiene workflow borrador, avanza UN step a la vez ejecutándolo por árbol de UI;
 * tras cada step se toma captura y Gemini 3.5 Flash (supervisor) juzga si quedó bien aplicado:
 *  - válido  → el step se marca CONFIRMED (verde): parte definitiva de la "red neuronal"
 *  - fallido → computer use toma el control, se devuelve si hace falta, lo hace él mismo,
 *              y el step queda LLM (rojo); el siguiente step vuelve al árbol de UI.
 * Re-ejecutar el learning reintenta los rojos para seguir consolidando.
 *
 * Sin borrador (lecciones antiguas), corre el modo libre: el agente ejecuta todo el tutorial
 * y las acciones se graban como steps DRAFT (que un learning posterior consolidará).
 */
class LearningStage(
    private val brain: ComputerUseBrain,
    private val ui: UiSurface,
    private val user: UserChannel,
    private val workflows: WorkflowRepository,
    private val newId: () -> String,
    private val log: GraphLog = NO_LOG,
    private val maxTurns: Int = 40,
) {
    suspend fun run(lesson: Lesson): Workflow {
        val draft = workflows.list().lastOrNull { it.lessonId == lesson.id && it.steps.isNotEmpty() }
        return if (draft != null) supervised(lesson, draft) else freeRun(lesson)
    }

    /* ---------- modo supervisado: workflow por árbol de UI + Gemini como juez ---------- */

    private suspend fun supervised(lesson: Lesson, draft: Workflow): Workflow {
        log.log("learning", "modo supervisado sobre ${draft.id}: ${draft.steps.size} steps")
        val runner = AgentRunner(brain, ui, user, log)
        val intro = "Supervisas la repetición paso a paso de un workflow que el usuario enseñó. " +
            "El sistema ejecuta los pasos por el árbol de accesibilidad; tú solo intervienes cuando " +
            "un paso falla: si la pantalla quedó en mal estado retrocede (go_back), haz el paso tú " +
            "mismo y devuelve el control respondiendo únicamente con texto."
        val steps = draft.steps.toMutableList()
        fun save() = workflows.save(draft.copy(steps = steps, variables = Workflow.deriveVariables(steps)))

        var error: String? = null
        try {
            for (i in steps.indices) {
                val step = steps[i]
                if (step.status == StepStatus.LLM) {
                    log.log("learning", "step ${step.order} es rojo → lo ejecuta el agente")
                    runner.instruct(lesson, intro, "Ejecuta tú el paso ${step.order}: ${describe(step)}. Al terminar responde solo texto.")
                    // sigue rojo: se reintenta consolidar en el próximo learning
                    continue
                }
                val performed = ui.perform(step, step.value)
                delay(500)
                val verdict = if (!performed) Verdict(false, "el árbol de UI no encontró o no pudo ejecutar el elemento")
                else brain.judge(lesson.goal, step, true, ui.state())
                if (verdict.valid) {
                    steps[i] = step.copy(status = StepStatus.CONFIRMED)
                    log.log("learning", "step ${step.order} CONFIRMADO 🟢 ${describe(step)}")
                } else {
                    steps[i] = step.copy(status = StepStatus.LLM)
                    log.log("learning", "step ${step.order} inválido 🔴 (${verdict.reason.take(80)}) → el agente toma el control")
                    runner.instruct(lesson, intro,
                        "El paso ${step.order} (${describe(step)}) falló: ${verdict.reason}. " +
                            "Si el intento dejó la pantalla en mal estado retrocede primero y hazlo tú. Al terminar responde solo texto.")
                }
                save() // progreso vivo: el grafo de la red neuronal se actualiza step a step
                delay(400)
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            log.log("learning", "ERROR: $error")
        }
        save()
        val green = steps.count { it.status == StepStatus.CONFIRMED }
        val red = steps.count { it.status == StepStatus.LLM }
        log.log("learning", "consolidación: 🟢 $green · 🔴 $red · ⚪ ${steps.size - green - red}" +
            (error?.let { " (interrumpido)" } ?: ""))
        return draft.copy(steps = steps, variables = Workflow.deriveVariables(steps))
    }

    /* ---------- modo libre (lecciones sin borrador): el agente ejecuta y se graba ---------- */

    private suspend fun freeRun(lesson: Lesson): Workflow = coroutineScope {
        log.log("learning", "modo libre: sin workflow borrador para la lección")
        val steps = mutableListOf<Step>()
        val agentKeys = ArrayDeque<String>()
        val demoCaptured = mutableListOf<Step>()
        var demoMode = false
        fun key(s: Step) = "${s.action}|${s.selector.viewId}|${s.selector.bounds}"
        fun record(step: Step?, source: StepSource = StepSource.AGENT) {
            step ?: return
            val s = step.copy(order = steps.size + 1, source = source)
            steps += s
            if (source == StepSource.AGENT) {
                agentKeys.addLast(key(s))
                if (agentKeys.size > 24) agentKeys.removeFirst()
            }
            log.log("learning", "step ${s.order} [${source.name}] ${s.action} " +
                s.label.ifBlank { s.selector.short() } +
                if (s.value.isNotBlank()) " = \"${s.value.take(40)}\"" else "")
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
                if (turn.text.isNotBlank()) summary = turn.text
                if (turn.done) { log.log("learning", "fin del agente: ${turn.text.take(120)}"); break }

                val out = mutableListOf<String>()
                for (action in turn.actions) {
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
                    log.log("learning", "pregunta del agente: $question")
                    val answer = user.ask(question)
                    if (answer.demo) {
                        demoCaptured.clear()
                        demoMode = true
                        user.awaitDemoEnd()
                        ui.setCapturing(false)
                        ui.setCapturing(true)
                        delay(300)
                        demoMode = false
                        brain.inform(
                            "El usuario lo demostró en pantalla. Pasos capturados: " +
                                demoCaptured.joinToString("; ") { "${it.action} ${it.label.ifBlank { it.selector.short() }}" }
                                    .ifBlank { "(ninguno)" } +
                                ". Continúa desde el estado actual de la pantalla."
                        )
                    } else {
                        log.log("learning", "respuesta del usuario: ${answer.text.take(80)}")
                        brain.inform("Respuesta del usuario: ${answer.text}")
                    }
                }
                delay(600)
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            log.log("learning", "ERROR: $error")
        }
        ui.setCapturing(false)
        delay(300)
        collector.cancel()

        if (steps.isEmpty() && error != null) throw IllegalStateException(error)
        val workflow = Workflow(
            id = newId(),
            name = lesson.goal.take(60),
            purpose = summary.ifBlank { lesson.goal } + (error?.let { " (interrumpido: ${it.take(80)})" } ?: ""),
            lessonId = lesson.id,
            steps = steps,
            variables = Workflow.deriveVariables(steps),
        )
        workflows.save(workflow)
        log.log("learning", "workflow ${workflow.id} guardado: ${steps.size} steps DRAFT")
        workflow
    }
}

/**
 * Etapa 3 · SUBCONSCIENTE: los steps verdes (CONFIRMED) y los borradores se reproducen por árbol
 * de UI, sin LLM; los rojos (LLM) se delegan puntualmente a Gemini computer use.
 */
class SubconsciousStage(
    private val ui: UiSurface,
    private val workflows: WorkflowRepository,
    private val brainFactory: (() -> ComputerUseBrain)? = null,
    private val log: GraphLog = NO_LOG,
) {
    suspend fun run(id: String, inputs: Map<String, String> = emptyMap()): String {
        val workflow = workflows.get(id) ?: return "Workflow $id no encontrado"
        val red = workflow.steps.count { it.status == StepStatus.LLM }
        log.log("subconsciente", "ejecutando ${workflow.id}: ${workflow.steps.size} steps ($red rojos)")
        var runner: AgentRunner? = null
        for (step in workflow.steps) {
            val value = if (step.action == ActionType.INPUT)
                inputs["input_${step.order}"]
                    ?: workflow.variables.firstOrNull { it.name == "input_${step.order}" }?.default
                    ?: step.value
            else step.value

            if (step.status == StepStatus.LLM) {
                val factory = brainFactory
                    ?: return "Step ${step.order} requiere Gemini y no hay modelo configurado"
                val r = runner ?: AgentRunner(factory(), ui, null, log).also { runner = it }
                log.log("subconsciente", "step ${step.order} rojo → delegado a Gemini: ${describe(step, value)}")
                r.instruct(
                    Lesson(id = workflow.id, goal = workflow.name, summary = workflow.purpose),
                    "Ejecutas pasos puntuales de un workflow ya aprendido en un teléfono Android real. " +
                        "Haz SOLO lo que se te pida en cada mensaje y devuelve el control respondiendo únicamente con texto.",
                    "Ejecuta este paso: ${describe(step, value)}. Al terminar responde solo texto.",
                )
                delay(250)
                continue
            }

            var ok = false
            var tries = 0
            while (!ok && tries++ < 8) {
                ok = ui.perform(step, value)
                if (!ok) delay(600)
            }
            log.log("subconsciente", "step ${step.order} ${step.action} ${step.label.ifBlank { step.selector.short() }} → ${if (ok) "ok" else "FALLO tras $tries intentos"}")
            if (!ok) return "Fallo en step ${step.order}: ${step.action} ${step.label.ifBlank { step.selector.short() }}"
            delay(250)
        }
        return "OK · ${workflow.steps.size} steps ejecutados (${workflow.id})"
    }
}
