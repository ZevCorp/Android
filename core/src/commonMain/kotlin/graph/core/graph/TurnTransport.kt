package graph.core.graph

/** Lo que volvió del servidor. `status` 0 = no hubo respuesta (sin red, timeout, DNS). */
class TransportReply(val status: Int, val body: String)

/**
 * La única puerta a la red del cerebro Graph. `core` no sabe de HTTP: la app la implementa con
 * `HttpURLConnection` (fase B) y los tests con un guion. Una falla de red se devuelve como
 * `status = 0`, no como excepción: así el cerebro la trata como transitoria y reintenta.
 */
interface TurnTransport {
    /** POST de [body] (JSON) a la [url] completa con estas [headers]. */
    suspend fun post(url: String, body: String, headers: Map<String, String>): TransportReply
}
