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
 * EJECUTOR DE WORKFLOWS, adaptativo y encadenado. Recorre los steps haciendo switch entre las dos
 * vías igual que un humano que ejecuta una tarea que en parte ya conoce, pero SIN volver a pensar
 * cada paso: los subconscientes se tocan por árbol de UI en cadena, sin ninguna llamada al modelo.
 *
 * En cada paso mira el árbol de UI VIVO (una lectura local barata, NO una llamada API) y decide:
 *  - Si el elemento del paso ESTÁ en pantalla → lo toca (subconsciente) y sigue.
 *  - Si NO está pero el elemento de un paso POSTERIOR sí → el estado ya avanzó (p.ej. Spotify abrió
 *    directo en "Me Gusta"): SALTA los pasos ya cumplidos y encadena desde donde está.
 *  - Si ni este ni ningún paso posterior aparecen → ese paso cae a la vía CONSCIENTE (mini-motor
 *    Gemini acotado a ese paso): solo ahí se gasta una llamada al modelo.
 * Así una segunda ejecución de algo aprendido avanza en ~1-2 s en vez de una llamada API por paso.
 */
class WorkflowRunner(
    private val player: UiPlayer,
    /** Lee las etiquetas tocables de la pantalla ACTUAL (árbol de UI vivo). Lectura local, sin API. */
    private val elements: suspend () -> List<String>,
    /** Ejecuta un step de forma consciente (motor acotado). Recibe el workflow, el step y el contexto. */
    private val conscious: suspend (Workflow, WorkflowStep, String) -> Boolean,
    private val mode: ExecutionMode? = null,
    private val stepDelay: () -> Long = { 350 },
    private val log: GraphLog = NO_LOG,
) : WorkflowExecutor {

    /** ¿Un step tiene un target subconsciente reproducible por etiqueta? */
    private fun WorkflowStep.hasTarget() = subconscious && target.isNotBlank()

    /** ¿La etiqueta está entre los elementos tocables actuales? (match exacto, sin distinción de mayúsculas) */
    private fun present(target: String, live: Set<String>) = target.lowercase() in live

    override suspend fun run(workflow: Workflow, context: String): WorkflowOutcome {
        val steps = workflow.steps
        log.log("workflow", "▶ \"${workflow.name}\": ${steps.size} steps")
        val failed = mutableListOf<String>()
        var subCount = 0
        var consCount = 0
        var skipped = 0

        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            val n = i + 1
            val live = elements().map { it.lowercase() }.toSet()

            // 1) Subconsciente y su elemento ESTÁ en pantalla → tocar en cadena (sin LLM).
            if (step.hasTarget() && present(step.target, live)) {
                mode?.executing(true)
                if (player.tapLabel(step.target)) {
                    subCount++
                    log.log("workflow", "  $n/${steps.size} 🧩 subconsciente \"${step.target}\"")
                } else {
                    failed += "step $n (${step.action})"
                    log.log("workflow", "  $n/${steps.size} \"${step.target}\" estaba pero no se pudo tocar")
                }
                i++
                delay(stepDelay())
                continue
            }

            // 2) Su elemento NO está: ¿el estado ya avanzó? Si un paso POSTERIOR ya está en pantalla,
            //    saltamos los pasos intermedios (ya cumplidos) y encadenamos desde ahí.
            if (step.hasTarget()) {
                val jump = (i + 1 until steps.size).firstOrNull {
                    steps[it].hasTarget() && present(steps[it].target, live)
                }
                if (jump != null) {
                    skipped += jump - i
                    log.log("workflow", "  ⏭ salto ${jump - i} paso(s) ya cumplido(s) (el estado avanzó a \"${steps[jump].target}\")")
                    i = jump
                    continue
                }
            }

            // 3) Ni este ni un paso posterior aparecen (o es un paso consciente): mini-motor Gemini.
            mode?.executing(false)
            val ok = runCatching { conscious(workflow, step, context) }
                .getOrElse { log.log("workflow", "  $n consciente falló: ${it.message}"); false }
            if (ok) {
                consCount++
                log.log("workflow", "  $n/${steps.size} 👁 consciente: ${step.action}")
            } else {
                failed += "step $n (${step.action})"
            }
            i++
            delay(stepDelay())
        }

        val detail = buildString {
            append("workflow \"${workflow.name}\": $subCount subconscientes + $consCount conscientes")
            if (skipped > 0) append(" · $skipped ya cumplidos")
            if (failed.isNotEmpty()) append(" · fallaron: ${failed.joinToString(", ")}")
        }
        log.log("workflow", "■ $detail")
        return WorkflowOutcome(failed.isEmpty(), detail)
    }
}
