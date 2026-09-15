package graph.core.graph

import kotlin.time.Duration

/**
 * Lo que volvió del servidor. Además de los HTTP, dos status propios:
 *  - [NOT_CONNECTED] (0): no conectó (sin red, DNS, conexión agotada, URL mal escrita). El turno no
 *    llegó a Graph: se puede reintentar. El cuerpo lleva la causa.
 *  - [TIMED_OUT] (-1): conectó y Graph no respondió a tiempo. Pudo haber recibido, y cobrado, el
 *    turno: no se reintenta (spec 001, promesa 13).
 * [retryAfterSeconds] = la cabecera `Retry-After` en segundos, si vino.
 */
class TransportReply(val status: Int, val body: String, val retryAfterSeconds: Int? = null) {
    companion object {
        const val NOT_CONNECTED = 0
        const val TIMED_OUT = -1
    }
}

/**
 * La única puerta a la red del cerebro Graph. `core` no sabe de HTTP: la app la implementa con
 * `HttpURLConnection` (fase B) y los tests con un guion. Una falla de red se devuelve como
 * `status = 0` o `-1` (ver [TransportReply]), no como excepción. La única excepción que debe salir
 * es la `CancellationException` de cancelar la corrida.
 */
interface TurnTransport {
    /** POST de [body] (JSON) a la [url] completa con estas [headers]. */
    suspend fun post(url: String, body: String, headers: Map<String, String>): TransportReply

    /**
     * Cualquier llamada: [method] `GET`, `POST`, `PUT` o `DELETE`; [body] JSON, o null sin cuerpo;
     * [timeout] el tope de lectura de ESTA llamada (null = el del transporte). Mismas garantías que
     * [post]. Por defecto solo sabe POST, que delega en [post]: un transporte que solo habla con el
     * turno (spec 001) no tiene que cambiar. El aprendizaje (spec 004) necesita el resto.
     */
    suspend fun send(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeout: Duration? = null,
    ): TransportReply = TODO()
}
