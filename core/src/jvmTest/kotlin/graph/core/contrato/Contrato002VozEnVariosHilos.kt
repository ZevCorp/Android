package graph.core.contrato

import graph.core.contrato.Contrato002VozGptLive.Companion.promesa
import graph.core.voz.Apertura
import graph.core.voz.CanalDeVoz
import graph.core.voz.ConversacionViva
import graph.core.voz.Recibido
import graph.core.voz.Reloj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LA 236 VIVE EN jvmTest PORQUE commonTest NO VE CARRERAS: `corre` es un runBlocking de un solo hilo, y ahí dos llamadas no
 * se pisan nunca. Aquí la voz corre con su despachador por defecto, el de verdad, y seis hilos de verdad le hablan a la vez
 * —el micrófono, la retirada y los avisos, como el AudioRecord, la tarea de 2C y la pantalla en la fase B— mientras el
 * servidor le pide herramientas de pantalla y de control. Semilla fija por hilo y por ronda, y un tope de tiempo.
 */
class Contrato002VozEnVariosHilos {

    private companion object {
        const val RONDAS = 25
        const val HILOS = 6
        const val OPERACIONES = 40
        const val LLAMADAS = 48
        const val TOPE_MS = 9_000L
        const val PREGUNTA = "haz muchas cosas a la vez"
        const val AVISO = "[aviso del sistema] "
        const val SESION = """{"type":"session.started","session":{"id":"live_1","model":"gpt-live-1","status":"active","input":[]}}"""
        const val SIN_HECHOS = """{"type":"session.updated","session":{"id":"live_1","model":"gpt-live-1","status":"active"}}"""
        val LLAMADA = Regex("\"call_id\":\"(call_\\d+)\"")

        fun usuario(t: String) = """{"type":"session.input_transcript.delta","delta":${JsonPrimitive(t)}}"""
        fun pide(id: String, nombre: String) =
            """{"type":"response.event","event":{"type":"response.output_item.done","item":{"type":"function_call","call_id":"$id","name":"$nombre","arguments":"{}"}}}"""
    }

    /**
     * El socket de verdad no admite dos envíos a la vez: este cuenta cuántos se solapan, y cede a mitad de cada uno para
     * que un segundo escritor tenga por dónde colarse. La línea guarda el orden de lo que pasó por él.
     */
    private class CanalDeVariosHilos : CanalDeVoz {
        val entrada = Channel<Recibido>(Channel.UNLIMITED)

        /** «→» cada envío; «←» cada llamada, en el momento en que se le entrega a la voz. */
        val linea: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val enVuelo = AtomicInteger()
        val intercalados = AtomicInteger()

        override suspend fun abrir(url: String, cabeceras: Map<String, String>): Apertura = Apertura.Ok

        override suspend fun enviar(texto: String) {
            if (enVuelo.incrementAndGet() > 1) intercalados.incrementAndGet()
            try {
                linea += "→$texto"
                Thread.yield()
                yield()
            } finally {
                enVuelo.decrementAndGet()
            }
        }

        override suspend fun recibir(): Recibido {
            val r = entrada.receive()
            if (r is Recibido.Mensaje) LLAMADA.find(r.texto)?.let { linea += "←" + it.groupValues[1] }
            return r
        }

        override fun cerrar(motivo: String) {
            entrada.trySend(Recibido.Cierre(1000, motivo, porRed = false))
        }

        fun foto(): List<String> = synchronized(linea) { linea.toList() }
    }

    private class RelojDeHilos : Reloj {
        val ms = AtomicLong()
        override fun ahora() = ms.get()
        override suspend fun esperar(ms: Long) {
            this.ms.addAndGet(ms)
        }
    }

    private fun JsonObject.texto(vararg camino: String): String? {
        var e: Any? = this
        for (paso in camino) e = (e as? JsonObject)?.get(paso) ?: return null
        return (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun enviado(linea: String): JsonObject = Json.parseToJsonElement(linea.drop(1)).jsonObject

    private suspend fun hasta(ms: Long, condicion: () -> Boolean): Boolean {
        val fin = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < fin) {
            if (condicion()) return true
            delay(2)
        }
        return condicion()
    }

    @Test
    fun promesa236() {
        val desde = System.nanoTime()
        repeat(RONDAS) { ronda ->
            val restante = TOPE_MS - (System.nanoTime() - desde) / 1_000_000
            assertTrue(restante > 0, promesa(236) + " · $ronda rondas no cupieron en $TOPE_MS ms")
            unaRonda(ronda, restante)
        }
    }

    private fun unaRonda(ronda: Int, tope: Long) = runBlocking(Dispatchers.Default) {
        val canal = CanalDeVariosHilos()
        val reloj = RelojDeHilos()
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val dicho: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val conv = ConversacionViva(
            canal = canal,
            credencial = { "sk-prueba" },
            instruccionesVoz = "Eres Ü.",
            instruccionesDelegado = "ERES Ü",
            utensilios = emptyList(),
            // Las de pantalla vuelven desde otro hilo, como una acción que espera a la pantalla; las de control, enseguida.
            ejecutar = {
                if (it.nombre == "pulsar") delay(1) else yield()
                "hecho: ${it.id}"
            },
            reproducir = {},
            callar = {},
            sonando = { false },
            dice = { dicho += it },
            log = { _, m -> log += m },
            reloj = reloj,
            actuaEnPantalla = { it == "pulsar" },
        )
        val prefijo = promesa(236) + " · ronda $ronda"
        // Fuera de este runBlocking: si la voz revienta, se afirma sobre eso en vez de tumbar la ronda sin mensaje.
        val voz = CoroutineScope(Dispatchers.Default).async { conv.conversar() }
        canal.entrada.send(Recibido.Mensaje(SESION))
        canal.entrada.send(Recibido.Mensaje(usuario(PREGUNTA)))
        assertTrue(hasta(1_000) { "Te escucho." in dicho }, "$prefijo · la sesión no se confirmó: $log")

        val aceptados = ConcurrentLinkedQueue<String>()
        val largada = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(HILOS)
        val hilos = (0 until HILOS).map { h ->
            pool.submit {
                val azar = Random(ronda * 1_000 + h)
                largada.await()
                repeat(OPERACIONES) { i ->
                    runBlocking {
                        when (azar.nextInt(3)) {
                            0 -> conv.oirMicrofono(ByteArray(480) { (it % 7).toByte() })
                            1 -> conv.retirar(listOf("call_${azar.nextInt(LLAMADAS)}"))
                            else -> "aviso $h-$i".let { if (conv.avisar(it)) aceptados += it }
                        }
                    }
                    if (i % 10 == 9) Thread.sleep(1)
                }
            }
        }
        largada.countDown()
        for (k in 0 until LLAMADAS) {
            canal.entrada.send(Recibido.Mensaje(pide("call_$k", if (k % 2 == 0) "pulsar" else "como_va")))
            if (k % 6 == 5) delay(1)
        }
        runInterruptible(Dispatchers.IO) { hilos.forEach { it.get(tope, TimeUnit.MILLISECONDS) } }
        pool.shutdown()

        // Quieta: todas entregadas, todas contestadas y lo último que salió (sin contar el micrófono) es el pedido de respuesta.
        hasta(2_000) {
            val l = canal.foto()
            val envios = l.filter { it.startsWith("→") }.map(::enviado).filter { it.texto("type") != "session.input_audio.append" }
            l.count { it.startsWith("←") } == LLAMADAS &&
                envios.mapNotNull { it.texto("item", "call_id") }.toSet().size == LLAMADAS &&
                envios.lastOrNull()?.texto("type") == "response.create"
        }
        // EL TURNO. Sin ninguna llamada en curso, 2000 ms de silencio lo cierran; con una que una carrera dejó colgada, no.
        reloj.ms.addAndGet(10_000)
        canal.entrada.send(Recibido.Mensaje(SIN_HECHOS))
        hasta(1_000) { log.any { "usuario dijo: $PREGUNTA" in it } }
        conv.detener()
        withTimeoutOrNull(2_000) { voz.join() } ?: voz.cancel()

        assertEquals(0, canal.intercalados.get(), "$prefijo · dos envíos a la vez en el socket")
        assertTrue(voz.isCompleted && !voz.isCancelled, "$prefijo · la voz no terminó al detenerla")
        val lanzada = voz.getCompletionExceptionOrNull()
        assertTrue(lanzada == null, "$prefijo · la voz terminó con $lanzada")

        val pedidas = mutableSetOf<String>()
        val contestadas = mutableMapOf<String, Int>()
        val avisosSalidos = mutableListOf<String>()
        var ultimaSalida = -1
        var ultimoPedido = -1
        canal.foto().forEachIndexed { i, e ->
            if (e.startsWith("←")) {
                pedidas += e.drop(1)
                return@forEachIndexed
            }
            val m = enviado(e)
            when (m.texto("type")) {
                "response.item.create" -> {
                    m.texto("item", "call_id")?.let { contestadas[it] = (contestadas[it] ?: 0) + 1 }
                    ((m["item"] as? JsonObject)?.get("content") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.texto("text") }
                        ?.let { avisosSalidos += it.removePrefix(AVISO) }
                    ultimaSalida = i
                }
                "response.create" -> {
                    val sinSalida = pedidas - contestadas.keys
                    assertTrue(sinSalida.isEmpty(), "$prefijo · se pidió respuesta con ${sinSalida.size} llamada(s) sin salida: $sinSalida")
                    ultimoPedido = i
                }
            }
        }
        assertEquals(LLAMADAS, pedidas.size, "$prefijo · llamadas entregadas")
        assertEquals((0 until LLAMADAS).associate { "call_$it" to 1 }, contestadas.toMap(), "$prefijo · cada llamada, una salida y solo una")
        assertTrue(ultimoPedido > ultimaSalida, "$prefijo · tras la última salida o aviso no se pidió respuesta: algo quedó pendiente")
        assertEquals(aceptados.sorted(), avisosSalidos.sorted(), "$prefijo · cada aviso aceptado sale una vez")
        assertEquals(1, log.count { "usuario dijo: $PREGUNTA" in it }, "$prefijo · el turno no se cerró: quedó una llamada en curso. ${log.takeLast(8)}")
        assertTrue(log.none { "no pude" in it }, "$prefijo · ${log.filter { "no pude" in it }}")
    }
}
