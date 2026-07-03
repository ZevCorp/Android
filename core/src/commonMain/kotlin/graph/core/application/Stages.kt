package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Etapa 1 · TEACHING: el usuario graba su pantalla enseñando la tarea;
 * un LLM analiza el video como tutorial y lo destila en una Lesson.
 */
class TeachingStage(
    private val recorder: ScreenRecorder,
    private val analyzer: TutorialAnalyzer,
    private val lessons: LessonRepository,
) {
    fun startRecording() = recorder.start()

    suspend fun stopAndAnalyze(): Lesson =
        analyzer.analyze(recorder.stop()).also { lessons.save(it) }
}

/**
 * Etapa 2 · LEARNING (consciente): el modelo con computer use sigue la Lesson en el dispositivo real.
 * Cada acción se resuelve contra el árbol de UI y se graba como step semántico de un Workflow.
 * Ante dudas pregunta al usuario, que responde con texto, voz o demostrándolo en pantalla
 * (la demo también se graba como steps, como si las hubiera hecho el asistente).
 */
class LearningStage(
    private val brain: ComputerUseBrain,
    private val ui: UiSurface,
    private val user: UserChannel,
    private val workflows: WorkflowRepository,
    private val newId: () -> String,
    private val maxTurns: Int = 40,
) {
    suspend fun run(lesson: Lesson): Workflow {
        val steps = mutableListOf<Step>()
        fun record(step: Step?, source: StepSource = StepSource.AGENT) {
            if (step != null) steps += step.copy(order = steps.size + 1, source = source)
        }

        brain.begin(lesson)
        var results = emptyList<String>()
        var summary = ""
        var turns = 0
        while (turns++ < maxTurns) {
            val turn = brain.next(ui.state(), results)
            if (turn.text.isNotBlank()) summary = turn.text
            if (turn.done) break

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
                val answer = user.ask(question)
                if (answer.demo) {
                    val captured = mutableListOf<Step>()
                    coroutineScope {
                        val collector = launch { ui.userActions.collect { captured += it } }
                        ui.setCapturing(true)
                        user.awaitDemoEnd()
                        ui.setCapturing(false)
                        delay(200)
                        collector.cancel()
                    }
                    captured.forEach { record(it, StepSource.USER_DEMO) }
                    brain.inform(
                        "El usuario lo demostró en pantalla. Pasos capturados: " +
                            captured.joinToString("; ") { "${it.action} ${it.label.ifBlank { it.selector.short() }}" }
                                .ifBlank { "(ninguno)" } +
                            ". Continúa desde el estado actual de la pantalla."
                    )
                } else {
                    brain.inform("Respuesta del usuario: ${answer.text}")
                }
            }
            delay(600) // dejar asentar la UI antes de la siguiente captura
        }

        val workflow = Workflow(
            id = newId(),
            name = lesson.goal.take(60),
            purpose = summary.ifBlank { lesson.goal },
            steps = steps,
            variables = Workflow.deriveVariables(steps),
        )
        workflows.save(workflow)
        return workflow
    }
}

/**
 * Etapa 3 · SUBCONSCIENTE: reproduce el workflow aprendido directamente sobre la superficie de
 * accesibilidad, sin LLM — rápido, barato y confiable, activado desde la terminal.
 */
class SubconsciousStage(
    private val ui: UiSurface,
    private val workflows: WorkflowRepository,
) {
    suspend fun run(id: String, inputs: Map<String, String> = emptyMap()): String {
        val workflow = workflows.get(id) ?: return "Workflow $id no encontrado"
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
            if (!ok) return "Fallo en step ${step.order}: ${step.action} ${step.label.ifBlank { step.selector.short() }}"
            delay(250)
        }
        return "OK · ${workflow.steps.size} steps ejecutados (${workflow.id})"
    }
}
