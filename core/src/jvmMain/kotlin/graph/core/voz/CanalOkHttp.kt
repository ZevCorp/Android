package graph.core.voz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/**
 * EL SOCKET DE LA VOZ SOBRE OKHTTP (docs/specs/002, fase B1a). Traduce lo que hace el WebSocket a lo que la conversación
 * tiene que poder distinguir: un apretón de manos que dice que no ([Apertura.Rechazo], con su HTTP), la falta de red
 * ([Apertura.SinRed]) y un cierre con trama o sin ella ([Recibido.Cierre.porRed]).
 *
 * UNA APERTURA, UN CLIENTE. La conversación reabre sobre el mismo canal, y el despachador de un `OkHttpClient` apagado no
 * vuelve a arrancar: por eso cada [abrir] arma el suyo y cerrar o caer lo apaga, despachador y pool. No queda ningún hilo
 * de OkHttp esperando sus 60 s, y el canal no le pide a nadie que lo libere. El lector sale cuando el servidor contesta la
 * trama de cierre; si no la contesta, OkHttp suelta el socket a los 60 s.
 *
 * LO RECIBIDO VA A UN BUZÓN SIN LÍMITE, en el orden del hilo lector. Sin límite a propósito: tirar un mensaje del protocolo es
 * perder una llamada o un error, y el único lector es la conversación, que no se para. Una espera cancelada en [recibir] no
 * se lleva nada: lo que ya llegó sigue en el buzón para la siguiente.
 *
 * AL LOG, NI LAS CABECERAS NI EL CONTENIDO DE LAS TRAMAS: el tipo de lo que pasó, códigos, cuántos y cuántos bytes.
 */
class CanalOkHttp(
    /** Lo que puede tardar abrir, apretón de manos incluido. Pasado, es falta de red. */
    private val tiempoDeConexionMs: Long = 10_000,
    private val log: (tag: String, mensaje: String) -> Unit = { _, _ -> },
) : CanalDeVoz {

    companion object {
        const val TAG = "voz-canal"

        /** La trama de cierre normal, la que manda [cerrar]. */
        const val CIERRE_NORMAL = 1000

        /** La que no viaja nunca: el socket murió sin trama de cierre (RFC 6455, 7.4.1). */
        const val CIERRE_SIN_TRAMA = 1006

        /** El código de error que GPT-Live pone en la respuesta de un apretón de manos rechazado. */
        const val CABECERA_DE_ERROR = "x-openai-ide-error-code"

        private const val LARGO_DEL_MOTIVO = 120

        /** El motivo de una trama de cierre no pasa de 123 bytes; uno más largo haría lanzar a OkHttp. */
        private const val BYTES_DEL_MOTIVO_DE_CIERRE = 123
    }

    @Volatile
    private var enlace: Enlace? = null

    override suspend fun abrir(url: String, cabeceras: Map<String, String>): Apertura {
        enlace?.soltar()
        val peticion = peticion(url, cabeceras)
        val cliente = OkHttpClient.Builder()
            .connectTimeout(tiempoDeConexionMs, TimeUnit.MILLISECONDS)
            // UNA REDIRECCIÓN TAMBIÉN ES UN NO: seguirla abriría la voz en otro sitio y el 3xx no llegaría nunca como rechazo.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val nuevo = Enlace(cliente, url, cabeceras.values)
        enlace = nuevo
        log(TAG, "abriendo el socket con ${cabeceras.size} cabecera(s)")
        nuevo.socket = cliente.newWebSocket(peticion, nuevo)
        val apertura = try {
            withTimeoutOrNull(tiempoDeConexionMs) { nuevo.apertura.await() }
        } catch (e: CancellationException) {
            nuevo.soltar()
            throw e
        }
        if (apertura == null) {
            nuevo.soltar()
            log(TAG, "sin red al abrir: pasaron $tiempoDeConexionMs ms sin apretón de manos")
            return Apertura.SinRed("tiempo agotado al abrir ($tiempoDeConexionMs ms)")
        }
        return apertura
    }

    override suspend fun enviar(texto: String) {
        val actual = enlace
        if (actual == null || !actual.enviar(texto)) {
            log(TAG, "no se pudo enviar un texto de ${texto.encodeToByteArray().size} bytes: el socket está cerrado")
            throw IllegalStateException("el socket de la voz está cerrado")
        }
    }

    override suspend fun recibir(): Recibido =
        (enlace ?: throw IllegalStateException("el socket de la voz no se abrió")).siguiente()

    override fun cerrar(motivo: String) {
        enlace?.cerrar(motivo)
    }

    /**
     * Sin la URL ni los valores en el mensaje: OkHttp los cita al rechazar una URL o una cabecera mal formada, y la
     * conversación deja ese mensaje en el log.
     */
    private fun peticion(url: String, cabeceras: Map<String, String>): Request {
        val constructor = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("la URL de la voz no es válida")
        }
        for ((nombre, valor) in cabeceras) {
            try {
                constructor.header(nombre, valor)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("la cabecera «${nombre.take(40)}» no es válida")
            }
        }
        return constructor.build()
    }

    /** Una apertura y todo lo suyo: su cliente, su socket y su buzón. Lo que llegue de un enlace viejo no toca al nuevo. */
    private inner class Enlace(
        private val cliente: OkHttpClient,
        private val url: String,
        private val secretos: Collection<String>,
    ) : WebSocketListener() {
        val apertura = CompletableDeferred<Apertura>()

        @Volatile
        var socket: WebSocket? = null

        @Volatile
        private var abierto = false
        private val cerrado = AtomicBoolean()
        private val apagado = AtomicBoolean()

        private val buzon = ArrayDeque<Recibido>()
        private var fin: Recibido.Cierre? = null

        /** Solo despierta: el mensaje vive en [buzon], así que una espera cancelada no se lo lleva. */
        private val aviso = Channel<Unit>(Channel.CONFLATED)

        private val recibidos = AtomicInteger()
        private val enviados = AtomicInteger()

        fun enviar(texto: String): Boolean {
            val ws = socket ?: return false
            if (!abierto || synchronized(buzon) { fin != null } || !ws.send(texto)) return false
            enviados.incrementAndGet()
            return true
        }

        /** Lanza si la apertura acabó sin abrir: esperar ahí sería esperar para siempre. */
        suspend fun siguiente(): Recibido {
            while (true) {
                synchronized(buzon) {
                    buzon.removeFirstOrNull()?.let { return it }
                    fin?.let { return it }
                }
                if (apertura.isCompleted && !abierto) throw IllegalStateException("el socket de la voz no llegó a abrir")
                aviso.receive()
            }
        }

        fun cerrar(motivo: String) {
            if (!cerrado.compareAndSet(false, true)) return
            val ws = socket
            if (abierto && ws != null) {
                ws.close(CIERRE_NORMAL, motivo.takeIf { it.encodeToByteArray().size <= BYTES_DEL_MOTIVO_DE_CIERRE })
            } else {
                ws?.cancel()
            }
            if (entregar(Recibido.Cierre(CIERRE_NORMAL, motivo, porRed = false))) {
                log(TAG, "cerrado por la voz ($CIERRE_NORMAL) tras ${recibidos.get()} recibido(s) y ${enviados.get()} enviado(s)")
            }
            apagar()
        }

        /** Sin trama y sin log: lo suelta quien abre otro, o quien se cansó de esperar el apretón de manos. */
        fun soltar() {
            cerrado.set(true)
            socket?.cancel()
            entregar(Recibido.Cierre(CIERRE_SIN_TRAMA, "soltado", porRed = true))
            apagar()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            abierto = true
            if (apertura.complete(Apertura.Ok)) log(TAG, "socket abierto (HTTP ${response.code})")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (entregar(Recibido.Mensaje(text))) recibidos.incrementAndGet()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            log(TAG, "llegó un binario de ${bytes.size} bytes: la voz solo habla texto, se ignora")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (entregar(Recibido.Cierre(code, reason, porRed = false))) {
                log(TAG, "el servidor cerró con $code (motivo de ${reason.encodeToByteArray().size} bytes) tras ${recibidos.get()} recibido(s) y ${enviados.get()} enviado(s)")
            }
            // Se contesta la trama: sin respuesta, OkHttp deja el socket abierto hasta que lo cancela a los 60 s.
            webSocket.close(CIERRE_NORMAL, null)
            apagar()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            entregar(Recibido.Cierre(code, reason, porRed = false))
            apagar()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            when {
                // EL SERVIDOR CONTESTÓ Y DIJO QUE NO: es un rechazo con su HTTP, nunca la red. En U, este 401 se reintentaba.
                response != null -> {
                    val rechazo = Apertura.Rechazo(response.code, response.header(CABECERA_DE_ERROR))
                    if (apertura.complete(rechazo)) {
                        log(TAG, "el apretón de manos no pasó: HTTP ${response.code}" + if (rechazo.codigoCabecera != null) " con código de error" else "")
                    }
                }

                !apertura.isCompleted -> {
                    // TLS roto o un servidor que no habla HTTP no se arreglan reintentando: lanzan desde abrir, y la
                    // conversación no los disfraza de «revisa tu internet».
                    if (t is SSLException || t is ProtocolException || t !is IOException) {
                        if (apertura.completeExceptionally(IOException(motivo(t)))) log(TAG, "no se pudo abrir y no es la red: ${tipo(t)}")
                    } else if (apertura.complete(Apertura.SinRed(motivo(t)))) {
                        log(TAG, "sin red al abrir: ${tipo(t)}")
                    }
                }

                else -> if (entregar(Recibido.Cierre(CIERRE_SIN_TRAMA, motivo(t), porRed = true))) {
                    log(TAG, "se cortó sin trama de cierre (${tipo(t)}) tras ${recibidos.get()} recibido(s) y ${enviados.get()} enviado(s)")
                }
            }
            apagar()
        }

        /** Verdadero si entró. Tras el primer cierre no entra nada más: el cierre es lo último que se lee, y se repite. */
        private fun entregar(r: Recibido): Boolean {
            synchronized(buzon) {
                if (fin != null) return false
                if (r is Recibido.Cierre) fin = r
                buzon.addLast(r)
            }
            aviso.trySend(Unit)
            return true
        }

        /**
         * EL DESPACHADOR Y EL POOL SE APAGAN: sin esto, el hilo «OkHttp Dispatcher» sigue vivo 60 s y el pool guarda la
         * conexión de un apretón rechazado 5 minutos. `shutdown` deja terminar al lector, que sale al cerrarse el socket.
         */
        private fun apagar() {
            if (!apagado.compareAndSet(false, true)) return
            cliente.dispatcher.executorService.shutdown()
            cliente.connectionPool.evictAll()
        }

        /** El tipo y la primera línea del error, sin la URL, sin nada con forma de clave ni lo que se mandó en las cabeceras. */
        private fun motivo(t: Throwable): String {
            var linea = t.message?.lineSequence()?.firstOrNull().orEmpty()
            for (s in secretos + url) if (s.isNotEmpty()) linea = linea.replace(s, "…")
            linea = linea
                .replace(CON_FORMA_DE_URL, "…")
                .replace(CON_FORMA_DE_CLAVE, "…")
                .filterNot { it.isISOControl() }
                .trim()
            if (linea.isEmpty()) return tipo(t)
            val corta = if (linea.length > LARGO_DEL_MOTIVO) linea.take(LARGO_DEL_MOTIVO).trimEnd() + "…" else linea
            return "${tipo(t)}: $corta"
        }
    }
}

private fun tipo(t: Throwable): String = t::class.simpleName ?: "Throwable"

private val CON_FORMA_DE_URL = Regex("(?i)\\b[a-z][a-z0-9+.-]*://\\S*")
private val CON_FORMA_DE_CLAVE = Regex("(?i)\\bbearer\\s+\\S+|\\b(?:sk|rk|pk|ek)-\\S+|[\\p{L}\\p{N}_\\-]{20,}")
