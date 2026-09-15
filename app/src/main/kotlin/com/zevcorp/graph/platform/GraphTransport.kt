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
import kotlin.time.Duration

/**
 * La puerta a la red de Graph: JSON por `HttpURLConnection` (POST del turno; GET, POST, PUT y DELETE del
 * aprendizaje, spec 004), mismas garantías que
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
        send("POST", url, body, headers)

    /**
     * Cualquier método: GET y DELETE sin cuerpo, POST y PUT con JSON. [timeout] es el tope de lectura de
     * esta llamada (el cliente de aprendizaje manda lo que le queda a su tope); sin él, los 5 min del turno.
     */
    override suspend fun send(method: String, url: String, body: String?, headers: Map<String, String>, timeout: Duration?): TransportReply =
        withContext(Dispatchers.IO) {
            try { once(method, url, body?.toByteArray(Charsets.UTF_8), headers, lectura(timeout)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { TransportReply(TransportReply.NOT_CONNECTED, causa(e)) }
        }

    private suspend fun once(method: String, url: String, body: ByteArray?, headers: Map<String, String>, readTimeoutMs: Int): TransportReply =
        suspendCancellableCoroutine { cont ->
            val c = URL(url).openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { c.disconnect() } }
            var connected = false
            val result = try {
                c.requestMethod = method
                c.connectTimeout = CONNECT_TIMEOUT_MS
                c.readTimeout = readTimeoutMs
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.doOutput = body != null
                c.connect() // el connectTimeout salta aquí y solo aquí
                connected = true
                if (body != null) c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                val retryAfter = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
                Result.success(TransportReply(code, text, retryAfter))
            } catch (e: SocketTimeoutException) {
                // Después de conectar, un timeout es de lectura: el turno pudo haberse cobrado.
                if (connected) Result.success(TransportReply(TransportReply.TIMED_OUT, causa(e))) else Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                // Toda salida suelta el socket: desde la promesa 13 una lectura agotada (-1) es un caso
                // esperado y no puede dejarlo colgado. La conexión no se reutiliza; si ya la cortó
                // `invokeOnCancellation`, un segundo disconnect() es inocuo.
                runCatching { c.disconnect() }
            }
            if (cont.isActive) cont.resumeWith(result)
        }

    /** El tope de lectura en ms: el de la llamada, entre 1 ms (0 sería «sin tope») y los 5 min del turno. */
    private fun lectura(timeout: Duration?): Int =
        timeout?.inWholeMilliseconds?.coerceIn(1L, READ_TIMEOUT_MS.toLong())?.toInt() ?: READ_TIMEOUT_MS

    /** La causa en una línea: tipo y mensaje de la excepción (`MalformedURLException: no protocol: …`). */
    private fun causa(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 300_000
    }
}
