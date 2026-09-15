package graph.core.contrato

import graph.core.contrato.Contrato004EnsenadoEnGraph.TransporteGuionado
import graph.core.contrato.Contrato004LeccionEnGraph.AlmacenEnMemoria
import graph.core.contrato.Contrato004LeccionEnGraph.Cronica
import graph.core.contrato.Contrato004LeccionEnGraph.TransporteDeRutas
import graph.core.domain.GraphLog
import graph.core.graph.TransportReply
import graph.core.graph.TurnTransport
import graph.core.graph.learning.Arranque
import graph.core.graph.learning.Cierre
import graph.core.graph.learning.FieldOption
import graph.core.graph.learning.GraphException
import graph.core.graph.learning.IdentidadDePantalla
import graph.core.graph.learning.LearningClient
import graph.core.graph.learning.Leccion
import graph.core.graph.learning.LeccionEnDisco
import graph.core.graph.learning.LeccionJson
import graph.core.graph.learning.ResumenDeVideo
import graph.core.graph.learning.StartSessionRequest
import graph.core.graph.learning.StepRequest
import graph.core.graph.learning.StepToRead
import graph.core.graph.learning.VideoParaReprocesar
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * CONTRATO 004 · LO QUE GRAPH MANDA RARO (docs/specs/004-lo-ensenado-vive-en-graph.md, revisión de la fase 4A1).
 *
 * Cada `promesaNNN` es una fila de la tabla de la spec, con el enunciado literal en [PROMESAS]. Nacen de la revisión
 * independiente de `d13ab95`: respuestas que Graph puede mandar y que el cliente tomaba mal —anidadas hasta tumbar la
 * pila, con un `null` donde Windows pone `""`, sin la clave que se esperaba— y un id en blanco que llegaba a la red.
 * Mismo mapa a mano que 401-412: el transporte guionado de [Contrato004EnsenadoEnGraph] y, para la lección, el
 * transporte por rutas, la crónica y el almacén en memoria de [Contrato004LeccionEnGraph]. Se escribieron ANTES que
 * el código que juzgan: nacieron rojas.
 */
class Contrato004RespuestasDeGraph {

    companion object {
        val PROMESAS = mapOf(
            413 to "Una respuesta de Graph demasiado anidada es un error manejado: nunca tumba la app, ni al leerla ni al registrarla ni al guardarla.",
            414 to "Un plan o un video procesado no se pierden por un campo raro: una opción sin value o label y una variable nula se leen como vacías, y un null en notas o preguntas se descarta.",
            415 to "Un id de workflow en blanco no llama a Graph y dice por qué.",
            416 to "Una respuesta a la que le falta la clave esperada es un error; una que la trae vacía es válida.",
            423 to "Lo que Graph responde de verdad se lee sin romperse: el workflow con sus variables en lista y sus fechas Neo4j, el plan con su eco y su contexto de ramas, y la alineación dice en el log si ya estaba o se aprendió.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        const val AHORA = 1_789_054_200_000L

        /** ~4 KB de `[[[…]]]`: pasado a texto, agota cualquier pila de hilo de la JVM (medido: desde ~1000 niveles con 1 MB). */
        const val HONDO = 2_000
    }

    /* ---------- El mapa a mano ---------- */

    private fun ok(json: String) = TransportReply(200, json)

    /** [niveles] listas una dentro de otra. */
    private fun anidado(niveles: Int) = "[".repeat(niveles) + "]".repeat(niveles)

    private fun cliente(transporte: TurnTransport, lineas: MutableList<String> = mutableListOf()): LearningClient {
        val reloj = TestTimeSource()
        return LearningClient(
            transport = transporte,
            credentials = { "miracle_k" },
            baseUrl = { "https://graph.test/" },
            email = { null },
            deviceId = { "dev-1" },
            log = GraphLog { tag, m -> lineas += "[$tag] $m" },
            sleep = { reloj += it.milliseconds },
            timeSource = reloj,
        )
    }

    /**
     * El bloque lanza exactamente [T]: ni una madre ni una hija. En la JVM `CancellationException` ES una
     * `IllegalStateException`, así que un `assertFailsWith` dejaría pasar una por la otra.
     */
    private inline fun <reified T : Throwable> lanzaExacto(donde: String, bloque: () -> Unit): T {
        val e = try { bloque(); null } catch (e: NotImplementedError) { throw e } catch (e: Throwable) { e }
        val lanzada = assertNotNull(e, "$donde · no lanzó")
        assertEquals(T::class, lanzada::class, "$donde · lanzó ${lanzada::class.simpleName}: ${lanzada.message?.take(200)}")
        return lanzada as T
    }

    private val sesion = StartSessionRequest(
        description = "Registrar paciente",
        appId = "dev-1",
        sourceUrl = "android://com.x/RegistroActivity",
        sourceOrigin = "android://com.x",
        sourcePathname = "/RegistroActivity",
        sourceTitle = "Registro",
        context = mapOf("surface" to "a11y", "platform" to "android"),
    )
    private val registro = IdentidadDePantalla(url = "android://com.x/RegistroActivity", origin = "android://com.x", pathname = "/RegistroActivity", title = "Registro")
    private fun paso(n: Int) = StepRequest(actionType = "click", selector = "a11y:id=com.x:id/b$n", label = "B$n", controlType = "button")
    private val pasosDeLaDemo = listOf(StepToRead(1, "Nombre", "Ana", "aquí va el nombre"))

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa413() = correConPilaChica {
        val p = promesa(413)
        val hondo = anidado(HONDO)

        // El tope es el de System.Text.Json en U: 64 niveles se leen y 65 no. El objeto del cuerpo ya cuenta como uno.
        run {
            val t = TransporteGuionado(ok("""{"interpretation":${anidado(63)}}"""), ok("""{"interpretation":${anidado(64)}}"""))
            val c = cliente(t)
            assertNotNull(c.processVideo("files/a", "u-1").interpretation, "$p · 64 niveles se leen")
            val e = lanzaExacto<GraphException>("$p · 65 niveles se leyeron") { c.processVideo("files/a", "u-1") }
            assertTrue("64 niveles" in e.message.orEmpty(), "$p · el error no dice por qué: ${e.message?.take(200)}")
            assertEquals(200, e.status, p)
        }

        // Lo que va dentro de un texto no anida y una comilla escapada no lo cierra; tras una barra escapada, el texto sí cierra.
        run {
            val corchetes = "[{".repeat(100)
            val t = TransporteGuionado(
                ok("""{"summary":"$corchetes","interpretation":{}}"""),
                ok("""{"summary":"dijo \"$corchetes\" y siguió","interpretation":{}}"""),
                ok("""{"summary":"c:\\","interpretation":${anidado(64)}}"""),
            )
            val c = cliente(t)
            assertEquals(corchetes, c.processVideo("files/a", "u-1").summary, "$p · unos corchetes dentro de un texto contaron como niveles")
            assertEquals("dijo \"$corchetes\" y siguió", c.processVideo("files/a", "u-1").summary, "$p · una comilla escapada cerró el texto")
            lanzaExacto<GraphException>("$p · tras una barra escapada, 65 niveles se leyeron") { c.processVideo("files/a", "u-1") }
        }

        // Cada respuesta que se lee: un error manejado que dice por qué, y ni el error ni el log la vuelcan.
        run {
            val casos = listOf<Triple<String, String, suspend (LearningClient) -> Unit>>(
                Triple("abrir la sesión", """{"session":{"id":"ses-1","extra":$hondo}}""", { it.crearSesion(sesion) }),
                Triple("mandar un paso", """{"step":{"step_order":1},"extra":$hondo}""", { it.mandarPaso("ses-1", paso(1)) }),
                Triple("cerrar la sesión", """{"workflow_id":"wf-1","workflow":$hondo}""", { it.terminar("ses-1") }),
                Triple("listar", """{"workflows":[$hondo]}""", { it.listarWorkflows() }),
                Triple("traer un workflow", """{"workflow":$hondo}""", { it.workflow("wf-1") }),
                Triple("preguntar si ya se cerró", """{"workflow":$hondo}""", { it.cerradaEnGraph("wf-1") }),
                Triple("planificar", """{"execution_plan":{"steps":[{"stepOrder":1,"surfaceHints":{"alternativeTargets":$hondo}}]}}""", { it.plan("wf-1") }),
                Triple("pedir dónde subir", """{"geminiUploadUrl":"https://upload.test/x","extra":$hondo}""", { it.uploadToken(1, "u-1") }),
                Triple("consultar el video", """{"state":"ACTIVE","extra":$hondo}""", { it.fileState("files/a") }),
                Triple("procesar el video", """{"summary":"s","interpretation":$hondo}""", { it.processVideo("files/a", "u-1") }),
            )
            for ((caso, cuerpo, llamar) in casos) {
                val lineas = mutableListOf<String>()
                val e = lanzaExacto<GraphException>("$p · $caso") { llamar(cliente(TransporteGuionado(ok(cuerpo)), lineas)) }
                val mensaje = e.message.orEmpty()
                assertTrue("64 niveles" in mensaje && "[[[" !in mensaje, "$p · $caso: ${mensaje.take(200)}")
                assertTrue(lineas.none { "[[[" in it }, "$p · $caso: el log volcó la respuesta")
            }
            // Un error de Graph anidado de más tampoco revienta al buscarle el `error`, ni se vuelca en el mensaje: ni el de un
            // 500, ni el de un transitorio que se rinde tras sus reintentos.
            val errores = listOf<Triple<String, Array<TransportReply>, suspend (LearningClient) -> Unit>>(
                Triple("un 500 anidado de más", arrayOf(TransportReply(500, """{"error":"neo4j caído","traza":$hondo}""")), { it.borrar("wf-1") }),
                Triple("un 503 anidado de más", Array(4) { TransportReply(503, hondo) }, { it.mandarPaso("ses-1", paso(1)) }),
            )
            for ((caso, guion, llamar) in errores) {
                val lineas = mutableListOf<String>()
                val e = lanzaExacto<GraphException>("$p · $caso") { llamar(cliente(TransporteGuionado(*guion), lineas)) }
                assertEquals(guion.first().status, e.status, "$p · $caso")
                val mensaje = e.message.orEmpty()
                assertTrue("64 niveles" in mensaje && "[[[" !in mensaje, "$p · $caso: ${mensaje.take(200)}")
                assertTrue(lineas.none { "[[[" in it }, "$p · $caso: el log volcó la respuesta")
            }
        }

        // Interpretar sin video: el modelo no opinó, con el porqué, y el log no vuelca la interpretación.
        run {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(ok("""{"interpretation":$hondo}"""))
            assertNull(cliente(t, lineas).interpretSteps("android://com.x/Registro", pasosDeLaDemo), p)
            assertTrue(lineas.any { "no opinó" in it && "64 niveles" in it }, "$p · el log no dice por qué: ${lineas.map { it.take(160) }}")
            assertTrue(lineas.none { "[[[" in it }, "$p · el log volcó la interpretación")
        }

        // Al guardarla: un paso con respuesta anidada de más y un video que la trae no tumban la lección, que llega a disco.
        withTimeout(20.seconds) {
            coroutineScope {
                val cronica = Cronica()
                val t = TransporteDeRutas(cronica) { l ->
                    when {
                        l.esSesion -> ok("""{"session":{"id":"ses-1","workflow_id":"wf-1"}}""")
                        l.esPaso && l.selector.endsWith("/b1") -> ok("""{"step":$hondo}""")
                        l.esPaso -> ok("""{"step":{"step_order":1}}""")
                        l.esCierre -> ok("""{"workflow_id":"wf-1","summary":"registra pacientes"}""")
                        l.ruta.endsWith("/process-video") -> ok("""{"summary":"registra pacientes","interpretation":$hondo}""")
                        else -> ok("{}")
                    }
                }
                val almacen = AlmacenEnMemoria(cronica)
                val lineas = mutableListOf<String>()
                val c = cliente(t, lineas)
                val l = Leccion(cliente = c, almacen = almacen, scope = this, appId = "dev-1", ahoraMs = { AHORA }, log = GraphLog { tag, m -> lineas += "[$tag] $m" })
                assertIs<Arranque.Ensenando>(l.empezar(registro, "Registrar paciente"), p)
                l.pasoObservado(paso(1))
                l.pasoObservado(paso(2))
                val r = l.terminar("android://com.x/ListoActivity") {
                    val hecho = c.processVideo("files/a", "u-1")
                    ResumenDeVideo(hecho.summary, hecho.interpretation)
                }
                assertEquals(Cierre.CERRADA, r.cierre, p)
                assertEquals(listOf(false, true), r.pasos.map { it.enviado }, "$p · el paso con la respuesta anidada de más cuenta como no enviado, y el siguiente sale")
                assertTrue("64 niveles" in r.pasos[0].motivo.orEmpty(), "$p · motivo del paso: ${r.pasos[0].motivo?.take(200)}")
                assertTrue(r.videoParaReprocesar, "$p · la interpretación anidada de más pasó como video procesado")
                val marca = LeccionJson.decodeFromString(VideoParaReprocesar.serializer(), almacen.en(Leccion.CARPETA_VIDEOS).values.single())
                assertTrue("64 niveles" in marca.motivo, "$p · la marca del video no dice por qué: ${marca.motivo.take(200)}")
                val enDisco = LeccionJson.decodeFromString(LeccionEnDisco.serializer(), almacen.archivos.getValue(assertNotNull(r.leccion, "$p · la lección no llegó a disco")))
                assertEquals(2, enDisco.pasos.size, p)
                assertTrue(lineas.none { "[[[" in it }, "$p · el log volcó una respuesta de Graph")
                assertTrue(almacen.archivos.values.none { "[[[" in it }, "$p · el disco guardó una respuesta de Graph")
            }
        }
    }

    @Test
    fun promesa414() = corre {
        val p = promesa(414)
        val t = TransporteGuionado(
            ok("""{"execution_plan":{"workflowId":"wf-1","variables":{"paciente":"Ana","pais":null,"edad":7},
                "steps":[{"stepOrder":1,"actionType":"select","allowedOptions":[
                  {"value":"co","text":"Colombia"},{"value":"mx","label":null},{"value":null,"label":"Perú"},{"label":"Chile"}]}]}}"""),
            ok("""{"execution_plan":{"workflowId":"wf-2","variables":null,"steps":[]}}"""),
            ok("""{"summary":"registra pacientes","interpretation":{"campos":[]},"notes":[null,{"app":"com.x","note":"siempre Colombia"},null],"questions":["¿siempre Colombia?",null]}"""),
            ok("""{"step":{"step_order":1}}"""),
        )
        val c = cliente(t)

        // El plan: como Windows (`Contracts.cs:94-95`, `Dictionary<string,string>`), lo que falta o es null se lee "".
        val plan = c.plan("wf-1")
        assertEquals(mapOf("paciente" to "Ana", "pais" to "", "edad" to "7"), plan.variables, "$p · una variable nula es vacía y un número, su texto")
        val opciones = assertNotNull(plan.steps.single().allowedOptions, p)
        assertEquals(listOf("co" to "", "mx" to "", "" to "Perú", "" to "Chile"), opciones.map { it.value to it.label }, "$p · una opción sin value o label")
        assertEquals("Colombia", opciones[0].text, p)
        assertEquals(emptyMap(), c.plan("wf-2").variables, "$p · variables null es ninguna variable")

        // El video, que Gemini ya cobró: un hueco en notas o preguntas no tira el resultado.
        val video = c.processVideo("files/a", "u-1")
        assertEquals("registra pacientes", video.summary, p)
        assertNotNull(video.interpretation, "$p · la interpretación se perdió")
        assertEquals(listOf("siempre Colombia"), assertNotNull(video.notes, p).map { it.note }, "$p · notas")
        assertEquals(listOf("¿siempre Colombia?"), video.questions, "$p · preguntas")

        // Al grabar, una opción vacía viaja igual con sus dos claves, como la manda Windows: el default no la calla.
        val conOpcionVacia = StepRequest(
            actionType = "select", selector = "a11y:id=com.x:id/pais", label = "País", controlType = "select",
            allowedOptions = listOf(FieldOption("", "")),
        )
        c.mandarPaso("ses-1", conOpcionVacia)
        val opcion = t.llamadas[3].json["allowedOptions"]!!.jsonArray.single().jsonObject
        assertEquals(mapOf("value" to "", "label" to ""), opcion.mapValues { it.value.jsonPrimitive.content }, "$p · una opción vacía no viajó entera")
    }

    @Test
    fun promesa415() = corre {
        val p = promesa(415)
        val enBlanco = listOf<Pair<String, suspend (LearningClient) -> Unit>>(
            "borrar" to { it.borrar("") },
            "borrar con blancos" to { it.borrar("  ") },
            "traer" to { it.workflow("") },
            "planificar" to { it.plan(" ") },
        )
        for ((caso, llamar) in enBlanco) {
            // Si llegara a Graph: `DELETE /api/v1/workflows/` responde 404, y un 404 al borrar cuenta como borrado sin decir nada.
            val t = TransporteGuionado(TransportReply(404, """{"error":"Not found"}"""))
            val e = lanzaExacto<IllegalArgumentException>("$p · $caso") { llamar(cliente(t)) }
            assertTrue(t.llamadas.isEmpty(), "$p · $caso: llamó a Graph: ${t.llamadas.map { "${it.metodo} ${it.url}" }}")
            val mensaje = e.message.orEmpty()
            assertTrue("id" in mensaje && "en blanco" in mensaje && "graph" in mensaje, "$p · $caso: no dice por qué: «$mensaje»")
        }
        // Alinear es best-effort: con el id en blanco devuelve false, no llama a Graph y el log dice por qué.
        for (id in listOf("", "  ")) {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(ok("{}"))
            assertFalse(cliente(t, lineas).prependAlignment(id), "$p · alinear «$id» dijo que alineó")
            assertTrue(t.llamadas.isEmpty(), "$p · alinear «$id» llamó a Graph: ${t.llamadas.map { "${it.metodo} ${it.url}" }}")
            assertTrue(lineas.any { "id" in it && "en blanco" in it && "graph" in it }, "$p · alinear «$id»: el log no dice por qué: $lineas")
        }

        // Un id que Graph manda como número se lee como su texto: no es un id en blanco y se puede borrar.
        val t = TransporteGuionado(ok("""{"workflows":[{"id":17,"description":"Radicar factura"},{"id":"wf-2","description":"Registrar"}]}"""), ok(""))
        val c = cliente(t)
        val numerico = c.listarWorkflows().single { it.description == "Radicar factura" }
        assertEquals("17", numerico.id, "$p · un id numérico se leyó como ausente")
        c.borrar(numerico.id)
        assertEquals("DELETE https://graph.test/api/v1/workflows/17", t.llamadas[1].let { "${it.metodo} ${it.url}" }, p)
    }

    @Test
    fun promesa416() = corre {
        val p = promesa(416)
        val sinClave = listOf<Triple<String, String, suspend (LearningClient) -> Unit>>(
            Triple("lista sin workflows", """{}""", { it.listarWorkflows() }),
            Triple("lista con workflows null", """{"workflows":null}""", { it.listarWorkflows() }),
            Triple("plan sin steps", """{"execution_plan":{"workflowId":"wf-1"}}""", { it.plan("wf-1") }),
            Triple("plan vacío", """{"execution_plan":{}}""", { it.plan("wf-1") }),
            Triple("plan con steps null", """{"execution_plan":{"workflowId":"wf-1","steps":null}}""", { it.plan("wf-1") }),
            Triple("sin plan", """{}""", { it.plan("wf-1") }),
        )
        for ((caso, cuerpo, llamar) in sinClave) {
            val e = lanzaExacto<GraphException>("$p · $caso: se aceptó como vacío") { llamar(cliente(TransporteGuionado(ok(cuerpo)))) }
            assertEquals(200, e.status, "$p · $caso")
            val clave = if ("lista" in caso) "workflows" else if (caso == "sin plan") "plan" else "steps"
            assertTrue(clave in e.message.orEmpty(), "$p · $caso: el error no dice qué clave falta: ${e.message}")
        }

        // La clave, vacía: es una respuesta válida.
        val t = TransporteGuionado(ok("""{"workflows":[]}"""), ok("""{"execution_plan":{"workflowId":"wf-1","steps":[]}}"""))
        val c = cliente(t)
        assertEquals(emptyList(), c.listarWorkflows(), "$p · una lista vacía es válida")
        val plan = c.plan("wf-1")
        assertEquals("wf-1", plan.workflowId, p)
        assertEquals(emptyList(), plan.steps, "$p · un plan de 0 pasos es válido")
    }

    @Test
    fun promesa423() = corre {
        val p = promesa(423)
        val cerrado = Contrato004LeccionEnGraph.workflowDeGraph("wf_1789054200000", "done")
        val abierto = Contrato004LeccionEnGraph.workflowDeGraph("wf_1789054140000", "recording", creadoMs = AHORA - 60_000)

        // La lista, un workflow y el cierre, como los arma Graph: variables en lista de objetos, fechas {low, high} y ramas.
        run {
            val t = TransporteGuionado(
                ok("""{"workflows":[$abierto,$cerrado]}"""),
                ok("""{"workflow":$cerrado}"""),
                ok("""{"workflow_id":"wf_1789054200000","summary":"registra pacientes","workflow":$cerrado}"""),
            )
            val c = cliente(t)
            val lista = c.listarWorkflows()
            assertEquals(listOf("wf_1789054200000", "wf_1789054140000"), lista.map { it.id }, "$p · la lista con la forma real")
            assertEquals(listOf(AHORA, AHORA - 60_000), lista.map { it.creadoEnMs }, "$p · createdAt Neo4j")
            assertEquals(listOf(1, 1), lista.map { it.totalSteps }, p)
            val wf = c.workflow("wf_1789054200000").jsonObject
            assertEquals(1, wf["variables"]!!.jsonArray.size, "$p · las variables del workflow no llegaron como la lista que manda Graph")
            assertEquals("done", wf["status"]!!.jsonPrimitive.content, p)
            val fin = c.terminar("wf_1789054200000")
            assertEquals("wf_1789054200000" to "registra pacientes", fin.workflowId to fin.summary, p)
            assertEquals("done", fin.workflow?.jsonObject?.get("status")?.jsonPrimitive?.content, "$p · el workflow del cierre se perdió")
        }

        // El plan de `WorkflowExecutor.buildExecutionPlan`: eco de variables e intención, runtimeIntelligence, branchContext (objeto o
        // null), el paso de alineación en orden 0 y `key`/`scroll`, que Graph deja pasar con value o selector. Sus errores: 404 y 500.
        run {
            fun paso(tipo: String, selector: String, value: String, orden: Int, pistas: String = "null") =
                """{"actionType":"$tipo","selector":"$selector","value":"$value","url":"${if (tipo == "navigation") "android://com.x" else ""}","explanation":"",
                   "label":"","controlType":"","selectedValue":"","selectedLabel":"","semanticTarget":"","surfaceSection":"","surfaceHints":$pistas,
                   "nodeKey":"","nodePath":"","nodeAction":"","allowedOptions":[],"valueMode":"fixed","bindTo":"","valueModeExplicit":false,"stepOrder":$orden}"""
            fun plan(ramas: String) = """{"execution_plan":{"workflowId":"wf_1","description":"Registrar paciente","appId":"dev-1",
                "sourceUrl":"android://com.x/RegistroActivity","sourceOrigin":"android://com.x","sourcePathname":"/RegistroActivity","sourceTitle":"Registro",
                "executionGuide":"1. toca Guardar","variables":{"paciente":"Ana"},"executionIntent":{"source":"android_app","surface":"native"},
                "runtimeIntelligence":{"maxCallsPerStep":5,"decisions":[]},"branchContext":$ramas,
                "steps":[${paso("navigation", "app:com.x", "", 0, """{"kind":"surface-alignment","appId":"com.x"}""")},${paso("key", "", "ENTER", 2)},${paso("scroll", "a11y:id=com.x:id/lista", "", 3)}]}}"""
            val t = TransporteGuionado(
                ok(plan("""{"id":"br-1","branchKey":"nuevo","stepPatches":[],"insertedSteps":[],"appliedBranchId":"br-1","applied":true}""")),
                ok(plan("null")),
                TransportReply(404, """{"error":"Workflow wf_x not found or has no steps."}"""),
                TransportReply(500, """{"error":"Workflow wf_1 has no executable steps."}"""),
            )
            val c = cliente(t)
            for (caso in listOf("con ramas", "sin ramas")) {
                val leido = c.plan("wf_1", mapOf("paciente" to "Ana"))
                assertEquals(listOf("navigation", "key", "scroll"), leido.steps.map { it.actionType }, "$p · plan $caso")
                assertEquals(listOf(0, 2, 3), leido.steps.map { it.stepOrder }, "$p · plan $caso")
                assertEquals("app:com.x" to "android://com.x", leido.steps[0].selector to leido.steps[0].url, "$p · la alineación, $caso")
                assertEquals(null to "ENTER", leido.steps[1].selector to leido.steps[1].value, "$p · una tecla sin selector, $caso")
                assertEquals(mapOf("paciente" to "Ana"), leido.variables, "$p · el eco de las variables, $caso")
            }
            val sinPasos = lanzaExacto<GraphException>("$p · plan de un workflow sin pasos") { c.plan("wf_x") }
            assertEquals(404, sinPasos.status, p)
            val sinEjecutables = lanzaExacto<GraphException>("$p · plan sin pasos ejecutables") { c.plan("wf_1") }
            assertEquals(500, sinEjecutables.status, p)
            assertEquals(4, t.llamadas.size, "$p · un 404 o un 500 del plan se reintentó")
        }

        // La alineación: best-effort, y el log dice cuál de las dos respuestas dio Graph —ya estaba o la aprendió—, sin el workflow.
        run {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(
                ok("""{"workflow":$cerrado,"already_present":true}"""),
                ok("""{"workflow":$cerrado,"learned":true}"""),
                TransportReply(404, """{"error":"Workflow not found"}"""),
                TransportReply(400, """{"error":"El workflow no tiene superficie de origen."}"""),
            )
            val c = cliente(t, lineas)
            assertTrue(c.prependAlignment("wf_ya"), p)
            val yaEstaba = lineas.toList()
            assertTrue(c.prependAlignment("wf_nuevo"), p)
            val aprendida = lineas.drop(yaEstaba.size)
            assertTrue(yaEstaba.any { "wf_ya" in it && "already_present" in it } && yaEstaba.none { "learned" in it }, "$p · ya estaba: el log no lo dice: $yaEstaba")
            assertTrue(aprendida.any { "wf_nuevo" in it && "learned" in it } && aprendida.none { "already_present" in it }, "$p · la aprendió: el log no lo dice: $aprendida")
            assertFalse(c.prependAlignment("wf_ido"), p)
            assertTrue(lineas.any { "wf_ido" in it && "HTTP 404" in it }, "$p · un 404: $lineas")
            assertFalse(c.prependAlignment("wf_sin_origen"), p)
            assertTrue(lineas.any { "wf_sin_origen" in it && "HTTP 400" in it }, "$p · un 400: $lineas")
            assertTrue(lineas.none { "Registrar paciente" in it || "android://" in it || "superficie de origen" in it }, "$p · el log de la alineación volcó el workflow o el texto de Graph: $lineas")
        }
    }
}
