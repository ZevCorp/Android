package com.zevcorp.graph.platform

import graph.core.graph.TransportReply
import graph.core.graph.TurnTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * La puerta a la red del cerebro Graph: un POST JSON con `HttpURLConnection`, mismas garantías que
 * `oaHttpOnce` (OpenAiBrain): cancelable de verdad (`disconnect()` al cancelar el Job) y con los
 * timeouts del cliente Windows (30 s para conectar, 5 min para leer: un turno de Graph puede tardar
 * lo que tarde el modelo). No reintenta: eso lo decide `GraphBrain` según el status.
 *
 * Qué vuelve, y por qué importa (spec 001, promesas 13 y 14):
 *  - no conectó (DNS, sin red, conexión agotada o rechazada, URL mal escrita) → `status = 0` con la
 *    causa en el cuerpo: el turno no llegó a Graph y se puede reintentar;
 *  - conectó y la lectura se agotó → `status = -1`: Graph pudo recibir, y cobrar, el turno; no se
 *    reintenta. Los dos timeouts se distinguen por el momento en que saltan: con `connect()`
 *    explícito, el de conexión solo salta ahí dentro (medido en la JVM: «Connect timed out» en
 *    `connect()`, «Read timed out» en `responseCode`);
 *  - cancelar la corrida sale como `CancellationException`, nunca como red caída;
 *  - un HTTP de error trae su cuerpo (errorStream), y `Retry-After` en segundos si vino.
 * Cabeceras: solo las del mapa, nada más; ni ellas ni la key van a un mensaje.
 */
class GraphTransport : TurnTransport {

    override suspend fun post(url: String, body: String, headers: Map<String, String>): TransportReply =
        withContext(Dispatchers.IO) {
            try { once(url, body.toByteArray(Charsets.UTF_8), headers) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { TransportReply(TransportReply.NOT_CONNECTED, causa(e)) }
        }

    private suspend fun once(url: String, body: ByteArray, headers: Map<String, String>): TransportReply =
        suspendCancellableCoroutine { cont ->
            val c = URL(url).openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { c.disconnect() } }
            var connected = false
            val result = try {
                c.requestMethod = "POST"
                c.connectTimeout = CONNECT_TIMEOUT_MS
                c.readTimeout = READ_TIMEOUT_MS
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.doOutput = true
                c.connect() // el connectTimeout salta aquí y solo aquí
                connected = true
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                val retryAfter = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
                c.disconnect()
                Result.success(TransportReply(code, text, retryAfter))
            } catch (e: SocketTimeoutException) {
                // Después de conectar, un timeout es de lectura: el turno pudo haberse cobrado.
                if (connected) Result.success(TransportReply(TransportReply.TIMED_OUT, causa(e))) else Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (cont.isActive) cont.resumeWith(result)
        }

    /** La causa en una línea: tipo y mensaje de la excepción (`MalformedURLException: no protocol: …`). */
    private fun causa(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 300_000
    }
}
