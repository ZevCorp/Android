package graph.core.domain

import kotlinx.serialization.Serializable

/**
 * WORKFLOWS: el punto de enlace entre los dos mundos de ejecución. El asistente está inspirado en el
 * ser humano, que ejecuta una misma tarea alternando entre lo CONSCIENTE (mirar y decidir) y lo
 * SUBCONSCIENTE (lo que ya conoce y sale solo). Un workflow es ese enlace: una tarea como flujo de
 * steps concatenados (primero a, después b, después c) donde cada step es o subconsciente (se ejecuta
 * por MCP tocando el árbol de UI aprendido, sin mirar) o consciente (lo ejecuta Gemini con computer-use).
 */

/**
 * Un step de un workflow. La UNIDAD del step es el CLIC sobre un elemento del árbol de UI.
 * - `subconscious=true`: el elemento se detectó bien en la enseñanza → `target` es su etiqueta EXACTA
 *   y el step se ejecuta por MCP (tocar por árbol de UI, sin pantalla).
 * - `subconscious=false`: el árbol de UI no se logró detectar bien → el step queda para ejecutarse de
 *   forma consciente (Gemini mira la pantalla y hace `action`).
 * - `note`: nota de contexto OPCIONAL que el post-procesador decide agregar (no todos los steps la llevan).
 */
@Serializable
data class WorkflowStep(
    val action: String,
    val app: String = "",
    val target: String = "",
    val subconscious: Boolean = false,
    val note: String = "",
)

/** Un workflow: steps que se concatenan para completar una tarea. Nace de la enseñanza (pasiva o activa). */
@Serializable
data class Workflow(
    val name: String,
    val description: String,
    val steps: List<WorkflowStep> = emptyList(),
    /** De qué enseñanza nació: "passive" (uso normal observado) o "active" (grabación de pantalla). */
    val source: String = WorkflowSource.PASSIVE.id,
)

/** El modo de enseñanza en el que se grabó el workflow. */
enum class WorkflowSource(val id: String) {
    /** Enseñanza pasiva: termina cuando el usuario sale de la app. */
    PASSIVE("passive"),

    /** Enseñanza activa: termina cuando el usuario detiene la grabación de pantalla. */
    ACTIVE("active"),
}

/** Un clic crudo capturado durante la enseñanza, en orden. `label` vacío = el árbol no detectó el elemento. */
class RawStep(
    val app: String,
    val screen: String,
    val label: String,
)

/**
 * El POST-PROCESADOR de workflows: al terminar la enseñanza, recibe la traza cruda de clics y la
 * estructura como workflow efectivo: limpia los pasos basura e innecesarios, organiza los necesarios,
 * agrega notas de contexto opcionales donde decida que aportan, y asigna la vía de cada step: MCP
 * (subconsciente) a los que ya están listos por árbol de UI, consciente a los que no. Devuelve null
 * si la traza no completa ninguna tarea que valga la pena guardar.
 */
interface WorkflowBrain {
    suspend fun structure(source: WorkflowSource, steps: List<RawStep>, existing: List<Workflow>): Workflow?
}

/** Persistencia de los workflows aprendidos. */
interface WorkflowRepository {
    fun save(workflow: Workflow)
    fun list(): List<Workflow>
}

/** Resultado de ejecutar un workflow completo: si salió y el detalle legible (para el modelo y el log). */
class WorkflowOutcome(val ok: Boolean, val detail: String)

/** Puerto de ejecución de workflows (lo implementa el WorkflowRunner de application). */
fun interface WorkflowExecutor {
    suspend fun run(workflow: Workflow, context: String): WorkflowOutcome
}
