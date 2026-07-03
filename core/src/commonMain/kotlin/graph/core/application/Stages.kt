package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val NO_LOG = GraphLog { _, _ -> }

/**
 * Etapa 1 · TEACHING: el usuario graba su pantalla enseñando la tarea;
 * un LLM analiza el video como tutorial y lo destila en una Lesson.
 */
class TeachingStage(
    private val recorder: ScreenRecorder,
    private val analyzer: TutorialAnalyzer,
    private val lessons: LessonRepository,
    private val log: GraphLog = NO_LOG,
) {
    fun startRecording() = recorder.start()

    suspend fun stopAndAnalyze(): Lesson {
        val video = recorder.stop()
        log.log("teaching", "video grabado: $video · analizando…")
        return analyzer.analyze(video).also {
            lessons.save(it)
            log.log("teaching", "lección: ${it.goal} (${it.steps.size} pasos)")
        }
    }
}

/**
 * Etapa 2 · LEARNING (consciente): el modelo con computer use sigue la Lesson en el dispositivo real.
 * Los steps se graban por DOS vías, como el content-script de Graph: la acción resuelta del agente y,
 * de respaldo, los eventos reales del árbol de UI (clic/tecleo) deduplicados contra lo ya grabado.
 * Ante dudas pregunta al usuario (texto, voz o demostración; la demo también se graba como steps).
 * El workflow SIEMPRE se guarda al final, aunque el learning se interrumpa con error.
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
    suspend fun run(lesson: Lesson): Workflow = coroutineScope {
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
                    // evento del árbol de UI que el agente no registró → grábalo igual
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
                if (turn.done) {
                    log.log("learning", "fin del agente: ${turn.text.take(120)}")
                    break
                }

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
                        ui.setCapturing(false) // flush de inputs pendientes de la demo
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
                delay(600) // dejar asentar la UI antes de la siguiente captura
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            log.log("learning", "ERROR: $error")
        }
        ui.setCapturing(false) // flush final
        delay(300)
        collector.cancel()

        if (steps.isEmpty() && error != null) throw IllegalStateException(error)
        val workflow = Workflow(
            id = newId(),
            name = lesson.goal.take(60),
            purpose = summary.ifBlank { lesson.goal } +
                (error?.let { " (interrumpido: ${it.take(80)})" } ?: ""),
            steps = steps,
            variables = Workflow.deriveVariables(steps),
        )
        workflows.save(workflow) // se guarda siempre, aun con learning interrumpido
        log.log("learning", "workflow ${workflow.id} guardado: ${steps.size} steps, " +
            "${workflow.variables.size} variables" + (error?.let { " (con error)" } ?: ""))
        workflow
    }
}

/**
 * Etapa 3 · SUBCONSCIENTE: reproduce el workflow aprendido directamente sobre la superficie de
 * accesibilidad, sin LLM — rápido, barato y confiable, activado desde la terminal.
 */
class SubconsciousStage(
    private val ui: UiSurface,
    private val workflows: WorkflowRepository,
    private val log: GraphLog = NO_LOG,
) {
    suspend fun run(id: String, inputs: Map<String, String> = emptyMap()): String {
        val workflow = workflows.get(id) ?: return "Workflow $id no encontrado"
        log.log("subconsciente", "ejecutando ${workflow.id}: ${workflow.steps.size} steps")
        for (step in workflow.steps) {
            val value = if (step.action == ActionType.INPUT)
                inputs["input_${step.order}"]
                    ?: workflow.variables.firstOrNull { it.name == "input_${step.order}" }?.default
                    ?: step.value
            else step.value

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
