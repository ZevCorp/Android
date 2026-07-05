package graph.core.domain

import kotlinx.serialization.Serializable

/**
 * Una herramienta MCP APRENDIDA en una sesión de enseñanza: una secuencia de toques sobre elementos
 * del árbol de UI (identificados por su etiqueta). Es la v1 de los "workflows powered by MCP":
 * el asistente la crea a partir de la explicación del usuario + el árbol de UI, y luego la ejecuta
 * como cualquier otra herramienta MCP.
 */
@Serializable
data class LearnedTool(
    val name: String,
    val description: String,
    val steps: List<String> = emptyList(), // etiquetas de los elementos a tocar, en orden
)

/** Lo que el cerebro propone cuando cree haber entendido una parte de la explicación. */
class Proposal(
    val understood: Boolean,
    val name: String = "",
    val description: String = "",
    val sequence: List<String> = emptyList(),
    val say: String = "",
)

/**
 * El cerebro de aprendizaje: escucha la explicación del usuario, mira el árbol de UI en vivo y
 * organiza todo en una herramienta MCP. Separado del cerebro de ejecución para mantenerlo simple.
 */
interface LearningBrain {
    /** ¿Ya entiendo una secuencia concreta de toques? Propónla, o sigue escuchando (understood=false). */
    suspend fun propose(explanation: String, screen: String, elements: List<String>): Proposal
    /** Al terminar: organiza la explicación + la secuencia confirmada en una herramienta MCP documentada. */
    suspend fun consolidate(explanation: String, sequence: List<String>): LearnedTool
}

/** Ejecuta un toque sobre un elemento por su etiqueta. Compartido por ejecución (MCP aprendido) y aprendizaje. */
interface UiPlayer {
    suspend fun tapLabel(label: String): Boolean
}

/** Superficie de aprendizaje: leer los elementos de la pantalla, iluminarlos en secuencia y tocarlos. */
interface LearningSurface : UiPlayer {
    suspend fun screen(): String
    suspend fun elements(): List<String>
    /** Ilumina en secuencia los elementos (por etiqueta): el "mira cómo lo haría". */
    suspend fun highlight(labels: List<String>)
}

/** El usuario que enseña: explica hablando y confirma con sí/no. */
interface Teacher {
    /** Siguiente explicación hablada del usuario; null si dio por terminada la sesión. */
    suspend fun listen(): String?
    suspend fun confirm(question: String): Boolean
}

/** Persistencia de las herramientas aprendidas. */
interface LearnedToolRepository {
    fun save(tool: LearnedTool)
    fun list(): List<LearnedTool>
}
