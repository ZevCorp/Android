package graph.core.contrato

import graph.core.domain.GraphLog
import graph.core.graph.TransportReply
import graph.core.graph.TurnTransport
import graph.core.graph.learning.Almacen
import graph.core.graph.learning.Arranque
import graph.core.graph.learning.Cierre
import graph.core.graph.learning.CierrePendiente
import graph.core.graph.learning.IdentidadDePantalla
import graph.core.graph.learning.LearningClient
import graph.core.graph.learning.Leccion
import graph.core.graph.learning.LeccionEnDisco
import graph.core.graph.learning.LeccionJson
import graph.core.graph.learning.ResumenDeVideo
import graph.core.graph.learning.StepRequest
import graph.core.graph.learning.VideoParaReprocesar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * CONTRATO 004 · LA LECCIÓN (docs/specs/004-lo-ensenado-vive-en-graph.md, fase 4A2 y su revisión: 417-418).
 *
 * Cada `promesaNNN` es una fila de la tabla de la spec, con el enunciado literal en [PROMESAS]. Juzgan el
 * orquestador de una enseñanza con el [LearningClient] REAL encima de un transporte que responde por ruta y
 * graba cada llamada, un [Almacen] en memoria que falla a pedido y una [Cronica] donde la red, el disco y el
 * video anotan lo que hacen en el orden en que lo hacen: quién fue primero se juzga por posición, no por
 * reloj. Nada duerme (las esperas del cliente se anotan) y ninguna toca red, Android ni disco. Se
 * escribieron ANTES que el código que juzgan: nacieron rojas.
 */
class Contrato004LeccionEnGraph {

    companion object {
        val PROMESAS = mapOf(
            407 to "Los pasos de una demostración viajan a Graph de a uno y en el orden en que ocurrieron; un paso que falla no detiene la grabación y queda contado con su motivo.",
            408 to "Sin sesión abierta en Graph no se empieza a enseñar, se dice por qué y no queda nada abierto.",
            409 to "Al terminar, la lección se escribe en disco antes de tocar la red, entera o nada.",
            410 to "La nota de contexto viaja antes de cerrar la sesión; sin nota, la sesión se cierra igual.",
            411 to "Si cerrar la sesión no sale por un fallo transitorio, queda pendiente en disco y se reintenta al arrancar hasta que sale; una lectura agotada no deja pendiente automático.",
            412 to "Si procesar el video falla, la sesión se cierra igual y el video queda para reprocesar; una demostración descartada no publica nada.",
            417 to "Si el lector de pasos se muere, los pasos que no viajaron cuentan como no enviados con su motivo y la lección nunca se da por entera ni se anuncia como aprendida con pasos que no llegaron.",
            418 to "Cancelar el cierre de una lección nunca la deja sin cerrar ni sin pendiente: o se cierra en Graph, o queda un pendiente que el arranque sabe cerrar.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** El reloj de pared de la lección: un número fijo, para leerlo tal cual en disco. */
        const val AHORA = 1_789_054_200_000L
        const val BASE = "https://graph.test"
    }

    /* ---------- El mapa a mano: crónica, transporte por rutas y almacén en memoria ---------- */

    /** Lo que pasó, en orden: «red POST /api/v1/…», «disco escribe lecciones/…», «video». */
    class Cronica {
        val eventos = mutableListOf<String>()
        operator fun plusAssign(evento: String) { eventos += evento }
        fun primero(que: (String) -> Boolean) = eventos.indexOfFirst(que)
        fun ultimo(que: (String) -> Boolean) = eventos.indexOfLast(que)
    }

    class Llamada(val metodo: String, val ruta: String, val body: String?) {
        val json: JsonObject get() = Json.parseToJsonElement(body ?: error("$metodo $ruta no llevó cuerpo")).jsonObject
        val esSesion get() = ruta == "/api/v1/learning/sessions"
        val esPaso get() = ruta.endsWith("/steps")
        val esNota get() = ruta.endsWith("/context-notes")
        val esCierre get() = ruta.endsWith("/finish")
        val selector: String get() = json["selector"]!!.jsonPrimitive.content
        override fun toString() = "$metodo $ruta"
    }

    /**
     * Responde por ruta con [responder] y graba cada llamada, también en la crónica. Cada llamada cede el hilo
     * tres veces antes de responder, como una red que tarda: si dos llamadas viajan a la vez, aquí se cruzan
     * y [maxEnVuelo] lo cuenta.
     */
    class TransporteDeRutas(private val cronica: Cronica, private val responder: suspend (Llamada) -> TransportReply) : TurnTransport {
        val llamadas = mutableListOf<Llamada>()
        private var enVuelo = 0
        var maxEnVuelo = 0
            private set

        override suspend fun post(url: String, body: String, headers: Map<String, String>) = send("POST", url, body, headers)

        override suspend fun send(method: String, url: String, body: String?, headers: Map<String, String>, timeout: Duration?): TransportReply {
            val llamada = Llamada(method, url.removePrefix(BASE), body)
            llamadas += llamada
            cronica += "red $llamada"
            enVuelo++
            maxEnVuelo = maxOf(maxEnVuelo, enVuelo)
            try {
                repeat(3) { yield() }
                return responder(llamada)
            } finally {
                enVuelo--
            }
        }
    }

    /** El disco en memoria. [falla] decide qué rutas se caen a mitad: lo escrito hasta ahí no queda, que es lo que promete el puerto. */
    class AlmacenEnMemoria(private val cronica: Cronica, private val falla: (String) -> Boolean = { false }) : Almacen {
        val archivos = linkedMapOf<String, String>()
        val escrituras = mutableListOf<String>()

        override suspend fun escribirEntero(ruta: String, contenido: String) {
            escrituras += ruta
            if (falla(ruta)) {
                cronica += "disco falla $ruta"
                throw IllegalStateException("disco lleno a mitad de $ruta")
            }
            archivos[ruta] = contenido
            cronica += "disco escribe $ruta"
        }

        override suspend fun leer(ruta: String): String? = archivos[ruta]

        override suspend fun listar(carpeta: String): List<String> =
            archivos.keys.filter { it.startsWith("$carpeta/") && '/' !in it.removePrefix("$carpeta/") }

        override suspend fun borrar(ruta: String) {
            archivos.remove(ruta)
            cronica += "disco borra $ruta"
        }

        fun en(carpeta: String) = archivos.filterKeys { it.startsWith("$carpeta/") }
    }

    private fun ok(json: String) = TransportReply(200, json)

    /** Un Graph que dice que sí a todo, como el del día bueno. */
    private fun sano(l: Llamada, sesion: String = "ses-1"): TransportReply = when {
        l.esSesion -> ok("""{"session":{"id":"$sesion","workflow_id":"wf-$sesion","recording":true}}""")
        l.esPaso -> ok("""{"step":{"step_order":1}}""")
        l.esNota -> ok("{}")
        l.esCierre -> ok("""{"workflow_id":"wf-$sesion","summary":"registra pacientes"}""")
        else -> error("ruta no prevista: $l")
    }

    private fun CoroutineScope.leccion(
        transporte: TurnTransport,
        almacen: Almacen,
        esperas: MutableList<Long> = mutableListOf(),
        avisos: MutableList<String> = mutableListOf(),
        key: () -> String = { "miracle_k" },
        topeDeVaciado: Duration = Leccion.TOPE_DE_VACIADO,
        /** Donde vive el lector de pasos: el de la prueba, o uno que la prueba cancela para matarlo. */
        scope: CoroutineScope = this,
        lineas: MutableList<String> = mutableListOf(),
        ahoraMs: () -> Long = { AHORA },
    ): Leccion {
        val reloj = TestTimeSource()
        val cliente = LearningClient(
            transport = transporte,
            credentials = key,
            baseUrl = { "$BASE/" },
            email = { null },
            deviceId = { "dev-1" },
            sleep = { esperas += it; reloj += it.milliseconds },
            timeSource = reloj,
        )
        return Leccion(
            cliente = cliente,
            almacen = almacen,
            scope = scope,
            appId = "dev-1",
            ahoraMs = ahoraMs,
            log = GraphLog { tag, m -> lineas += "[$tag] $m" },
            avisar = { avisos += it },
            topeDeVaciado = topeDeVaciado,
        )
    }

    /**
     * Corre una demostración entera. El lector de pasos vive en el scope de la prueba: si al acabar quedó algo
     * vivo —un lector sin cerrar, una llamada colgada—, la prueba falla a los 20 s en vez de colgarse.
     */
    private fun demo(p: String, bloque: suspend CoroutineScope.() -> Unit) = corre {
        try {
            withTimeout(20.seconds) { coroutineScope { bloque() } }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("$p · la demostración no terminó en 20 s: quedó algo abierto")
        }
    }

    /**
     * El bloque lanza exactamente [T]: ni una madre ni una hija. En la JVM `CancellationException` ES una
     * `IllegalStateException`, así que un `assertFailsWith` dejaría pasar una por la otra.
     */
    private inline fun <reified T : Throwable> lanzaExacto(donde: String, bloque: () -> Unit): T {
        val e = try { bloque(); null } catch (e: NotImplementedError) { throw e } catch (e: Throwable) { e }
        val lanzada = assertNotNull(e, "$donde · no lanzó")
        assertEquals(T::class, lanzada::class, "$donde · lanzó ${lanzada::class.simpleName}: ${lanzada.message}")
        return lanzada as T
    }

    private fun leida(almacen: AlmacenEnMemoria, ruta: String?) =
        LeccionJson.decodeFromString(LeccionEnDisco.serializer(), almacen.archivos.getValue(assertNotNull(ruta, "la lección no quedó en disco")))

    private val registro = IdentidadDePantalla(url = "android://com.x/RegistroActivity", origin = "android://com.x", pathname = "/RegistroActivity", title = "Registro")
    private val listo = "android://com.x/ListoActivity"
    private fun paso(n: Int) = StepRequest(actionType = "click", selector = "a11y:id=com.x:id/b$n", label = "B$n", controlType = "button")
    private val sinVideo: suspend () -> ResumenDeVideo? = { null }

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa407() = demo(promesa(407)) {
        val p = promesa(407)
        // Seis pasos y Graph rechaza el tercero: viajan de a uno, en su orden, y el rechazo no corta los de después.
        run {
            val cronica = Cronica()
            var numerados = 0
            val t = TransporteDeRutas(cronica) { l ->
                when {
                    l.esPaso && l.selector.endsWith("/b3") -> TransportReply(400, """{"error":"actionType inválido"}""")
                    l.esPaso -> ok("""{"step":{"step_order":${++numerados}}}""")
                    else -> sano(l)
                }
            }
            val almacen = AlmacenEnMemoria(cronica)
            val avisos = mutableListOf<String>()
            val l = leccion(t, almacen, avisos = avisos)
            assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), p)
            for (n in 1..6) assertTrue(l.pasoObservado(paso(n)), "$p · el paso $n no se aceptó")
            val r = l.terminar(listo, sinVideo)

            assertEquals(
                (1..6).map { "a11y:id=com.x:id/b$it" }, t.llamadas.filter { it.esPaso }.map { it.selector },
                "$p · en el orden en que ocurrieron, y el rechazo no cortó los de después",
            )
            assertEquals(1, t.maxEnVuelo, "$p · dos llamadas viajaron a la vez: Graph numera los pasos por llegada")
            assertEquals(5 to 1, r.pasosMandados to r.pasosFallidos, "$p · mandados y fallidos")
            assertEquals((1..6).toList(), r.pasos.map { it.orden }, p)
            val rechazado = r.pasos.single { !it.enviado }
            assertEquals("a11y:id=com.x:id/b3", rechazado.paso.selector, p)
            assertTrue("actionType inválido" in rechazado.motivo.orEmpty(), "$p · el paso que falló no dice por qué: ${rechazado.motivo}")
            assertEquals(listOf(1, 2, 3, 4, 5), r.pasos.filter { it.enviado }.map { it.stepOrder }, "$p · el step_order que dio Graph")
            val enDisco = leida(almacen, r.leccion).pasos.single { !it.enviado }
            assertTrue(enDisco.orden == 3 && "actionType inválido" in enDisco.motivo.orEmpty(), "$p · en la lección, el fallido no está contado con su motivo")
            assertEquals(1, t.llamadas.count { it.esCierre }, "$p · un paso que falla no detiene la grabación: se cierra igual")
            assertTrue(avisos.isEmpty(), "$p · seis pasos no son una grabación larga: $avisos")
        }
        // Al llegar a 30 pasos mandados, el aviso de 504 se da una vez (WorkflowTeachSession.cs: AvisoPasos).
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val avisos = mutableListOf<String>()
            val l = leccion(t, AlmacenEnMemoria(cronica), avisos = avisos)
            l.empezar(registro, "Registrar paciente")
            for (n in 1..31) l.pasoObservado(paso(n))
            val r = l.terminar(listo, sinVideo)
            assertEquals(31, r.pasosMandados, p)
            assertEquals(1, avisos.size, "$p · el aviso de grabación larga se da una vez: $avisos")
            assertTrue("30" in avisos.single() && "504" in avisos.single(), "$p · aviso: ${avisos.single()}")
        }
    }

    @Test
    fun promesa408() = demo(promesa(408)) {
        val p = promesa(408)
        val casos = listOf(
            Triple("la key no vale", TransportReply(401, """{"error":"invalid api key"}"""), "key"),
            Triple("lectura agotada", TransportReply(-1, "Read timed out"), "no respondió a tiempo"),
            Triple("graph dice que no", TransportReply(400, """{"error":"app_id requerido"}"""), "app_id requerido"),
            Triple("sin id de sesión", ok("""{"session":{"id":"","workflow_id":""}}"""), "id de sesión"),
            Triple("graph caído", TransportReply(503, ""), "HTTP 503"),
        )
        for ((caso, respuesta, causa) in casos) {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { l -> if (l.esSesion) respuesta else sano(l) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen)
            val no = assertIs<Arranque.NoSePuede>(l.empezar(registro, "Registrar paciente"), "$p · $caso: empezó a enseñar sin sesión")
            assertTrue(no.motivo.isNotBlank() && '\n' !in no.motivo && causa in no.motivo, "$p · $caso: el motivo no dice por qué en una línea: «${no.motivo}»")
            nadaAbierto("$p · $caso", l, t, almacen, cronica)
        }
        // Sin key no se llama a nadie, y también se dice.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen, key = { " " })
            val no = assertIs<Arranque.NoSePuede>(l.empezar(registro, "Registrar paciente"), "$p · sin key: empezó a enseñar")
            assertTrue("key" in no.motivo && '\n' !in no.motivo, "$p · sin key: «${no.motivo}»")
            assertTrue(t.llamadas.isEmpty(), "$p · sin key: llamó a Graph")
            nadaAbierto("$p · sin key", l, t, almacen, cronica)
        }
        // Un no no condena la lección: al volver a intentarlo con Graph sano, se enseña.
        run {
            val cronica = Cronica()
            var aperturas = 0
            val t = TransporteDeRutas(cronica) { l -> if (l.esSesion && ++aperturas == 1) TransportReply(400, """{"error":"app_id requerido"}""") else sano(l) }
            val l = leccion(t, AlmacenEnMemoria(cronica))
            assertIs<Arranque.NoSePuede>(l.empezar(registro, "Registrar paciente"), p)
            assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), "$p · después de un no, no se pudo volver a intentar")
            assertTrue(l.pasoObservado(paso(1)), p)
            assertEquals(Cierre.CERRADA, l.terminar(listo, sinVideo).cierre, p)
        }
        // Cancelar empezar cuando Graph ya abrió no deja la lección abierta ni trabada: vuelve a nueva y se puede empezar.
        // La cancelación llega en el último instante de empezar, cuando lee la hora para anotar cuándo empezó.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val abriendo = Job(coroutineContext.job)
            var primeraHora = true
            val l = leccion(t, AlmacenEnMemoria(cronica), ahoraMs = { if (primeraHora) { primeraHora = false; abriendo.cancel() }; AHORA })
            val intento = launch(abriendo) { l.empezar(registro, "Registrar paciente") }
            intento.join()
            assertTrue(intento.isCancelled, "$p · cancelado al abrir: empezar no salió cancelado")
            assertFalse(l.pasoObservado(paso(1)), "$p · cancelado al abrir: quedó enseñando y aceptó un paso")
            assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), "$p · cancelado al abrir: no se pudo volver a empezar")
            assertTrue(l.pasoObservado(paso(1)), p)
            assertEquals(Cierre.CERRADA, l.terminar(listo, sinVideo).cierre, p)
        }
    }

    private suspend fun nadaAbierto(donde: String, l: Leccion, t: TransporteDeRutas, almacen: AlmacenEnMemoria, cronica: Cronica) {
        assertFalse(l.pasoObservado(paso(1)), "$donde: aceptó un paso sin sesión")
        l.nota("esto no va a ningún lado")
        lanzaExacto<IllegalStateException>("$donde: terminar una enseñanza que no empezó") { l.terminar(listo) { cronica += "video"; null } }
        assertFalse(l.descartar(), "$donde: dijo que descartó una enseñanza que no empezó")
        assertTrue(t.llamadas.all { it.esSesion }, "$donde: sin sesión, habló igual con Graph: ${t.llamadas}")
        assertTrue(almacen.escrituras.isEmpty(), "$donde: sin sesión, escribió en disco: ${almacen.escrituras}")
        assertFalse("video" in cronica.eventos, "$donde: sin sesión, procesó el video")
    }

    @Test
    fun promesa409() = demo(promesa(409)) {
        val p = promesa(409)
        // Entera y antes de la red: después del último paso y antes del video, la nota y el cierre.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            l.pasoObservado(paso(2))
            l.nota("es para pacientes nuevos")
            val r = l.terminar(listo) { cronica += "video"; ResumenDeVideo("registra pacientes", null) }

            val escribe = cronica.primero { it.startsWith("disco escribe ${Leccion.CARPETA_LECCIONES}/") }
            val cierre = cronica.primero { it == "video" || it.endsWith("/context-notes") || it.endsWith("/finish") }
            assertTrue(escribe >= 0, "$p · la lección no se escribió: ${cronica.eventos}")
            assertTrue(cierre >= 0 && escribe < cierre, "$p · la lección se escribió después de tocar la red del cierre: ${cronica.eventos}")
            assertTrue(cronica.ultimo { it.endsWith("/steps") } < escribe, "$p · la lección se escribió antes de vaciar los pasos: ${cronica.eventos}")
            assertEquals(listOf(r.leccion), almacen.escrituras.filter { it.startsWith("${Leccion.CARPETA_LECCIONES}/") }, "$p · la lección se escribe de una vez, no a trozos")
            val leccion = leida(almacen, r.leccion)
            assertEquals("ses-1" to "wf-ses-1", leccion.sessionId to leccion.workflowId, p)
            assertEquals("Registrar paciente", leccion.descripcion, p)
            assertEquals(listOf(registro.url, registro.origin, registro.pathname, registro.title), leccion.identidad.let { listOf(it.url, it.origin, it.pathname, it.title) }, p)
            assertEquals(registro.url to listo, leccion.dondeEmpezo to leccion.dondeTermino, "$p · dónde empezó y dónde terminó")
            assertEquals("es para pacientes nuevos", leccion.nota, p)
            assertEquals(listOf("a11y:id=com.x:id/b1", "a11y:id=com.x:id/b2"), leccion.pasos.map { it.paso.selector }, p)
            assertTrue(leccion.pasos.all { it.enviado }, p)
            assertEquals(AHORA to AHORA, leccion.empezoMs to leccion.terminoMs, p)
            assertNull(leccion.motivo, "$p · entera, no le falta nada: ${leccion.motivo}")
        }
        // Si falta algo, la lección se escribe igual y dice qué: un paso colgado que el tope de vaciado corta, y sin saber dónde terminó.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { l -> if (l.esPaso && l.selector.endsWith("/b2")) awaitCancellation() else sano(l) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen, topeDeVaciado = 100.milliseconds)
            l.empezar(registro, "Registrar paciente")
            for (n in 1..3) l.pasoObservado(paso(n))
            val r = l.terminar("", sinVideo)
            assertEquals(listOf(true, false, false), r.pasos.map { it.enviado }, "$p · lo que el tope cortó no cuenta como mandado")
            assertTrue(r.pasos.drop(1).all { "no llegó a enviarse" in it.motivo.orEmpty() }, "$p · motivos: ${r.pasos.map { it.motivo }}")
            assertTrue(t.llamadas.indexOfLast { it.esPaso } < t.llamadas.indexOfFirst { it.esCierre }, "$p · un paso viajó después de cerrar: ${t.llamadas}")
            val leccion = leida(almacen, r.leccion)
            assertEquals(3, leccion.pasos.size, "$p · la lección lleva también los pasos que no salieron")
            val motivo = leccion.motivo.orEmpty()
            assertTrue("2 de 3" in motivo && "dónde terminó" in motivo, "$p · el motivo no dice qué falta: «$motivo»")
        }
        // Un fallo a mitad de la escritura no deja una lección a medias, se dice, y la sesión se cierra igual.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica, falla = { it.startsWith("${Leccion.CARPETA_LECCIONES}/") })
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            val r = l.terminar(listo, sinVideo)
            assertTrue(almacen.en(Leccion.CARPETA_LECCIONES).isEmpty(), "$p · quedó una lección a medias: ${almacen.archivos.keys}")
            assertEquals(1, almacen.escrituras.count { it.startsWith("${Leccion.CARPETA_LECCIONES}/") }, "$p · la lección se escribió a trozos: ${almacen.escrituras}")
            assertNull(r.leccion, "$p · dice que la lección está en disco y no está")
            assertTrue(r.avisos.any { "lección" in it && "disco lleno" in it }, "$p · no dice que la lección no se guardó: ${r.avisos}")
            assertEquals(Cierre.CERRADA, r.cierre, "$p · sin lección en disco, la sesión se cierra igual")
        }
    }

    @Test
    fun promesa410() = demo(promesa(410)) {
        val p = promesa(410)
        fun transcript(l: Llamada) = l.json["note"]!!.jsonObject["transcript"]!!.jsonPrimitive.content
        // Con nota: viaja una vez, entera y antes de cerrar.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val l = leccion(t, AlmacenEnMemoria(cronica))
            l.empezar(registro, "Registrar paciente")
            l.nota("es para pacientes nuevos")
            l.pasoObservado(paso(1))
            l.nota("siempre en Colombia")
            val r = l.terminar(listo, sinVideo)
            val notas = t.llamadas.filter { it.esNota }
            assertEquals(1, notas.size, "$p · la nota viajó ${notas.size} veces: ${t.llamadas}")
            val cierre = t.llamadas.indexOfFirst { it.esCierre }
            assertTrue(cierre >= 0 && t.llamadas.indexOf(notas.single()) < cierre, "$p · la nota viajó después de cerrar la sesión: ${t.llamadas}")
            assertEquals("/api/v1/learning/sessions/ses-1/context-notes", notas.single().ruta, p)
            val dicho = transcript(notas.single())
            assertTrue("es para pacientes nuevos" in dicho && "siempre en Colombia" in dicho, "$p · la nota no viajó entera: «$dicho»")
            assertTrue(dicho.indexOf("es para pacientes nuevos") < dicho.indexOf("siempre en Colombia"), "$p · los trozos de la nota viajaron fuera de orden: «$dicho»")
            assertEquals(Cierre.CERRADA, r.cierre, p)
        }
        // Sin nota, ni hablada ni del video: no hay context-notes y la sesión se cierra igual.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val l = leccion(t, AlmacenEnMemoria(cronica))
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            l.nota("   ")
            val r = l.terminar(listo) { ResumenDeVideo(null, null) }
            assertEquals(0, t.llamadas.count { it.esNota }, "$p · sin nota, viajó una nota: ${t.llamadas}")
            assertEquals(1, t.llamadas.count { it.esCierre }, "$p · sin nota, la sesión no se cerró")
            assertEquals(Cierre.CERRADA, r.cierre, p)
        }
        // La nota falla: la sesión se cierra igual, y lo dice.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { l -> if (l.esNota) TransportReply(500, """{"error":"neo4j caído"}""") else sano(l) }
            val l = leccion(t, AlmacenEnMemoria(cronica))
            l.empezar(registro, "Registrar paciente")
            l.nota("es para pacientes nuevos")
            val r = l.terminar(listo, sinVideo)
            assertEquals(1, t.llamadas.count { it.esCierre }, "$p · la nota falló y la sesión no se cerró")
            assertEquals(Cierre.CERRADA, r.cierre, p)
            assertTrue(r.avisos.any { "nota" in it && "neo4j caído" in it }, "$p · no dice que la nota no viajó: ${r.avisos}")
        }
        // Lo que vio el video también es contexto: sin nada hablado, la nota lleva el resumen, antes de cerrar.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val l = leccion(t, AlmacenEnMemoria(cronica))
            l.empezar(registro, "Registrar paciente")
            l.terminar(listo) { ResumenDeVideo("registra pacientes nuevos en el triage", null) }
            val nota = t.llamadas.singleOrNull { it.esNota }
            assertNotNull(nota, "$p · el resumen del video no viajó como nota: ${t.llamadas}")
            assertTrue("registra pacientes nuevos en el triage" in transcript(nota), "$p · «${transcript(nota)}»")
            assertTrue(t.llamadas.indexOf(nota) < t.llamadas.indexOfFirst { it.esCierre }, "$p · la nota del video viajó después de cerrar: ${t.llamadas}")
        }
    }

    @Test
    fun promesa411() = demo(promesa(411)) {
        val p = promesa(411)
        val vercel = TransportReply(504, """{"error":"FUNCTION_INVOCATION_TIMEOUT"}""")
        val cronica = Cronica()
        val almacen = AlmacenEnMemoria(cronica) // el mismo disco a lo largo de varios arranques
        // El cierre no sale tras sus 3 intentos: queda pendiente en disco, con la sesión, el workflow y cuándo.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteDeRutas(cronica) { l -> if (l.esCierre) vercel else sano(l) }
            val l = leccion(t, almacen, esperas = esperas)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            val r = l.terminar(listo, sinVideo)
            assertEquals(Cierre.PENDIENTE, r.cierre, p)
            assertTrue("pendiente de cerrar en Graph" in r.mensaje, "$p · mensaje: ${r.mensaje}")
            assertEquals(3, t.llamadas.count { it.esCierre }, "$p · el cierre no se intentó exactamente 3 veces: ${t.llamadas}")
            assertEquals(listOf(3_000L, 8_000L), esperas, p)
            val pendientes = almacen.en(Leccion.CARPETA_PENDIENTES)
            assertEquals(1, pendientes.size, "$p · el cierre que no salió no quedó en disco: ${almacen.archivos.keys}")
            val pendiente = LeccionJson.decodeFromString(CierrePendiente.serializer(), pendientes.values.single())
            assertEquals(Triple("ses-1", "wf-ses-1", AHORA), Triple(pendiente.sessionId, pendiente.workflowId, pendiente.cuandoMs), p)
        }
        // Al arrancar se reintenta: mientras Graph siga sin poder, se queda.
        run {
            val t = TransporteDeRutas(cronica) { l -> if (l.esCierre) vercel else sano(l) }
            val r = leccion(t, almacen).reintentarPendientes()
            assertEquals(0 to 1, r.cerrados to r.siguen, "$p · cerrados y los que siguen")
            assertEquals(1, almacen.en(Leccion.CARPETA_PENDIENTES).size, "$p · un pendiente que sigue sin salir se borró")
        }
        // Cuando sale, se borra; y el arranque siguiente no vuelve a cerrar lo cerrado.
        run {
            val t = TransporteDeRutas(cronica) { sano(it) }
            val r = leccion(t, almacen).reintentarPendientes()
            assertEquals(1 to 0, r.cerrados to r.siguen, p)
            assertEquals(listOf("POST /api/v1/learning/sessions/ses-1/finish"), t.llamadas.map { it.toString() }, p)
            assertTrue(almacen.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · el pendiente que salió no se borró")
            val otro = TransporteDeRutas(cronica) { sano(it) }
            leccion(otro, almacen).reintentarPendientes()
            assertTrue(otro.llamadas.isEmpty(), "$p · se volvió a cerrar lo ya cerrado: ${otro.llamadas}")
        }
        // Una lectura agotada al cerrar no deja pendiente automático: Graph pudo haberlo cerrado, y cobrado.
        run {
            val t = TransporteDeRutas(cronica) { l -> if (l.esCierre) TransportReply(-1, "Read timed out") else sano(l, "ses-2") }
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            val r = l.terminar(listo, sinVideo)
            assertEquals(Cierre.INCIERTO, r.cierre, p)
            assertTrue("pudo haberlo cerrado" in r.mensaje, "$p · mensaje: ${r.mensaje}")
            assertEquals(1, t.llamadas.count { it.esCierre }, "$p · la lectura agotada se reintentó: ${t.llamadas}")
            assertTrue(almacen.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · la lectura agotada dejó un pendiente automático: ${almacen.archivos.keys}")
        }
        // Un pendiente que Graph ya no reconoce, o que al reintentar lee agotado, no se reintenta para siempre.
        for ((sesion, alReintentar) in listOf("ses-3" to TransportReply(400, """{"error":"sesión desconocida"}"""), "ses-4" to TransportReply(-1, "Read timed out"))) {
            val t = TransporteDeRutas(cronica) { l -> if (l.esCierre) vercel else sano(l, sesion) }
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            assertEquals(Cierre.PENDIENTE, l.terminar(listo, sinVideo).cierre, p)
            val arranque = TransporteDeRutas(cronica) { alReintentar }
            val r = leccion(arranque, almacen).reintentarPendientes()
            assertEquals(1, arranque.llamadas.size, "$p · HTTP ${alReintentar.status} al reintentar: ${arranque.llamadas}")
            assertEquals(0, r.siguen, "$p · HTTP ${alReintentar.status} al reintentar: sigue pendiente")
            assertTrue(almacen.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · HTTP ${alReintentar.status} al reintentar: el pendiente se quedó para siempre")
        }
        // Al arrancar, un solo intento por pendiente, como Windows (`PendingFinish.cs:69`): los tres del cierre, con sus 3 s y
        // 8 s y un post-procesado de LLM cada uno, se repetirían en cada arranque y para siempre.
        run {
            val disco = AlmacenEnMemoria(Cronica())
            for (s in listOf("ses-a", "ses-b")) {
                disco.escribirEntero(Leccion.ruta(Leccion.CARPETA_PENDIENTES, s), LeccionJson.encodeToString(CierrePendiente.serializer(), CierrePendiente(s, "wf-$s", AHORA)))
            }
            val esperas = mutableListOf<Long>()
            val t = TransporteDeRutas(Cronica()) { vercel }
            val r = leccion(t, disco, esperas = esperas).reintentarPendientes()
            assertEquals(
                listOf("POST /api/v1/learning/sessions/ses-a/finish", "POST /api/v1/learning/sessions/ses-b/finish"), t.llamadas.map { it.toString() },
                "$p · al arrancar, un pendiente se intentó más de una vez",
            )
            assertTrue(esperas.isEmpty(), "$p · al arrancar se esperó entre intentos: $esperas")
            assertEquals(0 to 2, r.cerrados to r.siguen, p)
            assertEquals(2, disco.en(Leccion.CARPETA_PENDIENTES).size, "$p · un pendiente que sigue sin salir se borró")
        }
        // Sin key, o con una key que no vale, el cierre también queda pendiente: una key mal puesta se arregla y la sesión no
        // murió por eso. Es el mismo criterio del arranque, que los conserva hasta que haya una key que valga.
        run {
            val disco = AlmacenEnMemoria(Cronica())
            var clave = "miracle_k"
            val alCerrar = listOf(
                "ses-k401" to TransportReply(401, """{"error":"invalid api key"}"""),
                "ses-k403" to TransportReply(403, """{"error":"forbidden"}"""),
                "ses-sinkey" to null,
            )
            for ((sesion, respuesta) in alCerrar) {
                clave = "miracle_k"
                val t = TransporteDeRutas(Cronica()) { l -> if (l.esCierre && respuesta != null) respuesta else sano(l, sesion) }
                val l = leccion(t, disco, key = { clave })
                assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), p)
                if (respuesta == null) clave = " "
                val caso = respuesta?.let { "HTTP ${it.status}" } ?: "sin key"
                val r = l.terminar(listo, sinVideo)
                assertEquals(Cierre.PENDIENTE, r.cierre, "$p · $caso al cerrar: ${r.mensaje}")
                assertTrue(Leccion.ruta(Leccion.CARPETA_PENDIENTES, sesion) in disco.archivos, "$p · $caso al cerrar no dejó pendiente: ${disco.archivos.keys}")
            }
            val arranques = listOf(
                Triple("HTTP 401", TransportReply(401, """{"error":"invalid api key"}"""), "miracle_k"),
                Triple("HTTP 403", TransportReply(403, """{"error":"forbidden"}"""), "miracle_k"),
                Triple("sin key", ok("{}"), " "),
            )
            for ((caso, respuesta, llave) in arranques) {
                val t = TransporteDeRutas(Cronica()) { respuesta }
                val r = leccion(t, disco, key = { llave }).reintentarPendientes()
                assertEquals(Triple(0, 3, 0), Triple(r.cerrados, r.siguen, r.descartados), "$p · $caso al arrancar: cerrados, siguen y descartados")
                assertEquals(3, disco.en(Leccion.CARPETA_PENDIENTES).size, "$p · $caso al arrancar: se borró un pendiente que espera una key")
                if (caso == "sin key") assertTrue(t.llamadas.isEmpty(), "$p · sin key al arrancar: llamó a Graph")
            }
            assertEquals(3, leccion(TransporteDeRutas(Cronica()) { sano(it) }, disco).reintentarPendientes().cerrados, "$p · con la key arreglada, los pendientes no salieron")
            assertTrue(disco.en(Leccion.CARPETA_PENDIENTES).isEmpty(), p)
        }
    }

    @Test
    fun promesa412() = demo(promesa(412)) {
        val p = promesa(412)
        val videosQueFallan = listOf<Pair<String, suspend () -> ResumenDeVideo?>>(
            "el video revienta" to { throw IllegalStateException("gemini sin créditos") },
            "el video no deja nada" to { null },
        )
        for ((caso, video) in videosQueFallan) {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            val r = l.terminar(listo, video)
            assertEquals(1, t.llamadas.count { it.esCierre }, "$p · $caso: la sesión no se cerró")
            assertEquals(Cierre.CERRADA, r.cierre, "$p · $caso")
            assertTrue(r.videoParaReprocesar, "$p · $caso: el video no quedó para reprocesar")
            val marcas = almacen.en(Leccion.CARPETA_VIDEOS)
            assertEquals(1, marcas.size, "$p · $caso: no hay marca en disco: ${almacen.archivos.keys}")
            val marca = LeccionJson.decodeFromString(VideoParaReprocesar.serializer(), marcas.values.single())
            assertEquals("ses-1" to r.leccion, marca.sessionId to marca.leccion, "$p · $caso")
            assertTrue(marca.motivo.isNotBlank(), "$p · $caso: la marca no dice por qué")
            if (caso == "el video revienta") assertTrue("gemini sin créditos" in marca.motivo, "$p · $caso: «${marca.motivo}»")
            assertTrue(r.avisos.any { "video" in it }, "$p · $caso: no dice que el video no se procesó: ${r.avisos}")
        }
        // Con el video procesado, no queda nada para reprocesar.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            val r = l.terminar(listo) { ResumenDeVideo("registra pacientes", null) }
            assertFalse(r.videoParaReprocesar, p)
            assertTrue(almacen.en(Leccion.CARPETA_VIDEOS).isEmpty(), "$p · un video procesado quedó para reprocesar")
        }
        // Descartada con un paso en vuelo, dos en cola y una nota: no sale nada más.
        run {
            val cronica = Cronica()
            val enVuelo = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(cronica) { l -> if (l.esPaso) { enVuelo.complete(Unit); awaitCancellation() } else sano(l) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            for (n in 1..3) l.pasoObservado(paso(n))
            l.nota("me equivoqué de pantalla")
            enVuelo.await()
            assertTrue(l.descartar(), "$p · descartar mientras se graba no dijo que descartó")
            val publicado = listOf("POST /api/v1/learning/sessions", "POST /api/v1/learning/sessions/ses-1/steps")
            assertEquals(publicado, t.llamadas.map { it.toString() }, "$p · descartada, siguió publicando")
            assertFalse(l.pasoObservado(paso(4)), "$p · descartada, aceptó un paso")
            l.nota("y otra cosa")
            lanzaExacto<IllegalStateException>("$p · terminar una demostración descartada") { l.terminar(listo) { cronica += "video"; null } }
            assertTrue(l.descartar(), "$p · descartar dos veces: dejó de estar descartada")
            assertEquals(publicado, t.llamadas.map { it.toString() }, "$p · después de descartar, algo salió")
            assertTrue(almacen.escrituras.isEmpty(), "$p · descartada, escribió en disco: ${almacen.escrituras}")
            assertFalse("video" in cronica.eventos, "$p · descartada, procesó el video")
        }
        // Descartada antes de que el lector mande nada: ni un paso.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            l.pasoObservado(paso(2))
            assertTrue(l.descartar(), p)
            assertEquals(listOf("POST /api/v1/learning/sessions"), t.llamadas.map { it.toString() }, "$p · descartada antes de mandar, mandó")
            assertTrue(almacen.escrituras.isEmpty(), "$p · descartada, escribió en disco: ${almacen.escrituras}")
        }
        // Descartar mientras se cierra no descarta: dice que no, y el cierre sigue entero, con el paso que estaba en vuelo.
        run {
            val cronica = Cronica()
            val enVuelo = CompletableDeferred<Unit>()
            val soltar = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(cronica) { l -> if (l.esPaso && l.selector.endsWith("/b2")) { enVuelo.complete(Unit); soltar.await(); sano(l) } else sano(l) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            for (n in 1..3) l.pasoObservado(paso(n))
            l.nota("es para pacientes nuevos")
            enVuelo.await()
            // terminar corre hasta quedarse esperando el paso en vuelo: ya está cerrando.
            val cerrando = async(start = CoroutineStart.UNDISPATCHED) { l.terminar(listo, sinVideo) }
            assertFalse(l.descartar(), "$p · descartar durante el cierre dijo que descartó")
            soltar.complete(Unit)
            val r = cerrando.await()
            assertEquals(listOf(true, true, true), r.pasos.map { it.enviado }, "$p · descartar durante el cierre cortó pasos: ${r.pasos.map { it.motivo }}")
            assertEquals(1 to 1, t.llamadas.count { it.esNota } to t.llamadas.count { it.esCierre }, "$p · descartar durante el cierre se llevó la nota o el cierre: ${t.llamadas}")
            assertEquals(Cierre.CERRADA, r.cierre, p)
            assertNotNull(r.leccion, "$p · descartar durante el cierre dejó la lección sin disco")
        }
    }

    @Test
    fun promesa417() = demo(promesa(417)) {
        val p = promesa(417)
        // El scope del lector se cancela con un paso mandado, otro en vuelo y dos en cola: los tres no viajan, y cuentan.
        run {
            val cronica = Cronica()
            val enVuelo = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(cronica) { l -> if (l.esPaso && l.selector.endsWith("/b2")) { enVuelo.complete(Unit); awaitCancellation() } else sano(l) }
            val almacen = AlmacenEnMemoria(cronica)
            val pantalla = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val l = leccion(t, almacen, scope = pantalla)
            assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), p)
            for (n in 1..4) assertTrue(l.pasoObservado(paso(n)), "$p · el paso $n no se aceptó")
            enVuelo.await()
            pantalla.cancel()
            assertFalse(l.pasoObservado(paso(5)), "$p · con el lector muerto, aceptó un paso que no va a ningún lado")
            val r = l.terminar(listo, sinVideo)
            assertEquals(listOf(true, false, false, false), r.pasos.map { it.enviado }, "$p · los pasos que no viajaron no cuentan como no enviados")
            assertTrue(r.pasos.drop(1).all { "no llegó a enviarse" in it.motivo.orEmpty() && "lector" in it.motivo.orEmpty() }, "$p · motivos: ${r.pasos.map { it.motivo }}")
            assertEquals(listOf("b1", "b2"), t.llamadas.filter { it.esPaso }.map { it.selector.substringAfterLast('/') }, p)
            val motivo = leida(almacen, r.leccion).motivo.orEmpty()
            assertTrue("3 de 4" in motivo, "$p · la lección se dio por entera: «$motivo»")
            assertFalse("aprendí" in r.mensaje, "$p · se anunció como aprendida con pasos que no llegaron: ${r.mensaje}")
            assertTrue("incomplet" in r.mensaje, "$p · el mensaje no dice que quedó incompleta: ${r.mensaje}")
        }
        // El paso llega cuando el lector espera en la cola vacía, y el scope se cancela antes de que lo tome: la cola ya se lo
        // entregó, y sin más se perdería sin rastro.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val pantalla = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val l = leccion(t, almacen, scope = pantalla)
            assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), p)
            yield() // el lector arranca y se queda esperando en la cola vacía
            assertTrue(l.pasoObservado(paso(1)), p)
            pantalla.cancel()
            val r = l.terminar(listo, sinVideo)
            assertEquals(listOf("b1" to false), r.pasos.map { it.paso.selector.substringAfterLast('/') to it.enviado }, "$p · el paso que la cola entregó al lector cancelado se perdió")
            assertTrue(t.llamadas.none { it.esPaso }, "$p · viajó un paso: ${t.llamadas}")
            assertFalse("aprendí" in r.mensaje, "$p · se anunció como aprendida: ${r.mensaje}")
        }
        // Se muere sin nada pendiente: lo que se observe después no va a ningún lado, y la lección lo dice.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val pantalla = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val l = leccion(t, almacen, scope = pantalla)
            assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), p)
            pantalla.cancel()
            for (n in 1..3) assertFalse(l.pasoObservado(paso(n)), "$p · con el lector muerto, aceptó el paso $n")
            val r = l.terminar(listo, sinVideo)
            assertTrue(t.llamadas.none { it.esPaso }, "$p · con el lector muerto, viajó un paso: ${t.llamadas}")
            val motivo = leida(almacen, r.leccion).motivo.orEmpty()
            assertTrue("lector" in motivo, "$p · la lección no dice que el lector se detuvo: «$motivo»")
            assertFalse("aprendí" in r.mensaje, "$p · se anunció como aprendida: ${r.mensaje}")
        }
        // Con el scope ya cancelado al empezar: no se enseña, se dice por qué y no se abre nada en Graph.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val pantalla = CoroutineScope(coroutineContext + Job(coroutineContext.job)).apply { cancel() }
            val l = leccion(t, almacen, scope = pantalla)
            val no = assertIs<Arranque.NoSePuede>(l.empezar(registro, "Registrar paciente"), "$p · con el scope cancelado, empezó a enseñar")
            assertTrue("cancelad" in no.motivo && '\n' !in no.motivo, "$p · el motivo no dice por qué en una línea: «${no.motivo}»")
            assertTrue(t.llamadas.isEmpty(), "$p · con el scope cancelado, abrió una sesión en Graph: ${t.llamadas}")
            nadaAbierto("$p · scope cancelado", l, t, almacen, cronica)
        }
        // El scope se cancela mientras Graph abre la sesión: tampoco se enseña, con un lector que nació muerto.
        run {
            val cronica = Cronica()
            val pantalla = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val t = TransporteDeRutas(cronica) { l -> if (l.esSesion) pantalla.cancel(); sano(l) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen, scope = pantalla)
            val no = assertIs<Arranque.NoSePuede>(l.empezar(registro, "Registrar paciente"), "$p · el scope se canceló mientras Graph abría y empezó a enseñar")
            assertTrue("cancelad" in no.motivo && '\n' !in no.motivo, "$p · «${no.motivo}»")
            nadaAbierto("$p · scope cancelado al abrir", l, t, almacen, cronica)
        }
    }

    @Test
    fun promesa418() = demo(promesa(418)) {
        val p = promesa(418)
        val vercel = TransportReply(504, """{"error":"FUNCTION_INVOCATION_TIMEOUT"}""")
        // Cancelado mientras se procesa el video, que tarda minutos (un viewModelScope se cancela al salir de la pantalla): la
        // nota y el cierre salen igual, el video queda para reprocesar y la cancelación sigue su curso.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            val lineas = mutableListOf<String>()
            val l = leccion(t, almacen, lineas = lineas)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            l.nota("es para pacientes nuevos")
            val videoEmpezo = CompletableDeferred<Unit>()
            val cerrando = launch { l.terminar(listo) { cronica += "video"; videoEmpezo.complete(Unit); awaitCancellation() } }
            videoEmpezo.await()
            cerrando.cancelAndJoin()
            assertTrue(cerrando.isCancelled, "$p · la cancelación no siguió su curso")
            assertEquals(1, t.llamadas.count { it.esNota }, "$p · cancelado en el video, la nota no viajó: ${t.llamadas}")
            assertEquals(1, t.llamadas.count { it.esCierre }, "$p · cancelado en el video, la sesión quedó sin cerrar: ${t.llamadas}")
            assertTrue(t.llamadas.indexOfFirst { it.esNota } < t.llamadas.indexOfFirst { it.esCierre }, "$p · la nota viajó después del cierre: ${t.llamadas}")
            assertTrue(Leccion.ruta(Leccion.CARPETA_LECCIONES, "ses-1") in almacen.archivos, "$p · la lección no llegó a disco")
            val marca = LeccionJson.decodeFromString(VideoParaReprocesar.serializer(), assertNotNull(almacen.en(Leccion.CARPETA_VIDEOS).values.singleOrNull(), "$p · el video cancelado no quedó para reprocesar"))
            assertTrue("cancel" in marca.motivo, "$p · la marca no dice por qué: «${marca.motivo}»")
            val otra = lanzaExacto<IllegalStateException>("$p · terminar dos veces") { l.terminar(listo, sinVideo) }
            assertTrue("terminada" in otra.message.orEmpty(), "$p · la lección quedó trabada: ${otra.message}")
            assertTrue(lineas.any { "■" in it && "aprendí" in it }, "$p · el cierre no dejó su resultado en el log: $lineas")
        }
        // Cancelado mientras Graph cierra, y Graph no alcanza: los tres intentos terminan y queda el pendiente, que el arranque cierra.
        run {
            val cronica = Cronica()
            val almacen = AlmacenEnMemoria(cronica)
            val cierreEmpezo = CompletableDeferred<Unit>()
            val soltar = CompletableDeferred<Unit>()
            val esperas = mutableListOf<Long>()
            val t = TransporteDeRutas(cronica) { l -> if (l.esCierre) { cierreEmpezo.complete(Unit); soltar.await(); vercel } else sano(l) }
            val l = leccion(t, almacen, esperas = esperas)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            val cerrando = launch { l.terminar(listo, sinVideo) }
            cierreEmpezo.await()
            cerrando.cancel()
            soltar.complete(Unit)
            cerrando.join()
            assertTrue(cerrando.isCancelled, "$p · la cancelación no siguió su curso")
            assertEquals(3, t.llamadas.count { it.esCierre }, "$p · cancelado al cerrar, el cierre no terminó sus intentos: ${t.llamadas}")
            assertEquals(listOf(3_000L, 8_000L), esperas, p)
            assertEquals(1, almacen.en(Leccion.CARPETA_PENDIENTES).size, "$p · cancelado al cerrar, no quedó pendiente: ${almacen.archivos.keys}")
            val arranque = leccion(TransporteDeRutas(cronica) { sano(it) }, almacen).reintentarPendientes()
            assertEquals(1, arranque.cerrados, "$p · el arranque no supo cerrar el pendiente")
        }
        // Cancelado mientras se vacía la cola, con un paso colgado: lo que no salió cuenta como no enviado, la lección llega a
        // disco, el video ni se empieza (queda para reprocesar) y la sesión se cierra.
        run {
            val cronica = Cronica()
            val enVuelo = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(cronica) { l -> if (l.esPaso && l.selector.endsWith("/b2")) { enVuelo.complete(Unit); awaitCancellation() } else sano(l) }
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(t, almacen)
            l.empezar(registro, "Registrar paciente")
            for (n in 1..3) l.pasoObservado(paso(n))
            enVuelo.await()
            // terminar corre hasta quedarse esperando la cola: ya está cerrando cuando llega la cancelación.
            val cerrando = launch(start = CoroutineStart.UNDISPATCHED) { l.terminar(listo) { cronica += "video"; ResumenDeVideo("registra pacientes", null) } }
            cerrando.cancelAndJoin()
            assertTrue(cerrando.isCancelled, "$p · la cancelación no siguió su curso")
            assertEquals(1, t.llamadas.count { it.esCierre }, "$p · cancelado al vaciar la cola, la sesión quedó sin cerrar: ${t.llamadas}")
            assertTrue(t.llamadas.indexOfLast { it.esPaso } < t.llamadas.indexOfFirst { it.esCierre }, "$p · un paso viajó después de cerrar: ${t.llamadas}")
            assertFalse("video" in cronica.eventos, "$p · cancelado antes del video, lo empezó igual")
            assertEquals(1, almacen.en(Leccion.CARPETA_VIDEOS).size, "$p · el video que no se procesó no quedó para reprocesar")
            val enDisco = leida(almacen, Leccion.ruta(Leccion.CARPETA_LECCIONES, "ses-1"))
            assertEquals(listOf(true, false, false), enDisco.pasos.map { it.enviado }, "$p · pasos en disco")
            assertTrue(enDisco.pasos.drop(1).all { "cancel" in it.motivo.orEmpty() }, "$p · motivos: ${enDisco.pasos.map { it.motivo }}")
        }
    }
}
