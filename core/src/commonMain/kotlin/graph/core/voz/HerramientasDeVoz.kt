package graph.core.voz

import graph.core.domain.McpTool
import graph.core.graph.TurnScreenState

/**
 * LAS MANOS QUE TODAVÍA NO SON MANOS (docs/specs/002, fase 2B2a): lo que la conversación ejecuta cuando el delegado
 * pide una herramienta. Solo sabe mirar.
 *
 * NO RECIBE MANOS, y por eso no puede tocar aunque alguien se lo pida: ni teléfono, ni gestos, ni sistema, ni
 * reproductor. Solo el estado de la pantalla y el catálogo de acciones, los dos de solo lectura. Lo que pediría
 * ejecutar se contesta [CatalogoDeVoz.TODAVIA_NO]; ejecutar de verdad es la fase siguiente, y entrará por la puerta
 * única (spec 003), no por aquí.
 *
 * AL LOG SOLO VAN MEDIDAS: cuántas etiquetas, cuántos caracteres. Una etiqueta es lo que la pantalla muestra y el log
 * acaba en la telemetría remota (spec 005, promesa 245).
 */
class HerramientasDeVoz(
    /** El estado de la pantalla, el mismo del turno de Graph. `null` si no hay servicio de accesibilidad. */
    private val pantalla: suspend () -> TurnScreenState?,
    /** El catálogo real de acciones, tal como lo vería una corrida. */
    private val acciones: suspend () -> List<McpTool>,
    private val log: (tag: String, mensaje: String) -> Unit = { _, _ -> },
) {

    companion object {
        const val TAG = "voz-ojos"
    }

    /** Ejecuta lo que pidió el delegado. Nunca lanza: lo que no se sabe hacer se contesta, no revienta la voz. */
    suspend fun ejecutar(llamada: Llamada): String = TODO("2B2a")
}
