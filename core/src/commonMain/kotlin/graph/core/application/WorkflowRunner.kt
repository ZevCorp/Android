package graph.core.application

import graph.core.domain.ExecutionMode
import graph.core.domain.GraphLog
import graph.core.domain.UiPlayer
import graph.core.domain.Workflow
import graph.core.domain.WorkflowExecutor
import graph.core.domain.WorkflowOutcome
import graph.core.domain.WorkflowStep
import kotlinx.coroutines.delay

private val NO_LOG = GraphLog { _, _ -> }

/**
 * EJECUTOR DE WORKFLOWS: recorre los steps en orden haciendo switch entre las dos vías según avanza,
 * igual que un humano ejecutando una tarea que en parte ya conoce:
 * - step SUBCONSCIENTE → clic por árbol de UI (MCP): rápido, sin mirar la pantalla.
 * - step CONSCIENTE → `conscious` (Gemini computer-use acotado a ese step): mirar y decidir.
 * Si un clic subconsciente falla (el elemento no está donde se esperaba), el step cae en caliente a
 * la vía consciente: el switch es inmediato, dentro de la misma tarea.
 */
class WorkflowRunner(
    private val player: UiPlayer,
    /** Ejecuta un step de forma consciente (motor acotado). Recibe el workflow, el step y el contexto de esta ejecución. */
    private val conscious: suspend (Workflow, WorkflowStep, String) -> Boolean,
    private val mode: ExecutionMode? = null,
    private val stepDelay: () -> Long = { 350 },
    private val log: GraphLog = NO_LOG,
) : WorkflowExecutor {

    override suspend fun run(workflow: Workflow, context: String): WorkflowOutcome {
        log.log("workflow", "▶ \"${workflow.name}\": ${workflow.steps.size} steps")
        val failed = mutableListOf<String>()
        var subCount = 0
        var consCount = 0
        workflow.steps.forEachIndexed { i, step ->
            val n = i + 1
            var done = false
            if (step.subconscious && step.target.isNotBlank()) {
                mode?.executing(true)
                done = player.tapLabel(step.target)
                if (done) {
                    subCount++
                    log.log("workflow", "  $n/${workflow.steps.size} 🧩 subconsciente \"${step.target}\"")
                } else {
                    log.log("workflow", "  $n/${workflow.steps.size} \"${step.target}\" no salió por MCP → switch a consciente")
                }
            }
            if (!done) {
                mode?.executing(false)
                done = runCatching { conscious(workflow, step, context) }
                    .getOrElse { log.log("workflow", "  $n consciente falló: ${it.message}"); false }
                if (done) {
                    consCount++
                    log.log("workflow", "  $n/${workflow.steps.size} 👁 consciente: ${step.action}")
                }
            }
            if (!done) failed += "step $n (${step.action})"
            delay(stepDelay()) // deja asentar la UI entre steps
        }
        val detail = buildString {
            append("workflow \"${workflow.name}\": $subCount steps subconscientes + $consCount conscientes")
            if (failed.isNotEmpty()) append(" · fallaron: ${failed.joinToString(", ")}")
        }
        log.log("workflow", "■ $detail")
        return WorkflowOutcome(failed.isEmpty(), detail)
    }
}
