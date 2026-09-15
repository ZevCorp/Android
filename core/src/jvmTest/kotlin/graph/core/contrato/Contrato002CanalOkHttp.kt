package graph.core.contrato

import graph.core.contrato.Contrato002VozGptLive.Companion.promesa
import graph.core.voz.Apertura
import graph.core.voz.CanalOkHttp
import graph.core.voz.Recibido
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * EL CANAL REAL SE JUZGA CONTRA UN SERVIDOR QUE NO ES OPENAI (docs/specs/002, fase B1a). MockWebServer contesta el apretón de
 * manos como se le diga y habla WebSocket de verdad por localhost; la caída sin trama de cierre la da un `ServerSocket` a
 * mano, porque MockWebServer no sabe soltar un socket ya promovido. Nada sale de la máquina: abrir contra GPT-Live cuesta
 * plata. Cada espera tiene tope: un canal que se cuelga sale rojo, no deja la corrida colgada.
 */
class Contrato002CanalOkHttp {

    private companion object {
        const val CLAVE = "sk-live-contrato-9f3a7c1e5b2d"
        const val RUTA = "/v1/live/sessions"
        const val TOPE_MS = 5_000L
        const val RAFAGA = 200
        val CABECERAS = mapOf("Authorization" to "Bearer $CLAVE")
        const val GUID_WEBSOCKET = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }

    /** Lo que ve el servidor: los textos que le llegan y las tramas de cierre. Contesta el cierre, como un servidor de verdad. */
    private class Oyente(private val alAbrir: (WebSocket) -> Unit = {}, private val alRecibir: (WebSocket, String) -> Unit = { _, _ -> }) :
        WebSocketListener() {
        val recibidos = LinkedBlockingQueue<String>()
        val cierres = LinkedBlockingQueue<Pair<Int, String>>()

        override fun onOpen(webSocket: WebSocket, response: Response) = alAbrir(webSocket)

        override fun onMessage(webSocket: WebSocket, text: String) {
            recibidos += text
            alRecibir(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            cierres += code to reason
            webSocket.close(1000, null)
        }
    }

    private fun <T> conServidor(bloque: suspend (MockWebServer) -> T): T {
        val servidor = MockWebServer()
        servidor.start(InetAddress.getLoopbackAddress(), 0)
        try {
            return runBlocking { bloque(servidor) }
        } finally {
            servidor.shutdown()
        }
    }

    private fun MockWebServer.ws(): String = url(RUTA).toString().replaceFirst("http", "ws")

    private suspend fun <T : Any> aTiempo(que: String, bloque: suspend () -> T): T =
        withTimeoutOrNull(TOPE_MS) { bloque() } ?: fail("$que no volvió en $TOPE_MS ms")

    /** Un motivo que puede ir al log de la voz: dice algo, y no trae la clave ni la URL. */
    private fun sinSecretos(prefijo: String, texto: String, url: String) {
        assertTrue(texto.isNotBlank(), "$prefijo · motivo vacío")
        for (prohibido in listOf(CLAVE, "Bearer", url, RUTA, "://")) {
            assertFalse(prohibido in texto, "$prefijo · «$texto» trae «$prohibido»")
        }
    }

    /** Los hilos que OkHttp crea para una llamada: el del despachador y el lector, que se renombra con la URL. */
    private fun hilosDeOkHttp(): Set<Thread> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && (it.name == "OkHttp Dispatcher" || it.name.startsWith("OkHttp http") || it.name.startsWith("OkHttp ws")) }
        .toSet()

    private suspend fun hasta(ms: Long, condicion: () -> Boolean): Boolean {
        val fin = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < fin) {
            if (condicion()) return true
            delay(20)
        }
        return condicion()
    }

    /**
     * UN SERVIDOR QUE SE CAE: contesta el 101 con su `Sec-WebSocket-Accept`, manda [mensaje] en una trama de texto sin máscara
     * y suelta el socket sin trama de cierre, como GPT-Live ~2 s después de un error.
     */
    private fun servidorQueSeCae(mensaje: String): ServerSocket {
        val servidor = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true, name = "servidor-que-se-cae") {
            servidor.accept().use { s ->
                val entrada = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                var clave = ""
                while (true) {
                    val linea = entrada.readLine() ?: return@use
                    if (linea.isEmpty()) break
                    if (linea.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) clave = linea.substringAfter(':').trim()
                }
                val acepta = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((clave + GUID_WEBSOCKET).toByteArray()))
                val carga = mensaje.toByteArray()
                require(carga.size < 126)
                val salida = s.getOutputStream()
                salida.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acepta\r\n\r\n".toByteArray())
                salida.write(byteArrayOf(0x81.toByte(), carga.size.toByte()) + carga)
                salida.flush()
                Thread.sleep(100)
            }
            servidor.close()
        }
        return servidor
    }

    @Test
    fun promesa239() = conServidor { s ->
        val p = promesa(239)
        s.enqueue(MockResponse().setResponseCode(401).setHeader("x-openai-ide-error-code", "invalid_api_key").setBody("""{"error":"invalid_api_key"}"""))
        s.enqueue(MockResponse().setResponseCode(403))
        s.enqueue(MockResponse().setResponseCode(200).setBody("hola"))
        // Si el canal siguiera la redirección, detrás espera un upgrade que sí abre.
        s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", s.url("/otra").toString()))
        s.enqueue(MockResponse().withWebSocketUpgrade(Oyente()))
        val canal = CanalOkHttp()
        try {
            assertEquals(Apertura.Rechazo(401, "invalid_api_key"), aTiempo("abrir con 401") { canal.abrir(s.ws(), CABECERAS) }, "$p · 401")
            assertEquals(Apertura.Rechazo(403, null), aTiempo("abrir con 403") { canal.abrir(s.ws(), CABECERAS) }, "$p · 403 sin cabecera")
            assertEquals(Apertura.Rechazo(200, null), aTiempo("abrir con 200") { canal.abrir(s.ws(), CABECERAS) }, "$p · 200 sin upgrade")
            assertEquals(Apertura.Rechazo(302, null), aTiempo("abrir con 302") { canal.abrir(s.ws(), CABECERAS) }, "$p · una redirección no se sigue")
            assertEquals(4, s.requestCount, "$p · el canal reintentó o siguió la redirección por dentro")
        } finally {
            canal.cerrar("fin")
        }
    }

    @Test
    fun promesa240() {
        val p = promesa(240)
        val puerto = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val cerrado = "ws://127.0.0.1:$puerto$RUTA"
        runBlocking {
            val canal = CanalOkHttp()
            val r = aTiempo("abrir contra un puerto cerrado") { canal.abrir(cerrado, CABECERAS) }
            assertIs<Apertura.SinRed>(r, "$p · puerto cerrado: $r")
            sinSecretos("$p · puerto cerrado", r.motivo, cerrado)
            canal.cerrar("fin")
        }
        conServidor { s ->
            s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val canal = CanalOkHttp(tiempoDeConexionMs = 300)
            val desde = System.nanoTime()
            val r = aTiempo("abrir contra un servidor mudo") { canal.abrir(s.ws(), CABECERAS) }
            val ms = (System.nanoTime() - desde) / 1_000_000
            assertIs<Apertura.SinRed>(r, "$p · servidor mudo: $r")
            assertTrue(ms < 3_000, "$p · con un tope de 300 ms, abrir tardó $ms ms")
            sinSecretos("$p · servidor mudo", r.motivo, s.ws())
            canal.cerrar("fin")
        }
    }

    @Test
    fun promesa241() = conServidor { s ->
        val p = promesa(241)
        val oyente = Oyente(
            alAbrir = { ws -> repeat(RAFAGA) { ws.send("señal $it ü") } },
            alRecibir = { ws, texto ->
                when (texto) {
                    "¿sigues?" -> ws.send("sigo")
                    "ráfaga" -> repeat(RAFAGA) { ws.send("r$it") }
                }
            },
        )
        s.enqueue(MockResponse().withWebSocketUpgrade(oyente))
        val canal = CanalOkHttp()
        val fuera = CoroutineScope(Dispatchers.Default)
        try {
            assertEquals(Apertura.Ok, aTiempo("abrir") { canal.abrir(s.ws(), CABECERAS) }, p)

            // Enviar sin haber leído nada: lo del servidor espera en el canal y lo propio sale igual.
            aTiempo("enviar sin leer") { repeat(RAFAGA) { canal.enviar("c$it") } }
            val propios = List(RAFAGA) { oyente.recibidos.poll(TOPE_MS, TimeUnit.MILLISECONDS) }
            assertEquals(List(RAFAGA) { "c$it" }, propios, "$p · lo enviado sin leer no llegó entero y en orden")
            val suyos = aTiempo("recibir la ráfaga de apertura") { List(RAFAGA) { canal.recibir() } }
            assertEquals(List<Recibido>(RAFAGA) { Recibido.Mensaje("señal $it ü") }, suyos, "$p · lo del servidor no llegó entero y en orden")

            // Una escucha que ya espera no frena a quien manda.
            val escucha = fuera.async { canal.recibir() }
            delay(150)
            aTiempo("enviar con una escucha esperando") { canal.enviar("¿sigues?") }
            assertEquals("¿sigues?", oyente.recibidos.poll(TOPE_MS, TimeUnit.MILLISECONDS), "$p · el envío esperó a la escucha")
            assertEquals(Recibido.Mensaje("sigo"), aTiempo("la escucha que esperaba") { escucha.await() }, p)

            // Esperas que se cancelan en plena llegada no se llevan ningún mensaje. Lo leído se anota DENTRO de la corrutina,
            // sin suspender entre recibir y anotar: con `withTimeoutOrNull` el tope puede vencer con el mensaje ya devuelto y
            // tirarlo, y eso lo perdería el juez, no el canal.
            canal.enviar("ráfaga")
            val leidos: MutableList<Recibido> = Collections.synchronizedList(mutableListOf())
            val fin = System.nanoTime() + TOPE_MS * 1_000_000
            while (leidos.size < RAFAGA && System.nanoTime() < fin) {
                val espera = fuera.launch { leidos += canal.recibir() }
                delay(1)
                espera.cancelAndJoin()
            }
            assertEquals(List<Recibido>(RAFAGA) { Recibido.Mensaje("r$it") }, synchronized(leidos) { leidos.toList() }, "$p · con recibir cancelado a mitad se perdió o se desordenó algo")
        } finally {
            fuera.cancel()
            canal.cerrar("fin")
        }
    }

    @Test
    fun promesa242() {
        val p = promesa(242)
        conServidor { s ->
            val motivo = "invalid_request_error.response_input_buffer_full"
            s.enqueue(MockResponse().withWebSocketUpgrade(Oyente(alAbrir = { ws ->
                ws.send("""{"type":"error"}""")
                ws.close(4000, motivo)
            })))
            val canal = CanalOkHttp()
            try {
                assertEquals(Apertura.Ok, aTiempo("abrir") { canal.abrir(s.ws(), CABECERAS) }, p)
                assertEquals(Recibido.Mensaje("""{"type":"error"}"""), aTiempo("el mensaje antes del cierre") { canal.recibir() }, p)
                val cierre = Recibido.Cierre(4000, motivo, porRed = false)
                assertEquals(cierre, aTiempo("el cierre del servidor") { canal.recibir() }, "$p · cierre del servidor")
                assertEquals(cierre, aTiempo("recibir tras el cierre") { canal.recibir() }, "$p · recibir tras el cierre repite el cierre")
                val envio = aTiempo<Result<Unit>>("enviar tras el cierre") { runCatching { canal.enviar("tarde") } }
                assertTrue(envio.isFailure, "$p · enviar a un socket cerrado no dijo nada")
            } finally {
                canal.cerrar("fin")
            }
        }
        runBlocking {
            val servidor = servidorQueSeCae("antes de caer")
            val url = "ws://127.0.0.1:${servidor.localPort}$RUTA"
            val canal = CanalOkHttp()
            try {
                assertEquals(Apertura.Ok, aTiempo("abrir contra el que se cae") { canal.abrir(url, CABECERAS) }, p)
                assertEquals(Recibido.Mensaje("antes de caer"), aTiempo("el mensaje antes de caer") { canal.recibir() }, p)
                val caida = aTiempo("la caída") { canal.recibir() }
                assertIs<Recibido.Cierre>(caida, "$p · la caída: $caida")
                assertTrue(caida.porRed, "$p · una conexión que se cae sin trama llegó como cierre del servidor: $caida")
                assertEquals(1006, caida.codigo, "$p · la caída sin trama es la 1006")
                sinSecretos("$p · la caída", caida.motivo, url)
            } finally {
                canal.cerrar("fin")
                servidor.close()
            }
        }
    }

    @Test
    fun promesa243() = conServidor { s ->
        val p = promesa(243)
        val delServidor = "secreto-del-servidor-243"
        val delCliente = "secreto-del-cliente-243"
        val oyente = Oyente(alAbrir = { it.send(delServidor) })
        s.enqueue(MockResponse().withWebSocketUpgrade(oyente))
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val antes = hilosDeOkHttp()
        val canal = CanalOkHttp(log = { _, m -> log += m })
        val url = s.ws()

        assertEquals(Apertura.Ok, aTiempo("abrir") { canal.abrir(url, CABECERAS) }, p)
        val pedido = assertNotNull(s.takeRequest(TOPE_MS, TimeUnit.MILLISECONDS), "$p · el servidor no vio el apretón de manos")
        assertEquals("Bearer $CLAVE", pedido.getHeader("Authorization"), "$p · la clave no viajó en la cabecera")
        assertFalse(CLAVE in pedido.requestUrl.toString(), "$p · la clave viajó en la URL")
        assertEquals(RUTA, pedido.path, "$p · la URL cambió por el camino")

        assertEquals(Recibido.Mensaje(delServidor), aTiempo("recibir") { canal.recibir() }, p)
        aTiempo("enviar") { canal.enviar(delCliente) }
        assertEquals(delCliente, oyente.recibidos.poll(TOPE_MS, TimeUnit.MILLISECONDS), p)

        canal.cerrar("fin")
        canal.cerrar("fin")
        assertEquals(1000 to "fin", oyente.cierres.poll(TOPE_MS, TimeUnit.MILLISECONDS), "$p · el servidor no recibió el cierre normal «fin»")
        assertNull(oyente.cierres.poll(300, TimeUnit.MILLISECONDS), "$p · cerrar dos veces mandó dos tramas de cierre")
        assertEquals(Recibido.Cierre(1000, "fin", porRed = false), aTiempo("recibir tras cerrar") { canal.recibir() }, p)
        val envio = aTiempo<Result<Unit>>("enviar tras cerrar") { runCatching { canal.enviar("tarde") } }
        assertTrue(envio.isFailure, "$p · enviar tras cerrar no dijo nada")

        assertTrue(hasta(3_000) { (hilosDeOkHttp() - antes).isEmpty() }, "$p · quedan vivos: ${(hilosDeOkHttp() - antes).map { it.name }}")

        val lineas = synchronized(log) { log.toList() }
        assertTrue(lineas.isNotEmpty(), "$p · el canal no dejó ni una línea: no se sabe qué pasó")
        for (linea in lineas) {
            for (prohibido in listOf(CLAVE, "Bearer", url, RUTA, delServidor, delCliente)) {
                assertFalse(prohibido in linea, "$p · el log trae «$prohibido»: «$linea»")
            }
        }
    }
}
