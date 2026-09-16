package graph.core.pregunta

import graph.core.domain.AgentAction
import graph.core.domain.GraphLog
import graph.core.domain.ScreenState
import graph.core.domain.UserChannel
import graph.core.domain.Voice

/**
 * LO QUE EL CLIENTE PUEDE VER AHORA: las etiquetas de la pantalla y las apps instaladas. Con esto se cuentan los candidatos
 * de un nombre ambiguo: se ofrecen los que vio, nunca una lista inventada (promesa 602).
 */
class Vista(val etiquetas: List<String> = emptyList(), val apps: List<String> = emptyList()) {
    companion object {
        /** Cómo escribe la pantalla sus etiquetas (`GraphAccessibilityService.uiContext`, `app/…:213-214`). */
        const val ETIQUETAS = "etiquetas visibles: "
        const val SEPARADOR = " · "

        fun etiquetasDe(uiContext: String): List<String> = emptyList()
    }
}

/**
 * PREGUNTA ANTES DE EJECUTAR (docs/specs/006). Entre el motor y la acción: lo sensible que el pedido no autorizó no se
 * ejecuta, un destino ambiguo se pregunta con las opciones que vio, y un dato que falta se pide.
 *
 * ESQUELETO (fase E1): las promesas 601-608 se escribieron antes que esta lógica y nacen rojas contra estas firmas.
 */
class CompuertaDePregunta(
    private val usuario: UserChannel? = null,
    private val voz: Voice? = null,
    private val log: GraphLog = GraphLog { _, _ -> },
    private val apps: suspend () -> List<String> = { emptyList() },
) {
    /** La pregunta que está en el aire: mientras existe, la corrida está viva y quieta (promesa 604). */
    val pendiente: PreguntaPendiente? get() = null

    /** Corrida nueva: este es el pedido que manda y el registro de lo ya preguntado se vacía. */
    fun empieza(pedido: String) = Unit

    /** La última pantalla que el motor leyó: de ahí salen las etiquetas que el cliente ve. */
    fun vio(estado: ScreenState) = Unit

    /** `null` si la acción puede ejecutarse; si no, el resultado de una acción que NO se hizo, para el cerebro. */
    suspend fun revisa(accion: AgentAction): String? = null

    companion object {
        /** El tag de la compuerta en el log. De aquí solo sale medida (promesa 608). */
        const val TAG = "pregunta"

        /** Lo que el cerebro lee de una acción frenada. Antes del «—» va la medida; el detalle es para él. */
        const val PREGUNTE = "pregunté primero y no la hice"
        const val DIJISTE_QUE_NO = "dijiste que no"
        const val SIN_CANAL = "no hay a quién preguntarle"

        /** Cuántas opciones se le ofrecen a la persona: más que esto ya no se lee, se adivina. */
        const val OPCIONES_MAXIMAS = 6
    }
}
