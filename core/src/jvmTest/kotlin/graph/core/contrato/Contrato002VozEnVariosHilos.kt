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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * LA 236 VIVE EN jvmTest PORQUE commonTest NO VE CARRERAS: `corre` es un runBlocking de un solo hilo, y ahí dos llamadas no
 * se pisan nunca. Aquí la voz corre con su despachador por defecto, el de verdad, y seis hilos de verdad le hablan a la vez
 * —el micrófono, la retirada y los avisos, como el AudioRecord, la tarea de 2C y la pantalla en la fase B— mientras el
 * servidor le pide herramientas de pantalla y de control. Semilla fija por hilo y por ronda, y un tope por ronda.
 *
 * UNA CARRERA NO SALE EN CADA RONDA, y por eso son muchas: medido, el escritor único saltado en un solo sitio se ve en ≈13 %
 * de las rondas y `limitedParallelism(2)` en ≈25 %. Y LA CARGA NO ES UNA CARRERA: con la CPU saturada una ronda tarda
 * ≈0,5 s. El juez separa las dos cosas. Carrera detectada: un envío intercalado, un pedido con una llamada sin salida, una
 * salida o un aviso repetido, un hilo que lanza, o una ronda que se queda QUIETA sin terminar (nada se mueve durante
 * [QUIETA_MS]: una llamada colgada no avanza con más tiempo). No terminó a tiempo: pasó su tope y seguía moviéndose; esa
 * ronda se repite una vez con la misma semilla, y solo si vuelve a no caber es rojo, con ese nombre.
 */
class Contrato002VozEnVariosHilos {

    private companion object {
        /** A ≈13 % por ronda, 60 rondas dejan escapar una carrera en 1 de cada ~4000 corridas. */
        const val RONDAS = 60
        const val HILOS = 6
        const val OPERACIONES = 40
        const val LLAMADAS = 48

        /** De CADA ronda, no del total: sin carga una tarda ≈50 ms, y con la CPU saturada ≈0,5 s. */
        const val TOPE_POR_RONDA_MS = 15_000L

        /** Sin terminar y sin que nada se mueva este tiempo, no es carga: algo quedó colgado. */
        const val QUIETA_MS = 3_000L

        const val PREGUNTA = "haz muchas cosas a la vez"
        const val AVISO = "[aviso del sistema] "
        const val AUDIO = "→{\"type\":\"session.input_audio.append\""
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

    /** Cómo acabó una ronda. */
    private sealed interface Ronda {
        data object Limpia : Ronda
        class Carrera(val que: String) : Ronda
        class SinTiempo(val que: String) : Ronda
    }

    private enum class Espera { CUMPLIDA, QUIETA, VENCIDA }

    private fun JsonObject.texto(vararg camino: String): String? {
        var e: Any? = this
        for (paso in camino) e = (e as? JsonObject)?.get(paso) ?: return null
        return (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** Una lista que llenan otros hilos se lee por copia y con su candado: recorrerla a secas es una carrera del propio juez. */
    private fun foto(lista: MutableList<String>): List<String> = synchronized(lista) { lista.toList() }

    private fun enviado(linea: String): JsonObject = Json.parseToJsonElement(linea.drop(1)).jsonObject

    /**
     * Espera a que se cumpla, hasta [fin]. QUIETA si [progreso] no cambió en [QUIETA_MS] sin cumplirse; sin [progreso] (nada
     * corre en paralelo que pueda colgarse) solo vence.
     */
    private suspend fun esperar(fin: Long, progreso: (() -> Int)?, cumple: () -> Boolean): Espera {
        var visto = progreso?.invoke()
        var desde = System.nanoTime()
        while (true) {
            if (cumple()) return Espera.CUMPLIDA
            val ahora = System.nanoTime()
            val p = progreso?.invoke()
            if (p != visto) {
                visto = p
                desde = ahora
            } else if (progreso != null && ahora - desde >= QUIETA_MS * 1_000_000) {
                return Espera.QUIETA
            }
            if (ahora >= fin) return Espera.VENCIDA
            delay(2)
        }
    }

    @Test
    fun promesa236() {
        repeat(RONDAS) { ronda ->
            var r = unaRonda(ronda)
            // NO TERMINAR A TIEMPO PUEDE SER LA MÁQUINA: se repite una vez. Una carrera detectada no se repite.
            if (r is Ronda.SinTiempo) r = unaRonda(ronda)
            when (r) {
                Ronda.Limpia -> Unit
                is Ronda.Carrera -> fail(promesa(236) + " · CARRERA DETECTADA en la ronda $ronda: ${r.que}")
                is Ronda.SinTiempo -> fail(
                    promesa(236) + " · NO TERMINÓ A TIEMPO: la ronda $ronda pasó dos veces su tope de $TOPE_POR_RONDA_MS ms sin " +
                        "quedarse quieta (${r.que}). No se vio ninguna carrera: es la carga de la máquina",
                )
            }
        }
    }

    private fun unaRonda(ronda: Int): Ronda = runBlocking(Dispatchers.Default) {
        val canal = CanalDeVariosHilos()
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val dicho: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val alcance = CoroutineScope(Dispatchers.Default)
        val pool = Executors.newFixedThreadPool(HILOS)
        try {
            jugar(ronda, canal, log, dicho, alcance, pool)
        } finally {
            // Una ronda que acaba antes de tiempo no deja hilos ni una voz viva que ensucie la siguiente.
            pool.shutdownNow()
            alcance.cancel()
        }
    }

    private suspend fun jugar(
        ronda: Int,
        canal: CanalDeVariosHilos,
        log: MutableList<String>,
        dicho: MutableList<String>,
        alcance: CoroutineScope,
        pool: java.util.concurrent.ExecutorService,
    ): Ronda {
        val fin = System.nanoTime() + TOPE_POR_RONDA_MS * 1_000_000
        val reloj = RelojDeHilos()
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
        fun movimiento() = canal.linea.size + log.size

        // Fuera de este runBlocking: si la voz revienta, se afirma sobre eso en vez de tumbar la ronda sin mensaje.
        val voz = alcance.async { conv.conversar() }
        canal.entrada.send(Recibido.Mensaje(SESION))
        canal.entrada.send(Recibido.Mensaje(usuario(PREGUNTA)))
        if (esperar(fin, null) { "Te escucho." in foto(dicho) } != Espera.CUMPLIDA) return Ronda.SinTiempo("confirmar la sesión")

        val aceptados = ConcurrentLinkedQueue<String>()
        val hechas = AtomicInteger()
        val largada = CountDownLatch(1)
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
                    hechas.incrementAndGet()
                    if (i % 10 == 9) Thread.sleep(1)
                }
            }
        }
        largada.countDown()
        for (k in 0 until LLAMADAS) {
            canal.entrada.send(Recibido.Mensaje(pide("call_$k", if (k % 2 == 0) "pulsar" else "como_va")))
            if (k % 6 == 5) delay(1)
        }
        val operaciones = { "${hechas.get()} de ${HILOS * OPERACIONES} operaciones" }
        when (esperar(fin, { hechas.get() }) { hilos.all { it.isDone } }) {
            Espera.CUMPLIDA -> Unit
            Espera.QUIETA -> return Ronda.Carrera("los hilos que le hablan a la voz se quedaron quietos sin terminar: ${operaciones()}")
            Espera.VENCIDA -> return Ronda.SinTiempo("los hilos iban por ${operaciones()}")
        }
        for (h in hilos) {
            try {
                h.get()
            } catch (e: ExecutionException) {
                return Ronda.Carrera("un hilo que le hablaba a la voz lanzó ${e.cause}")
            }
        }

        // Quieta: todas entregadas, todas contestadas y lo último que salió (sin contar el micrófono) es el pedido de respuesta.
        fun estado(): String {
            val l = canal.foto()
            val envios = l.filter { it.startsWith("→") && !it.startsWith(AUDIO) }
            val contestadas = envios.mapNotNullTo(HashSet()) { LLAMADA.find(it)?.groupValues?.get(1) }.size
            val ultimo = envios.lastOrNull()?.let { enviado(it).texto("type") }
            return "${l.count { it.startsWith("←") }} de $LLAMADAS entregadas, $contestadas contestadas, lo último que salió: $ultimo"
        }
        val terminada = "$LLAMADAS de $LLAMADAS entregadas, $LLAMADAS contestadas, lo último que salió: response.create"
        when (esperar(fin, ::movimiento) { estado() == terminada }) {
            Espera.CUMPLIDA -> Unit
            Espera.QUIETA -> return Ronda.Carrera("la voz se quedó quieta sin contestarlo todo: ${estado()}")
            Espera.VENCIDA -> return Ronda.SinTiempo("contestar: ${estado()}")
        }

        // EL TURNO. Sin ninguna llamada en curso, 2000 ms de silencio lo cierran; con una que una carrera dejó colgada, no.
        reloj.ms.addAndGet(10_000)
        canal.entrada.send(Recibido.Mensaje(SIN_HECHOS))
        when (esperar(fin, ::movimiento) { foto(log).any { "usuario dijo: $PREGUNTA" in it } }) {
            Espera.CUMPLIDA -> Unit
            Espera.QUIETA -> return Ronda.Carrera("el turno no se cerró: quedó una llamada en curso. ${foto(log).takeLast(8)}")
            Espera.VENCIDA -> return Ronda.SinTiempo("cerrar el turno")
        }

        val restante = ((fin - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
        withTimeoutOrNull(restante) { conv.detener() } ?: return Ronda.SinTiempo("detener la voz")
        when (esperar(fin, ::movimiento) { voz.isCompleted }) {
            Espera.CUMPLIDA -> Unit
            Espera.QUIETA -> return Ronda.Carrera("la voz no terminó al detenerla")
            Espera.VENCIDA -> return Ronda.SinTiempo("terminar la voz al detenerla")
        }

        return try {
            juzgar(canal, log, voz.getCompletionExceptionOrNull(), aceptados)
            Ronda.Limpia
        } catch (e: AssertionError) {
            Ronda.Carrera(e.message.orEmpty())
        }
    }

    /** Terminada la ronda, lo que pasó por el socket. Cada fallo de aquí es una carrera: el tiempo ya no cuenta. */
    private fun juzgar(canal: CanalDeVariosHilos, log: MutableList<String>, lanzada: Throwable?, aceptados: Collection<String>) {
        assertEquals(0, canal.intercalados.get(), "dos envíos a la vez en el socket")
        assertTrue(lanzada == null, "la voz terminó con $lanzada")

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
                    assertTrue(sinSalida.isEmpty(), "se pidió respuesta con ${sinSalida.size} llamada(s) sin salida: $sinSalida")
                    ultimoPedido = i
                }
            }
        }
        assertEquals(LLAMADAS, pedidas.size, "llamadas entregadas")
        assertEquals((0 until LLAMADAS).associate { "call_$it" to 1 }, contestadas.toMap(), "cada llamada, una salida y solo una")
        assertTrue(ultimoPedido > ultimaSalida, "tras la última salida o aviso no se pidió respuesta: algo quedó pendiente")
        assertEquals(aceptados.sorted(), avisosSalidos.sorted(), "cada aviso aceptado sale una vez")
        assertEquals(1, foto(log).count { "usuario dijo: $PREGUNTA" in it }, "el turno se cerró más de una vez. ${foto(log).takeLast(8)}")
        assertTrue(foto(log).none { "no pude" in it }, "${foto(log).filter { "no pude" in it }}")
    }
}
