package graph.core.contrato

import graph.core.domain.GraphLog
import graph.core.graph.TransportReply
import graph.core.graph.TurnTransport
import graph.core.graph.learning.Almacen
import graph.core.graph.learning.Arranque
import graph.core.graph.learning.Cierre
import graph.core.graph.learning.CierrePendiente
import graph.core.graph.learning.GraphException
import graph.core.graph.learning.IdentidadDePantalla
import graph.core.graph.learning.LearningClient
import graph.core.graph.learning.Leccion
import graph.core.graph.learning.LeccionEnDisco
import graph.core.graph.learning.LeccionJson
import graph.core.graph.learning.ResultadoDeLeccion
import graph.core.graph.learning.ResumenDeVideo
import graph.core.graph.learning.StepRequest
import graph.core.graph.learning.StepToRead
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 * CONTRATO 004 · LA LECCIÓN (docs/specs/004-lo-ensenado-vive-en-graph.md, fase 4A2 y sus revisiones: 417-422 y 424-426).
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
            419 to "Ninguna línea de log de la enseñanza lleva lo que el usuario dijo, escribió o nombró: solo ids, cantidades, estados y códigos.",
            420 to "Un cierre cancelado devuelve el control en un tiempo acotado, y si el proceso muere a mitad del cierre, al arrancar queda un pendiente que lo termina.",
            421 to "Antes de reintentar un cierre pendiente, el arranque pregunta a Graph si ya lo cerró: si lo cerró no vuelve a cerrarlo ni a cobrarlo, y si no se sabe lo intenta como siempre.",
            422 to "Mientras una lección se cierra, ningún arranque cierra su sesión: a Graph no le llega un paso ni una nota después de su finish.",
            424 to "Dos arranques a la vez en el mismo proceso nunca reintentan el mismo cierre dos veces: mientras uno corre, el otro no llama a Graph ni toca el disco, y si el que corre se cancela, el siguiente arranque corre.",
            425 to "Descartar una demostración que ya abrió sesión en Graph la borra allí con un solo DELETE en segundo plano: 2xx y 404 cuentan como hecho, otro fallo deja en el log solo el id y el status, sin sesión no se llama, y su cierre pendiente se borra antes, sin que ningún arranque la cierre después del DELETE.",
            426 to "Procesar un video es una sola llamada por acción del usuario: ni un 5xx, ni un 429, ni una lectura agotada, ni el tope la repiten, y la lección cuyo video falla queda para reprocesar a mano, sin reintento al arrancar.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** El reloj de pared de la lección: un número fijo, para leerlo tal cual en disco. */
        const val AHORA = 1_789_054_200_000L
        const val BASE = "https://graph.test"

        /**
         * Un workflow con la forma que le da Graph (`e9d0d44`): `Workflow.toJSON` (`src/domain/entities/Workflow.js:134-158`) sobre las
         * filas de `getWorkflowRows` (`Neo4jWorkflowRepository.js:132-175`), con sus ramas (`WorkflowCatalog.js:121-137`). Las
         * variables van en LISTA de objetos (`Workflow.js:35-92`), las fechas como entero Neo4j `{low, high}` sin convertir
         * (`Neo4jDriver.js:126`), y lo que `finish` todavía no cerró trae `completedAt` nulo (`Neo4jWorkflowRepository.js:605`).
         */
        fun workflowDeGraph(id: String, status: String, completado: Boolean = status == "done", creadoMs: Long = AHORA): String {
            fun neo4j(ms: Long) = """{"low":${(ms and 0xFFFFFFFFL).toInt()},"high":${(ms shr 32).toInt()}}"""
            return """{"id":"$id","description":"Registrar paciente","summary":"${if (completado) "registra pacientes" else ""}","executionGuide":"",
                "status":"$status","scope":"private","ownerId":"api-client:android","appId":"dev-1","sourceUrl":"android://com.x/RegistroActivity",
                "sourceOrigin":"android://com.x","sourcePathname":"/RegistroActivity","sourceTitle":"Registro","contextNotes":[],
                "createdAt":${neo4j(creadoMs)},"updatedAt":null,"completedAt":${if (completado) neo4j(creadoMs + 60_000) else "null"},
                "publishedFromWorkflowId":"","publishedByOwnerId":"","publishedAt":null,
                "steps":[{"actionType":"input","selector":"a11y:id=com.x:id/nombre","value":"Ana","url":"","explanation":"","label":"Nombre",
                  "controlType":"text","selectedValue":"","selectedLabel":"","semanticTarget":"","surfaceSection":"","surfaceHints":null,
                  "nodeKey":"","nodePath":"","nodeAction":"","allowedOptions":[],"valueMode":"fixed","bindTo":"","valueModeExplicit":false,"stepOrder":1}],
                "variables":[{"name":"input_1","selector":"a11y:id=com.x:id/nombre","controlType":"text","actionType":"input","kind":"field-value",
                  "sourceStep":1,"defaultValue":"Ana","fieldLabel":"Nombre","selectedLabel":"","allowedOptions":[],"prompt":"Value for Nombre"}],
                "totalSteps":1,"branches":[]}"""
        }
    }

    /* ---------- El mapa a mano: crónica, transporte por rutas y almacén en memoria ---------- */

    /** Lo que pasó, en orden: «red POST /api/v1/…», «disco escribe lecciones/…», «video». */
    class Cronica {
        val eventos = mutableListOf<String>()
        operator fun plusAssign(evento: String) { eventos += evento }
        fun primero(que: (String) -> Boolean) = eventos.indexOfFirst(que)
        fun ultimo(que: (String) -> Boolean) = eventos.indexOfLast(que)
    }

    /** Una llamada como la vio el transporte; [timeout] es lo que le quedaba al tope de la llamada al salir. */
    class Llamada(val metodo: String, val ruta: String, val body: String?, val timeout: Duration? = null) {
        val json: JsonObject get() = Json.parseToJsonElement(body ?: error("$metodo $ruta no llevó cuerpo")).jsonObject
        val esSesion get() = ruta == "/api/v1/learning/sessions"
        val esPaso get() = ruta.endsWith("/steps")
        val esNota get() = ruta.endsWith("/context-notes")
        val esCierre get() = ruta.endsWith("/finish")
        /** `GET /api/v1/workflows/:id`: el arranque pregunta si Graph ya cerró la sesión (421). */
        val esWorkflow get() = metodo == "GET" && ruta.startsWith("/api/v1/workflows/")
        /** `DELETE /api/v1/workflows/:id`: una demostración descartada se borra en Graph (425). */
        val esBorrado get() = metodo == "DELETE"
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
            val llamada = Llamada(method, url.removePrefix(BASE), body, timeout)
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

    /**
     * El disco en memoria. [falla] decide qué rutas se caen a mitad: lo escrito hasta ahí no queda, que es lo que promete el puerto.
     * [alBorrar] ve el disco justo antes de cada borrado: es lo que quedaría si el proceso muriera ahí.
     */
    class AlmacenEnMemoria(
        private val cronica: Cronica,
        private val falla: (String) -> Boolean = { false },
        private val alBorrar: (ruta: String, archivos: Map<String, String>) -> Unit = { _, _ -> },
    ) : Almacen {
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
            alBorrar(ruta, archivos.toMap())
            archivos.remove(ruta)
            cronica += "disco borra $ruta"
        }

        fun en(carpeta: String) = archivos.filterKeys { it.startsWith("$carpeta/") }
    }

    /**
     * Un Graph con la memoria y las reglas de `e9d0d44`, para juzgar lo que cuesta. La sesión se abre en `recording` con su id como
     * workflow (`registerPublicApiRoutes.js:459-465`); un paso o una nota se guardan TAMBIÉN sobre una sesión ya cerrada
     * (`LearningSessionService.js:40-49`, `WorkflowLearner.js:49-67`); `finish` responde 200, la deja `done` y post-procesa con el
     * LLM CADA vez, aunque ya estuviera cerrada (`WorkflowLearner.js:69-121`, `Neo4jWorkflowRepository.js:605`), y [cobros] lo
     * cuenta; `DELETE` la borra (`WorkflowCatalog.js:267-276`); lo que no existe es 404 (`WorkflowLearner.js:23-36`). [llegadas] es lo que le llegó, en orden, por cualquier transporte.
     */
    class GraphDeVerdad(ids: List<String> = listOf("ses-1")) {
        private val porAbrir = ArrayDeque(ids)
        private val estados = linkedMapOf<String, String>()
        private val cobrado = mutableMapOf<String, Int>()
        val llegadas = mutableListOf<String>()

        fun abierta(id: String) { estados[id] = "recording" }
        fun cerrada(id: String) { estados[id] = "done"; cobrado[id] = cobros(id) + 1 }
        fun cobros(id: String) = cobrado[id] ?: 0
        /** ¿Sigue en Graph? Lo que se borra deja de salir en `GET /workflows` y de llegarle al cerebro. */
        fun existe(id: String) = id in estados

        fun responder(l: Llamada): TransportReply {
            llegadas += l.toString()
            val id = l.ruta.removePrefix("/api/v1/learning/sessions/").removePrefix("/api/v1/workflows/").substringBefore('/')
            return when {
                l.esSesion -> porAbrir.removeFirst().let { abierta(it); TransportReply(201, """{"session":{"id":"$it","workflow_id":"$it","recording":true}}""") }
                id !in estados -> TransportReply(404, """{"error":"Workflow not found"}""")
                l.esPaso -> TransportReply(201, """{"step":{"step_order":1,"stepOrder":1}}""")
                l.esNota -> TransportReply(201, """{"ok":true}""")
                l.esCierre -> { cerrada(id); TransportReply(200, """{"workflow_id":"$id","summary":"registra pacientes","workflow":${workflowDeGraph(id, "done")}}""") }
                l.esWorkflow -> TransportReply(200, """{"workflow":${workflowDeGraph(id, estados.getValue(id))}}""")
                // `DELETE /workflows/:id` borra el workflow con sus pasos (`Neo4jWorkflowRepository.js:735-749`): lo que llegue después es 404.
                l.esBorrado -> { estados.remove(id); TransportReply(200, """{"deleted":true,"id":"$id"}""") }
                else -> error("ruta no prevista: $l")
            }
        }
    }

    private fun ok(json: String) = TransportReply(200, json)

    /** Un Graph que dice que sí a todo, como el del día bueno. Una sesión por la que se pregunta está abierta: el cierre no salió. */
    private fun sano(l: Llamada, sesion: String = "ses-1"): TransportReply = when {
        l.esSesion -> ok("""{"session":{"id":"$sesion","workflow_id":"wf-$sesion","recording":true}}""")
        l.esPaso -> ok("""{"step":{"step_order":1}}""")
        l.esNota -> ok("{}")
        l.esCierre -> ok("""{"workflow_id":"wf-$sesion","summary":"registra pacientes"}""")
        l.esWorkflow -> ok("""{"workflow":${workflowDeGraph(l.ruta.substringAfterLast('/'), "recording")}}""")
        l.esBorrado -> ok("""{"deleted":true,"id":"${l.ruta.substringAfterLast('/')}"}""")
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
        /** Lo que escriben en el log la lección Y su cliente, como en la app, donde los dos van a `LogBus`. */
        lineas: MutableList<String> = mutableListOf(),
        ahoraMs: () -> Long = { AHORA },
        /** El reloj de las llamadas y del tope del arranque: avanza con las esperas del cliente y con lo que la prueba sume. */
        reloj: TestTimeSource = TestTimeSource(),
        /** Cómo espera el tope un cierre cancelado: la prueba decide cuándo vence. Por defecto, la espera de verdad. */
        esperarTope: suspend (Duration) -> Unit = { delay(it) },
    ): Leccion {
        val log = GraphLog { tag, m -> lineas += "[$tag] $m" }
        val cliente = LearningClient(
            transport = transporte,
            credentials = key,
            baseUrl = { "$BASE/" },
            email = { null },
            deviceId = { "dev-1" },
            log = log,
            sleep = { esperas += it; reloj += it.milliseconds },
            timeSource = reloj,
        )
        return Leccion(
            cliente = cliente,
            almacen = almacen,
            scope = scope,
            appId = "dev-1",
            ahoraMs = ahoraMs,
            log = log,
            avisar = { avisos += it },
            topeDeVaciado = topeDeVaciado,
            reloj = reloj,
            esperarTope = esperarTope,
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
            // Primero pregunta si Graph ya la cerró (421): sigue abierta, así que un solo finish.
            assertEquals(listOf("GET /api/v1/workflows/wf-ses-1", "POST /api/v1/learning/sessions/ses-1/finish"), t.llamadas.map { it.toString() }, p)
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
            assertEquals(1, arranque.llamadas.count { it.esCierre }, "$p · HTTP ${alReintentar.status} al reintentar: ${arranque.llamadas}")
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
                listOf("POST /api/v1/learning/sessions/ses-a/finish", "POST /api/v1/learning/sessions/ses-b/finish"), t.llamadas.filter { it.esCierre }.map { it.toString() },
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
        // Al arrancar, con tope: hasta MAX_PENDIENTES_POR_ARRANQUE pendientes y TOPE_DE_ARRANQUE en total. Con Graph caído, N
        // pendientes × 90 s se comerían el arranque. El resto queda para el siguiente, que empieza por los que no se intentaron.
        suspend fun sietePendientes() = AlmacenEnMemoria(Cronica()).apply {
            for (n in 1..7) escribirEntero(Leccion.ruta(Leccion.CARPETA_PENDIENTES, "ses-t$n"), LeccionJson.encodeToString(CierrePendiente.serializer(), CierrePendiente("ses-t$n", "wf-t$n", AHORA)))
        }
        fun sesionesDe(t: TransporteDeRutas) = t.llamadas.filter { it.esCierre }.map { it.ruta.substringAfter("/sessions/").substringBefore('/') }
        run {
            val disco = sietePendientes()
            val t = TransporteDeRutas(Cronica()) { vercel }
            val r = leccion(t, disco).reintentarPendientes()
            assertEquals((1..Leccion.MAX_PENDIENTES_POR_ARRANQUE).map { "ses-t$it" }, sesionesDe(t), "$p · al arrancar con 7 pendientes no hubo tope de cantidad")
            assertEquals(Triple(0, 7, 0), Triple(r.cerrados, r.siguen, r.descartados), "$p · con tope de cantidad: cerrados, siguen y descartados")
            assertEquals(7, disco.en(Leccion.CARPETA_PENDIENTES).size, "$p · un pendiente sin intentar se borró")
            val siguiente = TransporteDeRutas(Cronica()) { vercel }
            leccion(siguiente, disco).reintentarPendientes()
            assertEquals(listOf("ses-t6", "ses-t7"), sesionesDe(siguiente).take(2), "$p · el arranque siguiente no empezó por los que quedaron sin intentar")
        }
        run {
            val disco = sietePendientes()
            val reloj = TestTimeSource()
            // Cada cierre tarda 50 s en el reloj inyectado: tras el tercero van 150 s, más que los 2 min del arranque.
            val t = TransporteDeRutas(Cronica()) { l -> if (l.esCierre) reloj += 50.seconds; vercel }
            val r = leccion(t, disco, reloj = reloj).reintentarPendientes()
            assertEquals(listOf("ses-t1", "ses-t2", "ses-t3"), sesionesDe(t), "$p · al arrancar no hubo tope de tiempo")
            assertEquals(7, r.siguen, p)
        }
        // La consulta de si Graph ya la cerró (421) también gasta el tope: tiene el suyo, corto, y pasado el tope del arranque el cierre
        // ya no empieza. Cada consulta tarda 15 s y cada cierre 40 s: la tercera consulta acaba a los 125 s y su cierre queda para el siguiente.
        run {
            val disco = sietePendientes()
            val reloj = TestTimeSource()
            val t = TransporteDeRutas(Cronica()) { l ->
                if (l.esWorkflow) { reloj += 15.seconds; sano(l) } else { reloj += 40.seconds; vercel }
            }
            val r = leccion(t, disco, reloj = reloj).reintentarPendientes()
            val consultas = t.llamadas.filter { it.esWorkflow }
            assertEquals(listOf("wf-t1", "wf-t2", "wf-t3"), consultas.map { it.ruta.substringAfterLast('/') }, "$p · con consultas que tardan: ${t.llamadas}")
            assertEquals(listOf("ses-t1", "ses-t2"), sesionesDe(t), "$p · la consulta no contó en el tope del arranque: un cierre empezó pasados ${Leccion.TOPE_DE_ARRANQUE}")
            assertEquals(Triple(0, 7, 0), Triple(r.cerrados, r.siguen, r.descartados), "$p · con consultas que tardan: cerrados, siguen y descartados")
            assertEquals(7, disco.en(Leccion.CARPETA_PENDIENTES).size, "$p · un pendiente sin cerrar se borró")
            // Y su tope es el corto, no los 90 s de una llamada: una consulta colgada no se come el arranque.
            assertTrue(LearningClient.TOPE_DE_CONSULTA < LearningClient.TOPE_GENERAL, p)
            assertEquals(consultas.map { LearningClient.TOPE_DE_CONSULTA }, consultas.map { it.timeout }, "$p · la consulta no viajó con su tope corto")
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
        // Descartada con un paso en vuelo, dos en cola y una nota: no sale nada más que el borrado de la sesión (425). El scope interno
        // espera al lector y a ese borrado, que corre en segundo plano.
        val borrado = "DELETE /api/v1/workflows/wf-ses-1"
        run {
            val cronica = Cronica()
            val enVuelo = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(cronica) { l -> if (l.esPaso) { enVuelo.complete(Unit); awaitCancellation() } else sano(l) }
            val almacen = AlmacenEnMemoria(cronica)
            val publicado = listOf("POST /api/v1/learning/sessions", "POST /api/v1/learning/sessions/ses-1/steps")
            fun publicadas() = t.llamadas.filterNot { it.esBorrado }.map { it.toString() }
            coroutineScope {
                val l = leccion(t, almacen)
                l.empezar(registro, "Registrar paciente")
                for (n in 1..3) l.pasoObservado(paso(n))
                l.nota("me equivoqué de pantalla")
                enVuelo.await()
                assertTrue(l.descartar(), "$p · descartar mientras se graba no dijo que descartó")
                assertEquals(publicado, publicadas(), "$p · descartada, siguió publicando")
                assertFalse(l.pasoObservado(paso(4)), "$p · descartada, aceptó un paso")
                l.nota("y otra cosa")
                lanzaExacto<IllegalStateException>("$p · terminar una demostración descartada") { l.terminar(listo) { cronica += "video"; null } }
                assertTrue(l.descartar(), "$p · descartar dos veces: dejó de estar descartada")
            }
            assertEquals(publicado + borrado, t.llamadas.map { it.toString() }, "$p · después de descartar, algo salió")
            assertTrue(almacen.escrituras.isEmpty(), "$p · descartada, escribió en disco: ${almacen.escrituras}")
            assertFalse("video" in cronica.eventos, "$p · descartada, procesó el video")
        }
        // Descartada antes de que el lector mande nada: ni un paso.
        run {
            val cronica = Cronica()
            val t = TransporteDeRutas(cronica) { sano(it) }
            val almacen = AlmacenEnMemoria(cronica)
            coroutineScope {
                val l = leccion(t, almacen)
                l.empezar(registro, "Registrar paciente")
                l.pasoObservado(paso(1))
                l.pasoObservado(paso(2))
                assertTrue(l.descartar(), p)
            }
            assertEquals(listOf("POST /api/v1/learning/sessions", borrado), t.llamadas.map { it.toString() }, "$p · descartada antes de mandar, mandó")
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

    /* ---------- La 419: lo que el usuario dijo, escribió o nombró ---------- */

    /** Lo sensible de la demostración de la 419: nada de esto puede aparecer en una línea de log, venga de donde venga. */
    private val sensibles = listOf("Ana Pérez", "CC 1037", "Cédula", "Paciente VIP", "Lucía Gómez", "VIH", "Marta Ruiz", "diabética", "Jorge Díaz")
    private val descripcionSensible = "Registrar a Ana Pérez CC 1037"
    private val pasosSensibles = listOf(
        StepRequest(actionType = "input", selector = "a11y:id=com.x:id/nombre", label = "Nombre de Ana Pérez", controlType = "text", value = "Ana Pérez", semanticTarget = "nombre del Paciente VIP"),
        StepRequest(actionType = "select", selector = "a11y:id=com.x:id/documento", label = "Documento", controlType = "select", value = "CC 1037", selectedValue = "CC 1037", selectedLabel = "Cédula de ciudadanía"),
        StepRequest(actionType = "click", selector = "a11y:id=com.x:id/guardar", label = "Guardar a Ana Pérez", controlType = "button"),
    )
    private val notaSensible = "la señora Lucía Gómez tiene VIH"
    private val resumenSensible = "registra a Marta Ruiz, diabética"

    /**
     * Una demostración con lo sensible, contra el cliente REAL —que procesa también el video— y con el log de los dos en [lineas],
     * como en la app. Con [ecos], Graph hace lo que hace un error de validación: rechaza un paso repitiendo su valor, responde a
     * otro algo ilegible que lo trae dentro, devuelve la nota en el error y manda un video procesado que no se lee.
     */
    private suspend fun CoroutineScope.demoSensible(
        sesion: String,
        lineas: MutableList<String>,
        almacen: AlmacenEnMemoria,
        ecos: Boolean,
        cierre: (Llamada) -> TransportReply,
        scope: CoroutineScope = this,
        antesDeTerminar: () -> Unit = {},
    ): ResultadoDeLeccion {
        val t = TransporteDeRutas(Cronica()) { l ->
            when {
                ecos && l.esPaso && l.selector.endsWith("/nombre") -> TransportReply(400, """{"error":"value «Ana Pérez» no cabe en «Nombre de Ana Pérez»"}""")
                ecos && l.esPaso && l.selector.endsWith("/documento") -> ok("""{"step":{"step_order":"CC 1037 Cédula"}}""")
                ecos && l.esNota -> TransportReply(500, """{"error":"nota rechazada: $notaSensible · $resumenSensible"}""")
                l.esCierre -> cierre(l)
                l.ruta.endsWith("/process-video") ->
                    if (ecos) ok("""{"summary":"$resumenSensible","notes":"Jorge Díaz"}""")
                    else ok("""{"summary":"$resumenSensible","interpretation":{"campos":[{"campo":"Nombre","valor":"Jorge Díaz"}]}}""")
                else -> sano(l, sesion)
            }
        }
        val paraElVideo = LearningClient(t, { "miracle_k" }, { "$BASE/" }, { null }, { "dev-1" }, log = GraphLog { tag, m -> lineas += "[$tag] $m" })
        val l = leccion(t, almacen, lineas = lineas, scope = scope)
        assertIs<Arranque.Ensenando>(l.empezar(registro, descripcionSensible), "419 · $sesion")
        for (paso in pasosSensibles) assertTrue(l.pasoObservado(paso), "419 · $sesion")
        l.nota(notaSensible)
        antesDeTerminar()
        return l.terminar(listo) { paraElVideo.processVideo("files/demo", "u-1").let { ResumenDeVideo(it.summary, it.interpretation) } }
    }

    @Test
    fun promesa419() = demo(promesa(419)) {
        val p = promesa(419)
        val lineas = mutableListOf<String>()
        val almacen = AlmacenEnMemoria(Cronica())
        val cerrada = ok("""{"workflow_id":"wf-419","summary":"$descripcionSensible"}""")
        val cierres = listOf<Triple<String, Boolean, (Llamada) -> TransportReply>>(
            Triple("cerrada entera", false, { cerrada }),
            Triple("cerrada con ecos", true, { cerrada }),
            Triple("pendiente", true, { TransportReply(504, """{"error":"post-procesando «$descripcionSensible»"}""") }),
            Triple("fallida", true, { TransportReply(400, """{"error":"el workflow «$descripcionSensible» ya existe"}""") }),
            Triple("incierta", true, { TransportReply(-1, "Read timed out") }),
        )
        val resultados = cierres.mapIndexed { i, (_, ecos, cierre) -> demoSensible("ses-419-$i", lineas, almacen, ecos, cierre) }
        assertEquals(listOf(Cierre.CERRADA, Cierre.CERRADA, Cierre.PENDIENTE, Cierre.FALLIDO, Cierre.INCIERTO), resultados.map { it.cierre }, "$p · ${cierres.map { it.first }}")
        assertTrue(descripcionSensible in resultados[0].mensaje, "$p · el mensaje para el usuario, que no es log, perdió el nombre: ${resultados[0].mensaje}")
        assertEquals(listOf(false, false, true), resultados[1].pasos.map { it.enviado }, "$p · con ecos: ${resultados[1].pasos.map { it.motivo }}")
        // El lector se muere con un motivo que nombra al paciente: el motivo va a la lección, no al log.
        run {
            val pantalla = CoroutineScope(coroutineContext + Job(coroutineContext.job))
            val r = demoSensible(
                "ses-419-lector", lineas, almacen, ecos = false, cierre = { cerrada }, scope = pantalla,
                antesDeTerminar = { pantalla.cancel("salió de la ficha de Ana Pérez") },
            )
            assertFalse("aprendí" in r.mensaje, "$p · con el lector muerto se anunció como aprendida: ${r.mensaje}")
        }
        // Al arrancar, Graph ya no conoce la sesión pendiente y lo dice con el nombre dentro.
        run {
            val t = TransporteDeRutas(Cronica()) { TransportReply(400, """{"error":"la sesión de «$descripcionSensible» ya no existe"}""") }
            val r = leccion(t, almacen, lineas = lineas).reintentarPendientes()
            assertEquals(listOf("POST /api/v1/learning/sessions/ses-419-2/finish"), t.llamadas.filter { it.esCierre }.map { it.toString() }, "$p · al arrancar solo quedaba el pendiente")
            assertEquals(1, r.descartados, p)
        }
        // Lo que la enseñanza por video llama fuera de la lección: interpretar la demo, pedir dónde subir y alinear.
        run {
            val t = Contrato004EnsenadoEnGraph.TransporteGuionado(
                TransportReply(500, """{"error":"no entendí «$notaSensible»"}"""),
                ok("""{"interpretation":"Jorge Díaz","campos":[}"""),
                ok("""{"geminiUploadUrl":"https://upload.test/x","archiveError":"el video de Ana Pérez no se archivó"}"""),
                TransportReply(500, """{"error":"el workflow «$descripcionSensible» no tiene superficie"}"""),
            )
            val c = LearningClient(t, { "miracle_k" }, { "$BASE/" }, { null }, { "dev-1" }, log = GraphLog { tag, m -> lineas += "[$tag] $m" })
            val demo = listOf(StepToRead(1, "Nombre", "Ana Pérez", notaSensible))
            assertNull(c.interpretSteps("android://com.x/Registro", demo), "$p · interpretar con un 500")
            assertNull(c.interpretSteps("android://com.x/Registro", demo), "$p · interpretar con algo ilegible")
            assertEquals("https://upload.test/x", c.uploadToken(10, "u-1").geminiUploadUrl, p)
            assertFalse(c.prependAlignment("wf-419"), p)
        }

        val fugas = lineas.filter { linea -> sensibles.any { it in linea } }
        assertTrue(fugas.isEmpty(), "$p · el log llevó lo que el usuario dijo, escribió o nombró: $fugas")
        // Y sigue diciendo qué pasó: cada cierre, con su sesión; el paso rechazado, con su código.
        val cierresEnLog = lineas.filter { "■" in it }
        assertEquals(6, cierresEnLog.size, "$p · cada cierre deja su línea: $cierresEnLog")
        assertTrue(cierresEnLog.all { "ses-419" in it }, "$p · la línea del cierre no dice de qué sesión: $cierresEnLog")
        assertTrue(lineas.any { "no llegó a graph" in it && "HTTP 400" in it }, "$p · el paso rechazado no dejó su código en el log: $lineas")
    }

    @Test
    fun promesa420() = demo(promesa(420)) {
        val p = promesa(420)
        val pendiente = Leccion.ruta(Leccion.CARPETA_PENDIENTES, "ses-1")
        fun finish(s: String) = "POST /api/v1/learning/sessions/$s/finish"

        // Sale bien: el pendiente provisional se escribe antes del primer finish y se borra cuando finish sale. Sin cancelar, no hay tope.
        run {
            val cronica = Cronica()
            val almacen = AlmacenEnMemoria(cronica)
            val topes = mutableListOf<Duration>()
            val l = leccion(TransporteDeRutas(cronica) { sano(it) }, almacen, esperarTope = { topes += it })
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            assertEquals(Cierre.CERRADA, l.terminar(listo, sinVideo).cierre, p)
            val escribe = cronica.primero { it == "disco escribe $pendiente" }
            val cierre = cronica.primero { it.endsWith("/finish") }
            assertTrue(escribe in 0 until cierre, "$p · el pendiente provisional no se escribió antes del primer finish: ${cronica.eventos}")
            assertTrue(cronica.ultimo { it == "disco borra $pendiente" } > cierre, "$p · finish salió y el provisional no se borró: ${cronica.eventos}")
            assertTrue(almacen.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · cerrada, quedó un pendiente: ${almacen.archivos.keys}")
            assertTrue(topes.isEmpty(), "$p · sin cancelar, el cierre esperó un tope: $topes")
        }

        // El proceso muere a mitad del cierre —procesando el video, o con finish en vuelo—: con el disco de ese instante, el arranque
        // cierra la sesión.
        for (dondeMuere in listOf("procesando el video", "con finish en vuelo")) {
            val cronica = Cronica()
            val almacen = AlmacenEnMemoria(cronica)
            var discoAlMorir: Map<String, String>? = null
            val murio = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(cronica) { l ->
                if (l.esCierre && dondeMuere == "con finish en vuelo") { discoAlMorir = almacen.archivos.toMap(); murio.complete(Unit); awaitCancellation() } else sano(l)
            }
            val l = leccion(t, almacen, esperarTope = {})
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            val proceso = launch {
                l.terminar(listo) {
                    if (dondeMuere == "procesando el video") { discoAlMorir = almacen.archivos.toMap(); murio.complete(Unit); awaitCancellation() } else null
                }
            }
            murio.await()
            proceso.cancelAndJoin() // lo que haga después ya no es de este proceso: solo cuenta el disco de ese instante
            val disco = AlmacenEnMemoria(Cronica()).apply { archivos.putAll(assertNotNull(discoAlMorir, p)) }
            val arranque = TransporteDeRutas(Cronica()) { sano(it) }
            val r = leccion(arranque, disco).reintentarPendientes()
            assertEquals(listOf(finish("ses-1")), arranque.llamadas.filter { it.esCierre }.map { it.toString() }, "$p · murió $dondeMuere y al arrancar no quedó un pendiente que cierre la sesión: ${discoAlMorir?.keys}")
            assertEquals(1, r.cerrados, p)
            assertTrue(disco.en(Leccion.CARPETA_PENDIENTES).isEmpty(), p)
        }

        // Cancelado con Graph colgado en finish —la cancelación llega en el video, o con finish ya en vuelo—: el cierre espera
        // TOPE_DE_CIERRE_CANCELADO y no más. Vencido, suelta cancelado con el pendiente en disco, y el arranque lo cierra.
        for (cuando in listOf("en el video", "con finish en vuelo")) {
            val cronica = Cronica()
            val almacen = AlmacenEnMemoria(cronica)
            val llego = CompletableDeferred<Unit>()
            val pidio = CompletableDeferred<Duration>()
            val vence = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(cronica) { l ->
                if (l.esCierre) { if (cuando == "con finish en vuelo") llego.complete(Unit); awaitCancellation() } else sano(l)
            }
            val l = leccion(t, almacen, esperarTope = { pidio.complete(it); vence.await() })
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            val cerrando = launch { l.terminar(listo) { if (cuando == "en el video") { llego.complete(Unit); awaitCancellation() } else null } }
            llego.await()
            cerrando.cancel()
            assertEquals(Leccion.TOPE_DE_CIERRE_CANCELADO, withTimeoutOrNull(2.seconds) { pidio.await() }, "$p · cancelado $cuando, el cierre no se puso un tope")
            assertFalse(cerrando.isCompleted, "$p · cancelado $cuando, soltó sin esperar a que venciera el tope")
            vence.complete(Unit)
            assertNotNull(withTimeoutOrNull(2.seconds) { cerrando.join() }, "$p · cancelado $cuando, vencido el tope no devolvió el control")
            assertTrue(cerrando.isCancelled, "$p · cancelado $cuando, la cancelación no siguió su curso")
            assertEquals(1, t.llamadas.count { it.esCierre }, "$p · cancelado $cuando: ${t.llamadas}")
            assertTrue(pendiente in almacen.archivos, "$p · cancelado $cuando, vencido el tope no quedó el pendiente: ${almacen.archivos.keys}")
            val arranque = TransporteDeRutas(Cronica()) { sano(it) }
            assertEquals(1, leccion(arranque, almacen).reintentarPendientes().cerrados, "$p · cancelado $cuando, el arranque no cerró lo que el tope dejó pendiente")
        }

        // Muere con finish ya salido y antes de borrar el provisional: al arrancar queda el pendiente de una sesión que Graph ya cerró.
        // Graph no la desconoce: un finish repetido responde 200 y post-procesa con el LLM otra vez (`WorkflowLearner.js:69-121`), nunca
        // 400. El arranque le pregunta antes si ya la cerró (421): ningún finish, un solo cobro, (1, 0, 0) y el siguiente no llama.
        run {
            var discoAlMorir: Map<String, String>? = null
            val cronica = Cronica()
            val graph = GraphDeVerdad()
            val almacen = AlmacenEnMemoria(cronica, alBorrar = { ruta, archivos -> if (ruta == pendiente && discoAlMorir == null) discoAlMorir = archivos })
            val l = leccion(TransporteDeRutas(cronica) { graph.responder(it) }, almacen)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            assertEquals(Cierre.CERRADA, l.terminar(listo, sinVideo).cierre, p)
            val disco = AlmacenEnMemoria(Cronica()).apply {
                archivos.putAll(assertNotNull(discoAlMorir, "$p · finish salió y no había provisional que borrar: ${cronica.eventos}"))
            }
            val arranque = TransporteDeRutas(Cronica()) { graph.responder(it) }
            val r = leccion(arranque, disco).reintentarPendientes()
            assertEquals(0, arranque.llamadas.count { it.esCierre }, "$p · murió tras un finish que salió y el arranque volvió a cerrar la sesión: ${arranque.llamadas}")
            assertEquals(1, graph.cobros("ses-1"), "$p · Graph post-procesó con el LLM la misma sesión más de una vez: ${graph.llegadas}")
            assertEquals(Triple(1, 0, 0), Triple(r.cerrados, r.siguen, r.descartados), "$p · ya cerrada en Graph: cerrados, siguen y descartados")
            assertTrue(disco.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · el pendiente de una sesión ya cerrada se quedó")
            val otro = TransporteDeRutas(Cronica()) { graph.responder(it) }
            leccion(otro, disco).reintentarPendientes()
            assertTrue(otro.llamadas.isEmpty(), "$p · el arranque siguiente volvió a llamar: ${otro.llamadas}")
        }
    }

    @Test
    fun promesa421() = demo(promesa(421)) {
        val p = promesa(421)
        val ses1 = Leccion.ruta(Leccion.CARPETA_PENDIENTES, "ses-1")
        suspend fun conPendiente() = AlmacenEnMemoria(Cronica()).apply {
            escribirEntero(ses1, LeccionJson.encodeToString(CierrePendiente.serializer(), CierrePendiente("ses-1", "ses-1", AHORA)))
        }
        val consulta = "GET /api/v1/workflows/ses-1"
        val cierre = "POST /api/v1/learning/sessions/ses-1/finish"

        // Graph ya la cerró (el finish salió y el proceso murió antes de borrar): un GET y nada más, sin volver a cobrar.
        run {
            val graph = GraphDeVerdad().apply { cerrada("ses-1") }
            val disco = conPendiente()
            val lineas = mutableListOf<String>()
            val t = TransporteDeRutas(Cronica()) { graph.responder(it) }
            val r = leccion(t, disco, lineas = lineas).reintentarPendientes()
            assertEquals(listOf(consulta), t.llamadas.map { it.toString() }, "$p · ya cerrada en Graph, el arranque volvió a cerrarla")
            assertEquals(1, graph.cobros("ses-1"), "$p · ya cerrada en Graph, cobró otra vez")
            assertEquals(Triple(1, 0, 0), Triple(r.cerrados, r.siguen, r.descartados), "$p · ya cerrada: cerrados, siguen y descartados")
            assertTrue(disco.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · el pendiente de una sesión ya cerrada se quedó")
            assertTrue(lineas.any { "ses-1" in it && "ya estaba cerrada" in it }, "$p · el log no dice que no la volvió a cerrar: $lineas")
        }
        // Basta `completedAt`, que también lo deja finish: con un status que no es `done`, tampoco se vuelve a cerrar.
        run {
            val disco = conPendiente()
            val t = TransporteDeRutas(Cronica()) { l -> if (l.esWorkflow) ok("""{"workflow":${workflowDeGraph("ses-1", "published", completado = true)}}""") else sano(l) }
            val r = leccion(t, disco).reintentarPendientes()
            assertEquals(listOf(consulta), t.llamadas.map { it.toString() }, "$p · con completedAt, el arranque volvió a cerrarla")
            assertEquals(1, r.cerrados, p)
        }
        // Y basta `status` `done`, aunque `completedAt` llegue nulo o no llegue: tampoco se vuelve a cerrar.
        for ((caso, cuerpo) in listOf(
            "completedAt nulo" to workflowDeGraph("ses-1", "done", completado = false),
            "sin completedAt" to workflowDeGraph("ses-1", "done", completado = false).replace("\"completedAt\":null,", ""),
        )) {
            assertTrue(("\"completedAt\":null" in cuerpo) == (caso == "completedAt nulo") && "\"status\":\"done\"" in cuerpo, "$p · el caso «$caso» no es el que dice")
            val disco = conPendiente()
            val t = TransporteDeRutas(Cronica()) { l -> if (l.esWorkflow) ok("""{"workflow":$cuerpo}""") else sano(l) }
            val r = leccion(t, disco).reintentarPendientes()
            assertEquals(listOf(consulta), t.llamadas.map { it.toString() }, "$p · con status done y $caso, el arranque volvió a cerrarla")
            assertEquals(Triple(1, 0, 0), Triple(r.cerrados, r.siguen, r.descartados), "$p · con status done y $caso: cerrados, siguen y descartados")
        }
        // Graph la tiene abierta (el cierre no salió): el GET y un finish, con un solo cobro.
        run {
            val graph = GraphDeVerdad().apply { abierta("ses-1") }
            val disco = conPendiente()
            val t = TransporteDeRutas(Cronica()) { graph.responder(it) }
            val r = leccion(t, disco).reintentarPendientes()
            assertEquals(listOf(consulta, cierre), t.llamadas.map { it.toString() }, "$p · abierta en Graph")
            assertEquals(1 to Triple(1, 0, 0), graph.cobros("ses-1") to Triple(r.cerrados, r.siguen, r.descartados), p)
        }
        // No se sabe: el GET no responde, lee agotado o no la encuentra. Un solo GET, sin esperas, y el cierre con su criterio (411).
        val cerrada = ok("""{"workflow_id":"ses-1","summary":"registra pacientes"}""")
        val noEsta = TransportReply(404, """{"error":"Workflow not found"}""")
        for ((caso, alPreguntar, alCerrar) in listOf(
            Triple("HTTP 503", TransportReply(503, ""), cerrada),
            Triple("lectura agotada", TransportReply(-1, "Read timed out"), cerrada),
            Triple("HTTP 404", noEsta, noEsta),
        )) {
            val disco = conPendiente()
            val esperas = mutableListOf<Long>()
            val t = TransporteDeRutas(Cronica()) { l -> if (l.esWorkflow) alPreguntar else alCerrar }
            val r = leccion(t, disco, esperas = esperas).reintentarPendientes()
            assertEquals(listOf(consulta, cierre), t.llamadas.map { it.toString() }, "$p · $caso al preguntar")
            assertTrue(esperas.isEmpty(), "$p · $caso al preguntar: se esperó para reintentar el GET: $esperas")
            val esperado = if (alCerrar === noEsta) Triple(0, 0, 1) else Triple(1, 0, 0)
            assertEquals(esperado, Triple(r.cerrados, r.siguen, r.descartados), "$p · $caso al preguntar: cerrados, siguen y descartados")
        }
    }

    @Test
    fun promesa422() = demo(promesa(422)) {
        val p = promesa(422)
        // La lección está procesando el video, con su provisional ya en disco, y un arranque del mismo proceso lo encuentra —la app
        // que vuelve al frente y lo llama otra vez—. Si lo cerrara, la nota y el finish de la lección le llegarían a Graph después.
        run {
            val cronica = Cronica()
            val graph = GraphDeVerdad()
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(TransporteDeRutas(cronica) { graph.responder(it) }, almacen)
            assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), p)
            l.pasoObservado(paso(1))
            l.nota("es para pacientes nuevos")
            val enVideo = CompletableDeferred<Unit>()
            val soltar = CompletableDeferred<Unit>()
            val cerrando = async { l.terminar(listo) { enVideo.complete(Unit); soltar.await(); ResumenDeVideo("registra pacientes", null) } }
            enVideo.await()
            assertTrue(Leccion.ruta(Leccion.CARPETA_PENDIENTES, "ses-1") in almacen.archivos, "$p · sin el provisional en disco esta prueba no juzga nada: ${almacen.archivos.keys}")
            val arranque = TransporteDeRutas(Cronica()) { graph.responder(it) }
            val r = leccion(arranque, almacen).reintentarPendientes()
            assertTrue(arranque.llamadas.isEmpty(), "$p · un arranque tocó la sesión que una lección de este proceso está cerrando: ${arranque.llamadas}")
            assertEquals(Triple(0, 1, 0), Triple(r.cerrados, r.siguen, r.descartados), "$p · cerrados, siguen y descartados")
            soltar.complete(Unit)
            assertEquals(Cierre.CERRADA, cerrando.await().cierre, p)
            val llegadas = graph.llegadas.filter { "/ses-1/" in it }
            val fin = llegadas.indexOfFirst { it.endsWith("/finish") }
            assertTrue(fin >= 0 && llegadas.drop(fin + 1).none { it.endsWith("/steps") || it.endsWith("/context-notes") }, "$p · a Graph le llegó un paso o una nota después de cerrar la sesión: $llegadas")
            assertEquals(1, graph.cobros("ses-1"), "$p · la sesión se cerró más de una vez: $llegadas")
            assertTrue(almacen.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · cerrada, quedó un pendiente: ${almacen.archivos.keys}")
        }
        // Terminada la lección, lo que dejó pendiente ya es del arranque: el mismo proceso lo cierra.
        run {
            val cronica = Cronica()
            val almacen = AlmacenEnMemoria(cronica)
            val l = leccion(TransporteDeRutas(cronica) { c -> if (c.esCierre) TransportReply(504, "") else sano(c) }, almacen)
            l.empezar(registro, "Registrar paciente")
            l.pasoObservado(paso(1))
            assertEquals(Cierre.PENDIENTE, l.terminar(listo, sinVideo).cierre, p)
            val arranque = TransporteDeRutas(Cronica()) { sano(it) }
            assertEquals(1, leccion(arranque, almacen).reintentarPendientes().cerrados, "$p · terminada la lección, el arranque no cerró lo que dejó pendiente: ${arranque.llamadas}")
        }
    }

    @Test
    fun promesa424() = demo(promesa(424)) {
        val p = promesa(424)
        val ses1 = Leccion.ruta(Leccion.CARPETA_PENDIENTES, "ses-1")
        suspend fun conPendiente(cronica: Cronica = Cronica()) = AlmacenEnMemoria(cronica).apply {
            escribirEntero(ses1, LeccionJson.encodeToString(CierrePendiente.serializer(), CierrePendiente("ses-1", "ses-1", AHORA)))
        }
        val consulta = "GET /api/v1/workflows/ses-1"
        val cierre = "POST /api/v1/learning/sessions/ses-1/finish"

        // La app vuelve al frente dos veces: dos arranques sobre el mismo pendiente, con el finish del primero tardando. Sin exclusión, los
        // dos GET ven `recording`, los dos mandan finish y Graph cobra el post-procesado dos veces (`WorkflowLearner.js:69-121`).
        run {
            val graph = GraphDeVerdad().apply { abierta("ses-1") }
            val cronica = Cronica()
            val disco = conPendiente(cronica)
            val enVuelo = CompletableDeferred<Unit>()
            val soltar = CompletableDeferred<Unit>()
            val lento = TransporteDeRutas(Cronica()) { l ->
                if (l.esCierre && !enVuelo.isCompleted) { enVuelo.complete(Unit); soltar.await() }
                graph.responder(l)
            }
            val primero = async { leccion(lento, disco).reintentarPendientes() }
            enVuelo.await()
            val discoAntes = cronica.eventos.toList()
            val otro = TransporteDeRutas(Cronica()) { graph.responder(it) }
            val lineas = mutableListOf<String>()
            val segundo = leccion(otro, disco, lineas = lineas).reintentarPendientes()
            assertTrue(otro.llamadas.isEmpty(), "$p · con un arranque en curso, otro llamó a Graph: ${otro.llamadas}")
            assertEquals(discoAntes, cronica.eventos, "$p · con un arranque en curso, otro tocó el disco")
            assertTrue(segundo.otroArranqueEnCurso, "$p · el segundo arranque no dice que había otro en curso")
            assertEquals(Triple(0, 0, 0), Triple(segundo.cerrados, segundo.siguen, segundo.descartados), "$p · el segundo arranque: cerrados, siguen y descartados")
            assertTrue(lineas.any { "en curso" in it }, "$p · el log no dice por qué el segundo arranque no hizo nada: $lineas")
            soltar.complete(Unit)
            val r = primero.await()
            assertEquals(listOf(consulta, cierre), lento.llamadas.map { it.toString() }, p)
            assertEquals(1, graph.llegadas.count { it == cierre }, "$p · dos arranques a la vez mandaron más de un finish: ${graph.llegadas}")
            assertEquals(1, graph.cobros("ses-1"), "$p · Graph cobró el post-procesado más de una vez: ${graph.llegadas}")
            assertEquals(Triple(1, 0, 0), Triple(r.cerrados, r.siguen, r.descartados), "$p · el arranque que corría: cerrados, siguen y descartados")
            assertFalse(r.otroArranqueEnCurso, p)
            assertTrue(disco.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · el pendiente cerrado se quedó: ${disco.archivos.keys}")
            // Terminado, el siguiente arranque corre: ya no hay pendientes y no llama a nadie.
            val siguiente = TransporteDeRutas(Cronica()) { graph.responder(it) }
            assertFalse(leccion(siguiente, disco).reintentarPendientes().otroArranqueEnCurso, "$p · terminado el arranque, el candado quedó tomado")
            assertTrue(siguiente.llamadas.isEmpty(), "$p · sin pendientes, el siguiente arranque llamó: ${siguiente.llamadas}")
        }
        // El que corre se cancela con su finish colgado: el candado se suelta, y el siguiente arranque pregunta y cierra.
        run {
            val graph = GraphDeVerdad().apply { abierta("ses-1") }
            val disco = conPendiente()
            val enVuelo = CompletableDeferred<Unit>()
            val colgado = TransporteDeRutas(Cronica()) { l -> if (l.esCierre) { enVuelo.complete(Unit); awaitCancellation() } else graph.responder(l) }
            val primero = launch { leccion(colgado, disco).reintentarPendientes() }
            enVuelo.await()
            primero.cancelAndJoin()
            assertTrue(primero.isCancelled, "$p · el arranque cancelado no salió cancelado")
            assertTrue(ses1 in disco.archivos, "$p · el arranque cancelado borró el pendiente: ${disco.archivos.keys}")
            val t = TransporteDeRutas(Cronica()) { graph.responder(it) }
            val r = leccion(t, disco).reintentarPendientes()
            assertFalse(r.otroArranqueEnCurso, "$p · cancelado el arranque que corría, el candado quedó tomado y el siguiente no corrió")
            assertEquals(listOf(consulta, cierre), t.llamadas.map { it.toString() }, "$p · cancelado el que corría, el siguiente no cerró")
            assertEquals(Triple(1, 0, 0) to 1, Triple(r.cerrados, r.siguen, r.descartados) to graph.cobros("ses-1"), "$p · cancelado el que corría: el siguiente y los cobros")
        }
    }

    @Test
    fun promesa425() = demo(promesa(425)) {
        val p = promesa(425)
        val descripcion = "Registrar a Ana Pérez CC 1037"
        val borrado = "DELETE /api/v1/workflows/ses-1"
        val pendiente = Leccion.ruta(Leccion.CARPETA_PENDIENTES, "ses-1")
        suspend fun AlmacenEnMemoria.conPendiente() = apply {
            escribirEntero(pendiente, LeccionJson.encodeToString(CierrePendiente.serializer(), CierrePendiente("ses-1", "ses-1", AHORA)))
        }
        /** Lo que el log no puede llevar: la descripción, ni el texto con que Graph la repite (419). */
        fun conDatos(lineas: List<String>) = lineas.filter { l -> listOf("Ana", "Pérez", "1037", "Registrar").any { it in l } }

        // Grabando, con la sesión abierta en Graph —que ya la lista y se la da al cerebro—: descartar vuelve sin esperar al DELETE, y un
        // solo DELETE la borra.
        run {
            val graph = GraphDeVerdad()
            val lineas = mutableListOf<String>()
            val esperas = mutableListOf<Long>()
            val soltar = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(Cronica()) { l -> if (l.esBorrado) soltar.await(); graph.responder(l) }
            coroutineScope {
                val l = leccion(t, AlmacenEnMemoria(Cronica()), esperas = esperas, lineas = lineas)
                assertIs<Arranque.Ensenando>(l.empezar(registro, descripcion), p)
                assertEquals(true, withTimeoutOrNull(2.seconds) { l.descartar() }, "$p · descartar esperó al DELETE: bloquea a quien descarta")
                soltar.complete(Unit)
            }
            assertEquals(listOf("POST /api/v1/learning/sessions", borrado), t.llamadas.map { it.toString() }, "$p · descartada con la sesión abierta en Graph")
            assertTrue(esperas.isEmpty(), "$p · el DELETE esperó para reintentar: $esperas")
            assertFalse(graph.existe("ses-1"), "$p · descartada, la sesión sigue en Graph: ${graph.llegadas}")
            assertEquals(0, graph.cobros("ses-1"), p)
            assertTrue(lineas.any { "ses-1" in it && "ya no está en graph" in it }, "$p · el log no dice que la borró: $lineas")
            assertEquals(emptyList(), conDatos(lineas), "$p · el log llevó la descripción")
        }
        // 404 cuenta como hecho. Cualquier otro fallo: un solo intento, y en el log el id y el status, sin lo que Graph repitió.
        for ((caso, respuesta) in listOf(
            "HTTP 404" to TransportReply(404, """{"error":"Workflow not found: $descripcion"}"""),
            "HTTP 500" to TransportReply(500, """{"error":"no se pudo borrar «$descripcion»"}"""),
            "HTTP 503" to TransportReply(503, """{"error":"Ana Pérez"}"""),
            "HTTP 429" to TransportReply(429, """{"error":"Ana Pérez"}""", retryAfterSeconds = 2),
            "HTTP -1" to TransportReply(-1, "Read timed out borrando a Ana Pérez"),
            "HTTP 0" to null,
        )) {
            val lineas = mutableListOf<String>()
            val esperas = mutableListOf<Long>()
            val t = TransporteDeRutas(Cronica()) { l -> if (l.esBorrado) respuesta ?: throw IllegalStateException("sin red borrando a Ana Pérez") else sano(l) }
            coroutineScope {
                val l = leccion(t, AlmacenEnMemoria(Cronica()), esperas = esperas, lineas = lineas)
                assertIs<Arranque.Ensenando>(l.empezar(registro, descripcion), "$p · $caso")
                assertTrue(l.descartar(), "$p · $caso")
            }
            assertEquals(listOf("DELETE /api/v1/workflows/wf-ses-1"), t.llamadas.filter { it.esBorrado }.map { it.toString() }, "$p · $caso: el DELETE no fue uno solo")
            assertTrue(esperas.isEmpty(), "$p · $caso: se esperó para reintentar el DELETE: $esperas")
            if (caso == "HTTP 404") {
                assertTrue(lineas.any { "ses-1" in it && "ya no está en graph" in it }, "$p · 404 no contó como hecho: $lineas")
                assertTrue(lineas.none { "no se borró" in it }, "$p · 404 contó como fallo: $lineas")
            } else {
                assertTrue(lineas.any { "ses-1" in it && "no se borró" in it && caso in it }, "$p · $caso: el log no dice el id y el status: $lineas")
            }
            assertEquals(emptyList(), conDatos(lineas), "$p · $caso: el log llevó la descripción o lo que Graph repitió")
        }
        // Sin sesión en Graph no se llama: sin empezar, o con Graph que no la abrió.
        run {
            val t = TransporteDeRutas(Cronica()) { l -> if (l.esSesion) TransportReply(401, """{"error":"invalid api key"}""") else sano(l) }
            coroutineScope {
                val l = leccion(t, AlmacenEnMemoria(Cronica()))
                assertFalse(l.descartar(), "$p · sin empezar dijo que descartó")
                assertIs<Arranque.NoSePuede>(l.empezar(registro, descripcion), p)
                assertFalse(l.descartar(), "$p · sin sesión dijo que descartó")
            }
            assertEquals(listOf("POST /api/v1/learning/sessions"), t.llamadas.map { it.toString() }, "$p · sin sesión en Graph, descartar llamó")
        }
        // Descartada mientras Graph abría: la sesión existe allá, y en cuanto llega su id se borra.
        run {
            val graph = GraphDeVerdad()
            val abriendo = CompletableDeferred<Unit>()
            val soltar = CompletableDeferred<Unit>()
            val t = TransporteDeRutas(Cronica()) { l -> if (l.esSesion) { abriendo.complete(Unit); soltar.await() }; graph.responder(l) }
            coroutineScope {
                val l = leccion(t, AlmacenEnMemoria(Cronica()))
                val empezando = async { l.empezar(registro, descripcion) }
                abriendo.await()
                assertTrue(l.descartar(), p)
                soltar.complete(Unit)
                assertIs<Arranque.NoSePuede>(empezando.await(), p)
            }
            assertEquals(listOf("POST /api/v1/learning/sessions", borrado), t.llamadas.map { it.toString() }, "$p · descartada mientras Graph abría, la sesión quedó en Graph")
            assertFalse(graph.existe("ses-1"), p)
        }
        // Con un cierre pendiente de esa sesión en disco: se borra ANTES del DELETE, y el arranque siguiente no la toca.
        run {
            val graph = GraphDeVerdad()
            val cronica = Cronica()
            val almacen = AlmacenEnMemoria(cronica)
            coroutineScope {
                val l = leccion(TransporteDeRutas(cronica) { graph.responder(it) }, almacen)
                assertIs<Arranque.Ensenando>(l.empezar(registro, descripcion), p)
                almacen.conPendiente()
                assertTrue(l.descartar(), p)
            }
            val borraPendiente = cronica.primero { it == "disco borra $pendiente" }
            assertTrue(borraPendiente in 0 until cronica.primero { it == "red $borrado" }, "$p · el pendiente no se borró antes del DELETE: ${cronica.eventos}")
            val arranque = TransporteDeRutas(Cronica()) { graph.responder(it) }
            leccion(arranque, almacen).reintentarPendientes()
            assertTrue(arranque.llamadas.isEmpty(), "$p · descartada, un arranque volvió a tocar la sesión: ${arranque.llamadas}")
        }
        // Un arranque está cerrando esa sesión cuando se descarta (422, 424): el DELETE espera a que termine, y a Graph no le llega nada
        // de la sesión después del DELETE.
        run {
            val graph = GraphDeVerdad()
            val cronica = Cronica()
            val almacen = AlmacenEnMemoria(cronica)
            val enVuelo = CompletableDeferred<Unit>()
            val soltar = CompletableDeferred<Unit>()
            coroutineScope {
                val l = leccion(TransporteDeRutas(cronica) { graph.responder(it) }, almacen)
                assertIs<Arranque.Ensenando>(l.empezar(registro, descripcion), p)
                almacen.conPendiente()
                val lento = TransporteDeRutas(cronica) { c -> if (c.esCierre) { enVuelo.complete(Unit); soltar.await() }; graph.responder(c) }
                val arranque = async { leccion(lento, almacen).reintentarPendientes() }
                enVuelo.await()
                assertTrue(l.descartar(), p)
                repeat(20) { yield() } // lo que no espere al arranque tiene aquí tiempo de salir
                soltar.complete(Unit)
                arranque.await()
            }
            val deLaSesion = graph.llegadas.filter { "/ses-1" in it }
            val delete = deLaSesion.indexOf(borrado)
            assertTrue(delete >= 0 && deLaSesion.drop(delete + 1).isEmpty(), "$p · a Graph le llegó algo de la sesión después del DELETE: $deLaSesion")
            assertFalse(graph.existe("ses-1"), p)
            assertTrue(almacen.en(Leccion.CARPETA_PENDIENTES).isEmpty(), "$p · quedó el pendiente de una sesión borrada: ${almacen.archivos.keys}")
        }
    }

    @Test
    fun promesa426() = demo(promesa(426)) {
        val p = promesa(426)
        val video = "POST /api/v1/teach/process-video"
        fun cliente(t: TurnTransport, esperas: MutableList<Long>, topeTeach: Duration = LearningClient.TOPE_TEACH) = LearningClient(
            transport = t, credentials = { "miracle_k" }, baseUrl = { "$BASE/" }, email = { null }, deviceId = { "dev-1" },
            sleep = { esperas += it }, topeTeach = topeTeach,
        )
        // Graph ya reintenta 5 veces contra Gemini y cada intento es consumo (`GeminiVideoClient.js:260-300`): el cliente llama UNA vez.
        val fallos = listOf(500, 502, 503, 504, 408, 0).map { TransportReply(it, """{"error":"Gemini: saturado"}""") } +
            TransportReply(429, """{"error":"Gemini: sin cupo"}""", retryAfterSeconds = 4) + TransportReply(-1, "Read timed out")
        for (respuesta in fallos) {
            val caso = "HTTP ${respuesta.status}"
            val esperas = mutableListOf<Long>()
            val t = TransporteDeRutas(Cronica()) { respuesta }
            val e = lanzaExacto<GraphException>("$p · $caso") { cliente(t, esperas).processVideo("files/demo", "u-1") }
            assertEquals(listOf(video), t.llamadas.map { it.toString() }, "$p · $caso: process-video se repitió")
            assertTrue(esperas.isEmpty(), "$p · $caso: se esperó para reintentar process-video: $esperas")
            assertEquals(respuesta.status, e.status, "$p · $caso")
        }
        // Colgada: su tope la corta como lectura agotada, y no se repite.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteDeRutas(Cronica()) { awaitCancellation() }
            val e = lanzaExacto<GraphException>("$p · colgada") {
                withTimeout(3.seconds) { cliente(t, esperas, topeTeach = 200.milliseconds).processVideo("files/demo", "u-1") }
            }
            assertEquals(listOf(video), t.llamadas.map { it.toString() }, "$p · colgada: process-video se repitió")
            assertEquals(TransportReply.TIMED_OUT, e.status, "$p · colgada: ${e.message}")
        }
        // upload-token y file-state siguen con los reintentos del cerebro (403).
        for ((ruta, llamada) in listOf<Pair<String, suspend (LearningClient) -> Unit>>(
            "upload-token" to { it.uploadToken(10, "u-1") },
            "file-state" to { it.fileState("files/demo") },
        )) {
            val esperas = mutableListOf<Long>()
            var primera = true
            val guion = TransporteDeRutas(Cronica()) {
                if (primera) { primera = false; TransportReply(503, "") } else ok("""{"geminiUploadUrl":"https://upload.test/x","state":"ACTIVE"}""")
            }
            llamada(cliente(guion, esperas))
            assertEquals(2, guion.llamadas.size, "$p · $ruta dejó de reintentar: ${guion.llamadas}")
            assertEquals(listOf(800L), esperas, "$p · $ruta")
        }
        // La lección cuyo video falla: una sola llamada, el video queda para reprocesar a mano y ningún arranque lo reintenta.
        run {
            val cronica = Cronica()
            val almacen = AlmacenEnMemoria(cronica)
            val t = TransporteDeRutas(cronica) { l -> if (l.ruta.endsWith("/process-video")) TransportReply(503, """{"error":"Gemini: saturado"}""") else sano(l) }
            val esperas = mutableListOf<Long>()
            val paraElVideo = cliente(t, esperas)
            val l = leccion(t, almacen)
            assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), p)
            l.pasoObservado(paso(1))
            val r = l.terminar(listo) { paraElVideo.processVideo("files/demo", "u-1").let { ResumenDeVideo(it.summary, it.interpretation) } }
            assertEquals(1, t.llamadas.count { it.toString() == video }, "$p · la lección repitió process-video: ${t.llamadas}")
            assertTrue(esperas.isEmpty(), "$p · la lección esperó para reintentar el video: $esperas")
            assertEquals(Cierre.CERRADA, r.cierre, p)
            assertTrue(r.videoParaReprocesar, "$p · el video que falló no quedó para reprocesar")
            assertEquals(1, almacen.en(Leccion.CARPETA_VIDEOS).size, "$p · no quedó la marca para reprocesar: ${almacen.archivos.keys}")
            val arranque = TransporteDeRutas(Cronica()) { sano(it) }
            leccion(arranque, almacen).reintentarPendientes()
            assertTrue(arranque.llamadas.isEmpty(), "$p · al arrancar se reintentó algo: ${arranque.llamadas}")
            assertEquals(1, almacen.en(Leccion.CARPETA_VIDEOS).size, "$p · al arrancar desapareció la marca para reprocesar a mano")
        }
    }
}
