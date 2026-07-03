package graph.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ActionType { LAUNCH, CLICK, INPUT, SCROLL, KEY, WAIT }

@Serializable
enum class StepSource { AGENT, USER_DEMO }

/**
 * Estado de consolidación de cada step — la "red neuronal" del workflow:
 * DRAFT     capturado (del video o de un learning libre), aún sin confirmar
 * CONFIRMED aprendido: se ejecuta por árbol de UI, validado por el supervisor (verde)
 * LLM       no se logró consolidar: lo ejecuta Gemini computer use (rojo)
 */
@Serializable
enum class StepStatus { DRAFT, CONFIRMED, LLM }

/**
 * Localizador semántico sobre cualquier árbol de UI.
 * Equivalencias por plataforma: DOM (navegador), AXAccessibility (macOS), UIA (Windows), Accessibility (Android).
 * viewId ≈ data-testid/#id · text ≈ label · contentDesc ≈ aria-label · className ≈ tagName.
 * NUNCA contiene coordenadas: los workflows se localizan y reproducen solo por el árbol de UI.
 */
@Serializable
data class Selector(
    val viewId: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val className: String = "",
    val pkg: String = "",
) {
    fun isEmpty() = viewId.isBlank() && text.isBlank() && contentDesc.isBlank() && className.isBlank()
    fun short() = viewId.ifBlank { contentDesc.ifBlank { text.ifBlank { className } } }
    /** Reproducible sin LLM: tiene un localizador semántico estable (no solo coordenadas). */
    fun solid() = viewId.isNotBlank() || contentDesc.isNotBlank() || text.isNotBlank()
}

@Serializable
data class Step(
    val order: Int,
    val action: ActionType,
    val selector: Selector = Selector(),
    val value: String = "",
    val label: String = "",
    val screen: String = "",
    val source: StepSource = StepSource.AGENT,
    val status: StepStatus = StepStatus.DRAFT,
    /** "" = tronco (siempre se ejecuta); nombre de rama = solo si la rama se activa (--branch <name>). */
    val branch: String = "",
    /**
     * Elementos "paralelos"/variantes del mismo tipo vistos en el mismo contenedor al grabar
     * (como los alternativeTargets de Graph): los otros números de la calculadora, los otros
     * sabores de pizza… Si no está vacío, este CLICK es un punto de selección: se puede pedir
     * otra de estas etiquetas y se ejecuta con la misma velocidad aprendida.
     */
    val peers: List<String> = emptyList(),
)

/** Rama opcional/situacional del workflow (bifurcación que se reincorpora al tronco). */
@Serializable
data class Branch(val name: String, val description: String = "")

/** Variable de la CLI: si `options` no está vacío es una SELECCIÓN (pick) entre paralelos; si no, texto libre (input). */
@Serializable
data class Variable(
    val name: String,
    val field: String,
    val default: String,
    val options: List<String> = emptyList(),
)

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val purpose: String = "",
    val lessonId: String = "",
    val steps: List<Step> = emptyList(),
    val variables: List<Variable> = emptyList(),
    val branches: List<Branch> = emptyList(),
    /** Índice del próximo step a ejecutar (para --depth: ejecuciones por tramos). 0 = desde el inicio. */
    val cursor: Int = 0,
) {
    /** Firma de uso en terminal, p.ej.: graph run wf_x [--branch configurar_direccion] --depth 5 --input_3="..." */
    fun usage() = "graph run $id" +
        branches.joinToString("") { " [--branch ${it.name}]" } +
        " [--depth N]" +
        variables.joinToString("") { " --${it.name}=\"...\"" }

    /** % consolidado de forma subconsciente (steps verdes sobre el total). */
    fun learnedPct() = if (steps.isEmpty()) 0
    else steps.count { it.status == StepStatus.CONFIRMED } * 100 / steps.size

    companion object {
        /**
         * Variables parametrizables desde la CLI, como en Graph:
         *  - cada INPUT → `input_<order>` (texto libre).
         *  - cada CLICK con paralelos → `pick_<order>` (selección: default = lo aprendido, options = peers).
         */
        fun deriveVariables(steps: List<Step>): List<Variable> = steps.mapNotNull { s ->
            when {
                s.action == ActionType.INPUT ->
                    Variable("input_${s.order}", s.label.ifBlank { s.selector.short() }, s.value)
                s.action == ActionType.CLICK && s.peers.isNotEmpty() -> {
                    val chosen = s.label.ifBlank { s.selector.short() }
                    val options = (listOf(chosen) + s.peers).filter { it.isNotBlank() }.distinct()
                    Variable("pick_${s.order}", chosen, chosen, options)
                }
                else -> null
            }
        }
    }
}

/** Resultado de la etapa de Teaching: lo que el LLM entendió del video-tutorial. */
@Serializable
data class Lesson(
    val id: String,
    val goal: String,
    val app: String = "",
    val summary: String = "",
    val steps: List<String> = emptyList(),
    val videoPath: String = "",
)
