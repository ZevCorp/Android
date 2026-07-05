package graph.core.domain

import kotlinx.serialization.Serializable

/**
 * Una herramienta MCP APRENDIDA: el MAPA de una pantalla/app estructurado por el asistente durante
 * una sesión de enseñanza. No guarda los clics del usuario (esos fueron solo señales): guarda el
 * CATÁLOGO de elementos utilizables y su documentación. En ejecución, el modelo compone la secuencia
 * que necesite con esos elementos (p.ej. taps="5,+,7,=") y se reproduce por árbol de UI, sin imagen.
 */
@Serializable
data class LearnedTool(
    val name: String,
    val description: String, // documentación: qué hace la app, qué es cada grupo de elementos, cómo componer
    val elements: List<String> = emptyList(), // etiquetas EXACTAS utilizables del árbol de UI
)

/** Lo que el cerebro decide tras cada señal del usuario (voz y/o clics) durante la enseñanza. */
class TeachTurn(
    val say: String = "",
    /** Elementos a iluminar en secuencia (mostrar que entendió, o acompañar una pregunta). */
    val highlight: List<String> = emptyList(),
    /** Pregunta de sí/no para el usuario (se ilumina `highlight` mientras se pregunta). */
    val question: String? = null,
    /** Secuencia de prueba que propone ejecutar (con permiso) para validar lo entendido. */
    val test: List<String> = emptyList(),
)

/**
 * El cerebro de aprendizaje: recibe TODO el árbol de UI + la transcripción (voz, clics, respuestas)
 * y estructura el mapa MCP de la pantalla. Los clics del usuario son señales para generalizar.
 */
interface LearningBrain {
    suspend fun step(transcript: String, screen: String, elements: List<String>): TeachTurn
    /** Al terminar: organiza todo en una herramienta MCP documentada (catálogo + cómo usarlo). */
    suspend fun consolidate(transcript: String, screen: String, elements: List<String>): LearnedTool
}

/** Ejecuta un toque sobre un elemento por su etiqueta (ejecución de herramientas aprendidas). */
interface UiPlayer {
    suspend fun tapLabel(label: String): Boolean
}

/** Superficie de aprendizaje: leer el árbol, capturar los clics-señal del usuario, iluminar y tocar. */
interface LearningSurface : UiPlayer {
    suspend fun screen(): String
    suspend fun elements(): List<String>
    /** Ilumina en secuencia los elementos (por etiqueta): el "mira, entendí esto". */
    suspend fun highlight(labels: List<String>)
    /** Captura de clics del usuario (señales) durante la sesión. */
    fun setCapturing(enabled: Boolean)
    /** Devuelve y vacía las etiquetas que el usuario tocó desde la última llamada. */
    fun drainClicks(): List<String>
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
