package com.zevcorp.graph.platform

import graph.core.graph.TransportReply
import graph.core.graph.TurnTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * La puerta a la red del cerebro Graph: un POST JSON con `HttpURLConnection`, mismas garantías que
 * `oaHttpOnce` (OpenAiBrain): cancelable de verdad (`disconnect()` al cancelar el Job) y con los
 * timeouts del cliente Windows (30 s para conectar, 5 min para leer: un turno de Graph puede tardar
 * lo que tarde el modelo). No reintenta: eso lo decide `GraphBrain` según el status.
 *
 * Una falla de red (DNS, timeout, sin conexión) vuelve como `status = 0` con el mensaje en el cuerpo,
 * nunca como excepción; un HTTP de error trae su cuerpo (errorStream) para que el cerebro lo lea.
 * Cabeceras: solo las del mapa, nada más.
 */
class GraphTransport : TurnTransport {

    override suspend fun post(url: String, body: String, headers: Map<String, String>): TransportReply =
        withContext(Dispatchers.IO) {
            try { once(url, body.toByteArray(Charsets.UTF_8), headers) }
            catch (e: Exception) { TransportReply(0, e.message ?: "sin red") }
        }

    private suspend fun once(url: String, body: ByteArray, headers: Map<String, String>): TransportReply =
        suspendCancellableCoroutine { cont ->
            val c = URL(url).openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { c.disconnect() } }
            try {
                c.requestMethod = "POST"
                c.connectTimeout = CONNECT_TIMEOUT_MS
                c.readTimeout = READ_TIMEOUT_MS
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.doOutput = true
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                c.disconnect()
                if (cont.isActive) cont.resumeWith(Result.success(TransportReply(code, text)))
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 300_000
    }
}
