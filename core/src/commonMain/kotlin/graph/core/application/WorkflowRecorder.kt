package graph.core.application

import graph.core.domain.GraphLog
import graph.core.domain.RawStep
import graph.core.domain.WorkflowSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val NO_LOG = GraphLog { _, _ -> }

/**
 * GRABADORA DE WORKFLOWS. Ambos modos de enseñanza son el punto de creación de workflows: mientras
 * el asistente ve explícitamente el paso a paso, aquí se va guardando el workflow step by step (la
 * unidad es el clic sobre un elemento del árbol de UI, con o sin etiqueta detectada).
 *
 * El cierre de la enseñanza dispara `onTrace` con la traza cruda para el post-procesamiento LLM:
 * - PASIVA: al salir el usuario de la app (cada app es su propia traza) y al apagar el modo.
 * - ACTIVA: al terminar la grabación de pantalla (una sola traza, puede cruzar apps).
 */
class WorkflowRecorder(
    private val log: GraphLog = NO_LOG,
    /** Fire-and-forget: la plataforma lanza el post-procesamiento async (LLM) y guarda el workflow. */
    private val onTrace: (WorkflowSource, List<RawStep>) -> Unit,
) {
    @Volatile var active = false
        private set

    private val mutex = Mutex()
    private var source = WorkflowSource.PASSIVE
    private val steps = mutableListOf<RawStep>()

    suspend fun start(mode: WorkflowSource) {
        mutex.withLock {
            if (active) flush() // enseñanzas encadenadas sin stop: no se mezclan trazas
            active = true
            source = mode
            log.log("workflow", "▶ grabando workflow (enseñanza ${mode.id})")
        }
    }

    /** Termina la enseñanza: manda a estructurar lo que haya y apaga la grabadora. */
    suspend fun stop() {
        mutex.withLock {
            if (!active) return
            active = false
            flush()
            log.log("workflow", "■ grabadora de workflows apagada")
        }
    }

    /** Un clic observado. `label` vacío = el árbol de UI no detectó el elemento (será paso consciente). */
    suspend fun record(app: String, screen: String, label: String) {
        if (!active) return
        mutex.withLock {
            if (!active) return
            steps += RawStep(app, screen, label)
            log.log("workflow", "step ${steps.size}: ${label.ifBlank { "(sin etiqueta)" }} en $app")
        }
    }

    /** En enseñanza PASIVA, salir de la app cierra la traza; en ACTIVA el flujo puede cruzar apps. */
    suspend fun appChanged(newApp: String) {
        if (!active) return
        mutex.withLock {
            if (!active || source != WorkflowSource.PASSIVE) return
            if (steps.isNotEmpty() && steps.last().app != newApp) flush()
        }
    }

    /** Entrega la traza al post-procesador y limpia. Llamar con el lock tomado. */
    private fun flush() {
        val trace = steps.toList()
        steps.clear()
        if (trace.size < 2) return // un clic suelto no es un flujo
        log.log("workflow", "traza lista: ${trace.size} steps (${source.id}) → post-procesamiento")
        onTrace(source, trace)
    }
}
