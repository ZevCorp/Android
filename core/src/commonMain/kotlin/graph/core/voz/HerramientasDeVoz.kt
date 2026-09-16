package graph.core.voz

import graph.core.domain.McpTool
import graph.core.graph.TurnScreenState
import kotlin.coroutines.CoroutineContext

/**
 * LAS MANOS QUE TODAVÍA NO SON MANOS (docs/specs/002, fase 2B2a): lo que la conversación ejecuta cuando el delegado
 * pide una herramienta. Solo sabe mirar.
 *
 * NO RECIBE MANOS, y por eso no puede tocar aunque alguien se lo pida: ni teléfono, ni gestos, ni sistema, ni
 * reproductor. Solo el estado de la pantalla y el catálogo de acciones, los dos de solo lectura. Lo que pediría
 * ejecutar se contesta [CatalogoDeVoz.TODAVIA_NO]; ejecutar de verdad es la fase siguiente, y entrará por la puerta
 * única (spec 003), no por aquí.
 *
 * AL LOG SOLO VAN MEDIDAS: cuántas etiquetas, cuántas herramientas, cuántos caracteres. Una etiqueta es lo que la
 * pantalla muestra, un filtro es lo que alguien buscó, y el log acaba en la telemetría remota (spec 005, promesa 245).
 * Por eso tampoco se escribe el nombre que pidió el delegado cuando no se reconoce: puede ser cualquier cosa.
 */
class HerramientasDeVoz(
    /** El estado de la pantalla, el mismo del turno de Graph. `null` si no hay servicio de accesibilidad. */
    private val pantalla: suspend () -> TurnScreenState?,
    /** El catálogo real de acciones, tal como lo vería una corrida. `null` si no hay servicio de accesibilidad. */
    private val acciones: suspend () -> List<McpTool>?,
    /**
     * DÓNDE SE MIRA, y nunca es el hilo de la conversación: sin default, para que ningún sitio se olvide de decirlo.
     * En el teléfono leer la pantalla recorre el árbol de accesibilidad con IPC binder SÍNCRONO, que bloquea el hilo en
     * vez de suspenderlo; en el hilo único de [ConversacionViva] eso para también el audio que llega y el micrófono.
     */
    private val mirarEn: CoroutineContext,
    /** Lo que se espera a una mirada antes de contestar que no se pudo. */
    private val topeDeLaMirada: Long = TOPE_DE_LA_MIRADA_MS,
    private val log: (tag: String, mensaje: String) -> Unit = { _, _ -> },
) {

    companion object {
        const val TAG = "voz-ojos"

        /**
         * 2 500 ms. Es el tope de una CONVERSACIÓN HABLADA, no el de una operación: mientras la lectura no vuelve, el
         * delegado no tiene salida y la voz está muda, y un silencio de más de dos segundos y medio ya se lee como que
         * se colgó. Por arriba de lo que tarda un árbol normal (decenas de ms; una lista larga, cientos), así que solo
         * lo cruza una pantalla patológica o un servicio colgado —y entonces vale más decirlo que esperar.
         */
        const val TOPE_DE_LA_MIRADA_MS = 2_500L
    }

    /** Ejecuta lo que pidió el delegado. Solo lee: lo que no se sabe hacer se contesta, no se intenta. */
    suspend fun ejecutar(llamada: Llamada): String = when (llamada.nombre) {
        CatalogoDeVoz.DONDE_ESTOY -> {
            val respuesta = OjosDeLaVoz.dondeEstoy(pantalla())
            log(TAG, "${CatalogoDeVoz.DONDE_ESTOY} · respuesta de ${respuesta.length} caracteres")
            respuesta
        }

        CatalogoDeVoz.QUE_VEO -> {
            val estado = pantalla()
            val filtro = llamada.args[CatalogoDeVoz.FILTRO].orEmpty().trim()
            val respuesta = OjosDeLaVoz.queVeo(estado, filtro)
            log(
                TAG,
                "${CatalogoDeVoz.QUE_VEO} · filtro de ${filtro.length} caracteres · ${OjosDeLaVoz.etiquetas(estado)} etiquetas" +
                    " · respuesta de ${respuesta.length} caracteres",
            )
            respuesta
        }

        CatalogoDeVoz.QUE_PUEDO_HACER -> {
            val catalogo = acciones().orEmpty()
            val respuesta = CatalogoDeVoz.capacidades(catalogo)
            log(TAG, "${CatalogoDeVoz.QUE_PUEDO_HACER} · ${catalogo.size} herramientas · respuesta de ${respuesta.length} caracteres")
            respuesta
        }

        else -> {
            log(TAG, "todavía no se ejecuta · nombre de ${llamada.nombre.length} caracteres")
            CatalogoDeVoz.TODAVIA_NO
        }
    }
}
