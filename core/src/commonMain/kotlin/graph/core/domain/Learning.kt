package graph.core.domain

import kotlinx.serialization.Serializable

/**
 * Una herramienta MCP APRENDIDA: el MAPA de una app estructurado por el asistente observando el uso
 * normal del usuario (enseñanza pasiva). No guarda los clics (esos fueron solo señales de valor):
 * guarda el CATÁLOGO de elementos utilizables y su documentación. En ejecución, el modelo compone la
 * secuencia que necesite con esos elementos (p.ej. taps="5,+,7,=") y se reproduce por árbol de UI.
 */
@Serializable
data class LearnedTool(
    val name: String,
    val description: String, // documentación: qué hace la app, qué es cada grupo de elementos, cómo componer
    val elements: List<String> = emptyList(), // etiquetas EXACTAS utilizables del árbol de UI
    /** Paquete Android de la app dueña del mapa: permite refinarlo (no duplicar) y visualizarlo. */
    val app: String = "",
)

/**
 * El cerebro de aprendizaje pasivo: al salir el usuario de una app, recibe los clics que hizo dentro
 * (las señales de lo que le importa) más el catálogo de elementos vistos en el árbol de UI, y decide
 * qué merece guardarse como herramienta MCP.
 */
interface LearningBrain {
    /**
     * Estructura (o refina, si `previous` existe) el mapa MCP de la app. Solo debe incluir
     * aprendizajes con certeza muy alta y valor real según los clics; devuelve null si la señal
     * fue poca o ambigua y no vale la pena guardar nada.
     */
    suspend fun consolidate(
        app: String,
        screen: String,
        clicks: List<String>,
        elements: List<String>,
        previous: LearnedTool?,
    ): LearnedTool?
}

/** Ejecuta un toque sobre un elemento por su etiqueta (ejecución de herramientas aprendidas). */
interface UiPlayer {
    suspend fun tapLabel(label: String): Boolean
}

/** Superficie de aprendizaje: leer el árbol de la pantalla actual y tocar por etiqueta. */
interface LearningSurface : UiPlayer {
    suspend fun screen(): String
    suspend fun elements(): List<String>
}

/** Persistencia de las herramientas aprendidas. */
interface LearnedToolRepository {
    fun save(tool: LearnedTool)
    fun list(): List<LearnedTool>
}
