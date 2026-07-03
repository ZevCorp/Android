package graph.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ActionType { LAUNCH, CLICK, INPUT, SCROLL, KEY, WAIT }

@Serializable
enum class StepSource { AGENT, USER_DEMO }

/**
 * Localizador semántico sobre cualquier árbol de UI.
 * Equivalencias por plataforma: DOM (navegador), AXAccessibility (macOS), UIA (Windows), Accessibility (Android).
 * viewId ≈ data-testid/#id · text ≈ label · contentDesc ≈ aria-label · className ≈ tagName · pkg+bounds = fallback.
 */
@Serializable
data class Selector(
    val viewId: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val className: String = "",
    val pkg: String = "",
    val bounds: String = "",
) {
    fun isEmpty() = viewId.isBlank() && text.isBlank() && contentDesc.isBlank() && className.isBlank()
    fun short() = viewId.ifBlank { contentDesc.ifBlank { text.ifBlank { className } } }
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
)

@Serializable
data class Variable(val name: String, val field: String, val default: String)

@Serializable
data class Workflow(
    val id: String,
    val name: String,
    val purpose: String = "",
    val steps: List<Step> = emptyList(),
    val variables: List<Variable> = emptyList(),
) {
    companion object {
        /** Igual que en Graph: cada INPUT se vuelve una variable `input_<order>` parametrizable desde la CLI. */
        fun deriveVariables(steps: List<Step>) = steps
            .filter { it.action == ActionType.INPUT }
            .map { Variable("input_${it.order}", it.label.ifBlank { it.selector.short() }, it.value) }
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
