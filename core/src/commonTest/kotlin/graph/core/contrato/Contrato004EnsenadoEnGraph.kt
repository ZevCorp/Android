package graph.core.contrato

import graph.core.domain.GraphLog
import graph.core.graph.TransportReply
import graph.core.graph.TurnTransport
import graph.core.graph.learning.FieldOption
import graph.core.graph.learning.FinishPendiente
import graph.core.graph.learning.GraphException
import graph.core.graph.learning.LearningClient
import graph.core.graph.learning.NombreDeWorkflow
import graph.core.graph.learning.StartSessionRequest
import graph.core.graph.learning.StepRequest
import graph.core.graph.learning.StepToRead
import graph.core.graph.learning.SurfaceHint
import graph.core.graph.learning.WorkflowResumen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * CONTRATO 004 — LO ENSEÑADO VIVE EN GRAPH (docs/specs/004-lo-ensenado-vive-en-graph.md).
 *
 * Cada `promesaNNN` es una fila de la tabla de la spec, con el enunciado literal en [PROMESAS]. Se
 * escribieron ANTES que el código que juzgan: nacieron rojas. Ninguna toca red, Android ni disco: el
 * transporte es un guion que graba método, URL, cuerpo, cabeceras y tope de cada llamada; la espera
 * entre reintentos se anota en vez de dormir y el reloj es un [TestTimeSource] que avanzan las esperas.
 * Los formatos salen de `U-Windows-App/windows-graph/src/Contracts.cs` y de `TeachSession.cs`.
 */
class Contrato004EnsenadoEnGraph {

    companion object {
        val PROMESAS = mapOf(
            401 to "El protocolo de aprendizaje y workflows es espejo de Windows campo por campo; un campo vacío de Graph cuenta como ausente y createdAt se lee sin signo.",
            402 to "Cada llamada de aprendizaje lleva X-API-Key, X-Miracle-App android_app y el id de dispositivo; el email solo si existe.",
            403 to "Un transitorio de aprendizaje se reintenta como en el cerebro, pero terminar una sesión se reintenta 3 veces con 3 s y 8 s y una lectura agotada nunca se reintenta.",
            404 to "La lista de workflows va del más nuevo al más viejo y ningún workflow se presenta con un nombre de relleno de Graph.",
            405 to "Borrar un workflow que ya no existe cuenta como borrado; alinear antes de un workflow es best-effort pero su error queda en el log.",
            406 to "Interpretar pasos nunca revienta: sin respuesta de Graph devuelve que el modelo no opinó y lo dice.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"
    }

    /* ---------- El mapa a mano: transporte guionado ---------- */

    class Llamada(val metodo: String, val url: String, val body: String?, val headers: Map<String, String>, val tope: Duration?) {
        val json: JsonObject get() = Json.parseToJsonElement(body ?: error("$metodo $url no llevó cuerpo")).jsonObject
    }

    /**
     * Devuelve las respuestas en el orden del guion y graba cada llamada. [antes] corre con el número de
     * llamada antes de responder: ahí el guion se cuelga o lanza lo que lanzaría la red.
     */
    class TransporteGuionado(vararg guion: TransportReply, val antes: suspend (Int) -> Unit = {}) : TurnTransport {
        val llamadas = mutableListOf<Llamada>()
        private val cola = ArrayDeque(guion.toList())
        override suspend fun post(url: String, body: String, headers: Map<String, String>) = send("POST", url, body, headers)
        override suspend fun send(method: String, url: String, body: String?, headers: Map<String, String>, timeout: Duration?): TransportReply {
            llamadas += Llamada(method, url, body, headers, timeout)
            antes(llamadas.size)
            return cola.removeFirstOrNull() ?: error("guion agotado: llamada nº${llamadas.size} sin respuesta prevista")
        }
    }

    private fun ok(json: String) = TransportReply(200, json)

    private fun cliente(
        transporte: TurnTransport,
        esperas: MutableList<Long> = mutableListOf(),
        lineas: MutableList<String> = mutableListOf(),
        key: String = "miracle_k",
        email: String? = null,
        deviceId: String? = "dev-1",
        reloj: TestTimeSource = TestTimeSource(),
        topeGeneral: Duration = LearningClient.TOPE_GENERAL,
    ) = LearningClient(
        transport = transporte,
        credentials = { key },
        baseUrl = { "https://graph.test/" },
        email = { email },
        deviceId = { deviceId },
        log = GraphLog { tag, m -> lineas += "[$tag] $m" },
        sleep = { esperas += it; reloj += it.milliseconds },
        timeSource = reloj,
        topeGeneral = topeGeneral,
    )

    private fun JsonObject.texto(k: String) = this[k]?.jsonPrimitive?.content

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

    private val sesion = StartSessionRequest(
        description = "Registrar paciente",
        appId = "dev-1",
        sourceUrl = "android://com.x/RegistroActivity",
        sourceOrigin = "android://com.x",
        sourcePathname = "/RegistroActivity",
        sourceTitle = "",
        context = mapOf("surface" to "a11y", "platform" to "android"),
    )
    private val click = StepRequest(actionType = "click", selector = "a11y:id=com.x:id/ok", label = "OK", controlType = "button")
    private val pasoGuardado = TransportReply(200, """{"step":{"step_order":4}}""")
    private val pasosDeLaDemo = listOf(StepToRead(1, "Nombre", "Ana", "aquí va el nombre"), StepToRead(2, "País", "", ""))

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa401() = corre {
        val p = promesa(401)
        val interpretacion = """{"campos":[{"campo":"Nombre","esDato":true,"significado":"el paciente"}],"recuerdos":[{"campo":"País","significado":"siempre Colombia"}]}"""
        val plan = """{"execution_plan":{"workflowId":"wf-1","description":"","appId":"dev-1","sourceUrl":"android://com.x/RegistroActivity",
            "sourceOrigin":"android://com.x","sourcePathname":"/RegistroActivity","sourceTitle":"","executionGuide":"toca OK","planner":"v9",
            "variables":{"paciente":"Ana"},
            "steps":[
              {"stepOrder":1,"actionType":"navigation","selector":"","url":"android://com.x/RegistroActivity","label":"","surfaceHints":null},
              {"stepOrder":2,"actionType":"input","selector":"a11y:id=com.x:id/nombre","label":null,"controlType":"text","value":"Ana",
               "valueMode":"dynamic","bindTo":"paciente","nodeKey":"","extra":{"a":1},
               "surfaceHints":{"alternativeTargets":["a11y:text=Nombre","",7],"observedSurface":"android://com.x/RegistroActivity",
                               "readiness":12,"fingerprint":"a1b2","clickPos":"540,300","nodePath":"1\\2"}},
              {"stepOrder":3,"actionType":"click","selector":"a11y:id=com.x:id/ok","valueMode":"flexible","surfaceHints":{"readiness":"7","clickPos":"no"}}
            ]}}"""
        val t = TransporteGuionado(
            ok("""{"session":{"id":"ses-1","workflow_id":"","recording":null},"nuevo":1}"""),
            ok("""{"step":{"step_order":1}}"""),
            ok("""{"step":{"step_order":2}}"""),
            ok("""{"step":{"step_order":3},"error":""}"""),
            ok("{}"),
            ok("""{"workflow_id":"","summary":"","workflow":{"id":"wf-1"},"error":""}"""),
            ok(plan),
            ok("""{"geminiUploadUrl":"https://upload.test/x","archiveUploadUrl":"","archivePath":null}"""),
            ok("""{"state":""}"""),
            ok("""{"summary":"registra pacientes","interpretation":$interpretacion,"notes":[{"app":"com.x","note":""}],"questions":["¿siempre Colombia?"]}"""),
            ok("""{"summary":""}"""),
            ok("""{"interpretation":{"campos":[]}}"""),
        )
        val c = cliente(t)

        // La sesión: snake, las siete claves siempre, y un "" del request viaja.
        val info = c.crearSesion(sesion)
        assertEquals("ses-1", info.id, p)
        assertNull(info.workflowId, "$p · workflow_id \"\" no es un id")
        assertFalse(info.recording, "$p · recording null toma su default")
        val rSesion = t.llamadas[0].json
        assertEquals(setOf("description", "app_id", "source_url", "source_origin", "source_pathname", "source_title", "context"), rSesion.keys, p)
        assertEquals("", rSesion.texto("source_title"), "$p · un \"\" del request tiene que viajar")
        assertEquals("android", rSesion["context"]!!.jsonObject.texto("platform"), p)

        // El paso: camel; lo que no hay no viaja; alternativeTargets es lista y las otras pistas, texto.
        assertEquals(1, c.mandarPaso("ses-1", click), p)
        assertEquals(setOf("actionType", "selector", "label", "controlType"), t.llamadas[1].json.keys, "$p · lo que no hay no viaja")
        val completo = StepRequest(
            actionType = "select", selector = "a11y:id=com.x:id/pais", label = "País", controlType = "select",
            value = "co", explanation = "elige el país", selectedValue = "co", selectedLabel = "Colombia",
            allowedOptions = listOf(FieldOption("co", "Colombia", "Colombia"), FieldOption("mx", "México")),
            semanticTarget = "país de residencia", surfaceSection = "datos",
            surfaceHints = SurfaceHint.build(
                alternativeTargets = listOf("a11y:text=País", " ", "a11y:path=0/3/1"),
                pistas = mapOf(
                    SurfaceHint.OBSERVED_SURFACE to "android://com.x/RegistroActivity", SurfaceHint.READINESS to "12",
                    SurfaceHint.FINGERPRINT to "a1b2", SurfaceHint.CLICK_POS to "540,300", SurfaceHint.NODE_PATH to "",
                ),
            ),
        )
        assertEquals(2, c.mandarPaso("ses-1", completo), p)
        val rPaso = t.llamadas[2].json
        assertEquals(
            setOf("actionType", "selector", "label", "controlType", "value", "explanation", "selectedValue", "selectedLabel", "allowedOptions", "semanticTarget", "surfaceSection", "surfaceHints"),
            rPaso.keys, p,
        )
        val opciones = rPaso["allowedOptions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("value", "label", "text"), opciones[0].keys, p)
        assertEquals(setOf("value", "label"), opciones[1].keys, p)
        val pistas = rPaso["surfaceHints"]!!.jsonObject
        assertEquals(
            listOf("a11y:text=País", "a11y:path=0/3/1"), pistas["alternativeTargets"]!!.jsonArray.map { it.jsonPrimitive.content },
            "$p · alternativeTargets tiene que viajar como lista, que es como la lee Graph",
        )
        assertEquals(setOf("alternativeTargets", "observedSurface", "readiness", "fingerprint", "clickPos"), pistas.keys, "$p · una pista vacía no viaja")
        assertTrue(pistas["readiness"]!!.jsonPrimitive.isString, p)
        assertNull(SurfaceHint.build(emptyList(), mapOf(SurfaceHint.CLICK_POS to " ")), "$p · sin pistas, surfaceHints no viaja")
        val navegar = StepRequest(actionType = "navigation", selector = "", url = "android://com.x/RegistroActivity", label = "", controlType = "")
        assertEquals(3, c.mandarPaso("ses-1", navegar), "$p · un error \"\" con HTTP 200 no es un error")
        assertEquals("android://com.x/RegistroActivity", t.llamadas[3].json.texto("url"), p)

        // La nota y el cierre.
        c.notaDeContexto("ses-1", "es para pacientes nuevos")
        assertEquals(
            buildJsonObject { putJsonObject("note") { put("role", "clinical_context"); put("transcript", "es para pacientes nuevos"); put("mode", "training") } },
            t.llamadas[4].json, p,
        )
        val fin = c.terminar("ses-1")
        assertEquals(JsonObject(emptyMap()), t.llamadas[5].json, p)
        assertNull(fin.workflowId, p)
        assertNull(fin.summary, p)
        assertNull(fin.error, p)
        assertEquals("wf-1", fin.workflow?.jsonObject?.texto("id"), p)

        // El plan: lo vacío es ausente, lo nuevo no rompe y las pistas vuelven legibles.
        val ejecutable = c.plan("wf-1", mapOf("paciente" to "Ana"))
        val rPlan = t.llamadas[6].json
        assertEquals(setOf("variables", "execution_intent"), rPlan.keys, p)
        assertEquals("Ana", rPlan["variables"]!!.jsonObject.texto("paciente"), p)
        assertEquals(mapOf("source" to "android_app", "surface" to "native"), rPlan["execution_intent"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }, p)
        assertEquals("wf-1", ejecutable.workflowId, p)
        assertNull(ejecutable.description, p)
        assertNull(ejecutable.sourceTitle, p)
        assertEquals("toca OK", ejecutable.executionGuide, p)
        assertEquals(mapOf("paciente" to "Ana"), ejecutable.variables, p)
        assertEquals(listOf(1, 2, 3), ejecutable.steps.map { it.stepOrder }, p)
        val (navegacion, input, toque) = ejecutable.steps
        assertNull(navegacion.selector, p)
        assertNull(navegacion.label, p)
        assertEquals("android://com.x/RegistroActivity", navegacion.url, p)
        assertNull(navegacion.observedSurface(), p)
        assertEquals(0, navegacion.readiness(), p)
        assertEquals(emptyList<String>(), navegacion.alternativeTargets(), p)
        assertNull(input.label, p)
        assertNull(input.nodeKey, p)
        assertEquals("dynamic" to "paciente", input.valueMode to input.bindTo, p)
        assertEquals(listOf("a11y:text=Nombre"), input.alternativeTargets(), "$p · un respaldo vacío o que no es texto no es un selector")
        assertEquals("android://com.x/RegistroActivity", input.observedSurface(), p)
        assertEquals(12, input.readiness(), p)
        assertEquals("a1b2", input.fingerprint(), p)
        assertEquals(540 to 300, input.clickPos(), p)
        assertEquals("1\\2", input.nodePathOrHint(), p)
        assertEquals("flexible", toque.valueMode, p)
        assertEquals(7, toque.readiness(), "$p · readiness como texto")
        assertNull(toque.clickPos(), p)

        // /teach/*: sus claves, lo vacío ausente y la interpretación cruda, idéntica.
        val token = c.uploadToken(1_048_576L, "u-1")
        assertEquals(setOf("contentLength", "userId"), t.llamadas[7].json.keys, p)
        assertEquals(1_048_576L, t.llamadas[7].json["contentLength"]!!.jsonPrimitive.long, p)
        assertEquals("https://upload.test/x", token.geminiUploadUrl, p)
        assertNull(token.archiveUploadUrl, "$p · archiveUploadUrl \"\" no es una URL")
        assertNull(token.archivePath, p)
        assertEquals("UNKNOWN", c.fileState("files/abc"), "$p · un state \"\" no es un estado")
        assertEquals(setOf("fileUri"), t.llamadas[8].json.keys, p)
        val leido = c.processVideo("files/abc", "u-1", pasosDeLaDemo)
        val rVideo = t.llamadas[9].json
        assertEquals(setOf("fileUri", "userId", "steps"), rVideo.keys, p)
        assertEquals(setOf("order", "field", "value", "said"), rVideo["steps"]!!.jsonArray[1].jsonObject.keys, "$p · un \"\" del paso viaja")
        assertEquals(Json.parseToJsonElement(interpretacion), leido.interpretation, "$p · la interpretación llega cruda e idéntica")
        assertEquals("registra pacientes", leido.summary, p)
        assertNull(leido.notes!!.single().note, p)
        assertEquals(listOf("¿siempre Colombia?"), leido.questions, p)
        assertNull(c.processVideo("files/abc", "u-1").summary, p)
        assertEquals(setOf("fileUri", "userId"), t.llamadas[10].json.keys, "$p · sin pasos, steps no viaja")
        c.interpretSteps("android://com.x/RegistroActivity", pasosDeLaDemo)
        assertEquals(setOf("startsAt", "steps"), t.llamadas[11].json.keys, p)

        // createdAt: entero Neo4j con low negativo (sin signo) o número llano; sin fecha legible, ausente.
        val ms = 1_789_054_200_000L // 2026-09-10 15:30 UTC: su mitad baja pasa de 2^31
        val low = (ms and 0xFFFFFFFFL).toInt()
        val high = (ms shr 32).toInt()
        assertTrue(low < 0, "$p · el fixture tiene que tener low negativo para juzgar el signo")
        fun creado(v: String) = WorkflowResumen.desdeJson(Json.parseToJsonElement("""{"id":"wf","createdAt":$v}""")).creadoEnMs
        assertEquals(ms, creado("""{"low":$low,"high":$high}"""), "$p · createdAt {low negativo, high}")
        assertEquals(ms, creado("""{"low":${ms and 0xFFFFFFFFL},"high":$high}"""), p)
        assertEquals(ms, creado("$ms"), p)
        assertNull(creado("\"\""), p)
        assertNull(creado("null"), p)
    }

    @Test
    fun promesa402() = corre {
        for (email in listOf(null, " ", "ana@example.com")) {
            val t = TransporteGuionado(
                ok("""{"session":{"id":"ses/1"}}"""),
                ok("""{"step":{"step_order":1}}"""),
                ok("{}"),
                ok("""{"workflow_id":"wf 1"}"""),
                ok("""{"workflows":[]}"""),
                ok("""{"workflow":{"id":"wf 1"}}"""),
                ok(""),
                ok("""{"execution_plan":{"workflowId":"wf 1"}}"""),
                ok("{}"),
                ok("""{"geminiUploadUrl":"https://upload.test/x"}"""),
                ok("""{"state":"ACTIVE"}"""),
                ok("""{"summary":"s"}"""),
                ok("""{"interpretation":{"campos":[]}}"""),
            )
            val c = cliente(t, email = email, deviceId = "dev-1")
            c.crearSesion(sesion)
            c.mandarPaso("ses/1", click)
            c.notaDeContexto("ses/1", "nota")
            c.terminar("ses/1")
            c.listarWorkflows()
            c.workflow("wf 1")
            c.borrar("wf 1")
            c.plan("wf 1")
            assertTrue(c.prependAlignment("wf 1"), promesa(402))
            c.uploadToken(10, "u-1")
            c.fileState("files/abc")
            c.processVideo("files/abc", "u-1")
            assertNotNull(c.interpretSteps("android://com.x", pasosDeLaDemo), promesa(402))

            assertEquals(
                listOf(
                    "POST https://graph.test/api/v1/learning/sessions",
                    "POST https://graph.test/api/v1/learning/sessions/ses%2F1/steps",
                    "POST https://graph.test/api/v1/learning/sessions/ses%2F1/context-notes",
                    "POST https://graph.test/api/v1/learning/sessions/ses%2F1/finish",
                    "GET https://graph.test/api/v1/workflows",
                    "GET https://graph.test/api/v1/workflows/wf%201",
                    "DELETE https://graph.test/api/v1/workflows/wf%201",
                    "POST https://graph.test/api/v1/workflows/wf%201/plan",
                    "POST https://graph.test/api/v1/workflows/wf%201/prepend-alignment",
                    "POST https://graph.test/api/v1/teach/upload-token",
                    "POST https://graph.test/api/v1/teach/file-state",
                    "POST https://graph.test/api/v1/teach/process-video",
                    "POST https://graph.test/api/v1/teach/interpret-steps",
                ),
                t.llamadas.map { "${it.metodo} ${it.url}" },
                promesa(402) + " · método y ruta, con el id escapado",
            )
            for (l in t.llamadas) {
                val h = l.headers
                val donde = promesa(402) + " · ${l.metodo} ${l.url} · email=«$email»"
                assertEquals("miracle_k", h["X-API-Key"], donde)
                assertEquals("android_app", h["X-Miracle-App"], donde)
                assertEquals("dev-1", h["X-Miracle-Device-Id"], donde)
                assertEquals(email?.takeIf { it.isNotBlank() }, h["X-Miracle-User-Email"], "$donde · el email solo si existe")
                val teach = "/api/v1/teach/" in l.url
                assertEquals(if (teach) "conscious_bridge" else null, h["X-Miracle-Feature"], "$donde · X-Miracle-Feature solo en /teach/*")
                assertFalse("miracle_k" in l.url || "miracle_k" in l.body.orEmpty(), "$donde · la key viajó fuera de su cabecera")
                if (l.metodo == "GET" || l.metodo == "DELETE") assertNull(l.body, "$donde · un ${l.metodo} no lleva cuerpo")
            }
        }
    }

    @Test
    fun promesa403() = corre {
        val p = promesa(403)
        // Como en el cerebro: 503, 0, 200 → 3 llamadas y esperas crecientes.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(503, ""), TransportReply(0, "Connect timed out"), pasoGuardado)
            assertEquals(4, cliente(t, esperas).mandarPaso("ses-1", click), p)
            assertEquals(3, t.llamadas.size, p)
            assertEquals(listOf(800L, 1600L), esperas, p)
        }
        // Cada transitorio, solo y primero, se reintenta.
        for (code in listOf(0, 408, 429, 502, 503, 504)) {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(code, ""), pasoGuardado)
            cliente(t, esperas).mandarPaso("ses-1", click)
            assertEquals(2, t.llamadas.size, "$p · HTTP $code no se reintentó")
            assertEquals(listOf(800L), esperas, "$p · HTTP $code")
        }
        // 1 intento + 3 reintentos y se rinde, con el status y la causa.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(504, ""), TransportReply(502, ""), TransportReply(408, ""), TransportReply(429, """{"error":"sin cupo"}"""))
            val e = lanzaExacto<GraphException>(p) { cliente(t, esperas).mandarPaso("ses-1", click) }
            assertEquals(4, t.llamadas.size, p)
            assertEquals(listOf(800L, 1600L, 3200L), esperas, p)
            assertEquals(429, e.status, p)
            assertTrue(e.transitorio, p)
            assertTrue("HTTP 429" in e.message.orEmpty() && "sin cupo" in e.message.orEmpty(), "$p · mensaje: ${e.message}")
        }
        // 429 con Retry-After: se espera eso.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(429, "", retryAfterSeconds = 4), pasoGuardado)
            cliente(t, esperas).mandarPaso("ses-1", click)
            assertEquals(listOf(4_000L), esperas, "$p · Retry-After")
        }
        // Una lectura agotada nunca se reintenta: Graph pudo haber recibido, y cobrado, la llamada.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(-1, "Read timed out"), pasoGuardado)
            val e = lanzaExacto<GraphException>(p) { cliente(t, esperas).mandarPaso("ses-1", click) }
            assertEquals(1, t.llamadas.size, "$p · la lectura agotada se reintentó")
            assertTrue(esperas.isEmpty(), p)
            assertEquals(-1, e.status, p)
            assertFalse(e.transitorio, p)
            assertTrue("no se reintentó para no cobrar dos veces" in e.message.orEmpty(), "$p · mensaje: ${e.message}")
        }
        // Un 400 es un error de contrato: reintentarlo solo lo repite.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(400, """{"error":"actionType inválido"}"""), pasoGuardado)
            val e = lanzaExacto<GraphException>(p) { cliente(t, esperas).mandarPaso("ses-1", click) }
            assertEquals(1, t.llamadas.size, p)
            assertTrue("actionType inválido" in e.message.orEmpty(), "$p · mensaje: ${e.message}")
        }
        // El tope de la llamada cuenta intentos y esperas: un 503 que tarda 89,5 s ya no deja reintentar.
        run {
            val reloj = TestTimeSource()
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(503, ""), pasoGuardado, antes = { reloj += 89_500.milliseconds })
            val e = lanzaExacto<GraphException>(p) { cliente(t, esperas, reloj = reloj).mandarPaso("ses-1", click) }
            assertEquals(1, t.llamadas.size, "$p · reintentó pasado el tope")
            assertTrue("90 s" in e.message.orEmpty(), "$p · mensaje: ${e.message}")
        }
        // Una llamada colgada se corta en su tope y cuenta como lectura agotada.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(pasoGuardado, antes = { awaitCancellation() })
            val e = lanzaExacto<GraphException>(p) {
                withTimeout(3.seconds) { cliente(t, esperas, topeGeneral = 200.milliseconds).mandarPaso("ses-1", click) }
            }
            assertEquals(1, t.llamadas.size, p)
            assertEquals(-1, e.status, "$p · la llamada colgada no se cortó en su tope: ${e.message}")
        }
        // El tope que recibe el transporte: 90 s general, 5 min en /teach/*.
        run {
            val t = TransporteGuionado(pasoGuardado, ok("""{"geminiUploadUrl":"https://upload.test/x"}"""))
            val c = cliente(t)
            c.mandarPaso("ses-1", click)
            c.uploadToken(1, "u-1")
            assertEquals(listOf<Duration?>(90.seconds, 5.minutes), t.llamadas.map { it.tope }, p)
        }
        // terminar: 3 intentos, 3 s y 8 s; si no, FinishPendiente con la sesión.
        run {
            val esperas = mutableListOf<Long>()
            val vercel = TransportReply(504, """{"error":"FUNCTION_INVOCATION_TIMEOUT"}""")
            val t = TransporteGuionado(vercel, vercel, vercel, ok("{}"))
            val e = lanzaExacto<FinishPendiente>(p) { cliente(t, esperas).terminar("ses-1") }
            assertEquals(3, t.llamadas.size, "$p · el cierre no se intentó exactamente 3 veces")
            assertEquals(listOf(3_000L, 8_000L), esperas, p)
            assertEquals("ses-1" to "ses-1", e.sessionId to e.workflowId, p)
            assertEquals(504, e.status, p)
            assertTrue("los pasos ya están guardados" in e.message.orEmpty(), "$p · mensaje: ${e.message}")
            assertEquals(listOf<Duration?>(90.seconds, 90.seconds, 90.seconds), t.llamadas.map { it.tope }, "$p · cada intento del cierre tiene su tope")
        }
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(504, ""), ok("""{"workflow_id":"wf-9","summary":"registra pacientes"}"""))
            assertEquals("wf-9", cliente(t, esperas).terminar("ses-1").workflowId, p)
            assertEquals(2, t.llamadas.size, p)
            assertEquals(listOf(3_000L), esperas, p)
        }
        for (reply in listOf(TransportReply(-1, "Read timed out"), TransportReply(400, """{"error":"sesión desconocida"}"""))) {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(reply, ok("{}"))
            val e = lanzaExacto<GraphException>(p) { cliente(t, esperas).terminar("ses-1") }
            assertEquals(1, t.llamadas.size, "$p · HTTP ${reply.status} en el cierre se reintentó")
            assertTrue(esperas.isEmpty(), p)
            assertEquals(reply.status, e.status, p)
        }
        // Cancelar no es red: sale tal cual, sin reintento.
        for (llamar in listOf<suspend (LearningClient) -> Unit>({ it.mandarPaso("ses-1", click) }, { it.terminar("ses-1") })) {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(ok("{}"), antes = { throw CancellationException("el usuario canceló") })
            lanzaExacto<CancellationException>(p) { llamar(cliente(t, esperas)) }
            assertEquals(1, t.llamadas.size, p)
            assertTrue(esperas.isEmpty(), p)
        }
    }

    @Test
    fun promesa404() = corre {
        val p = promesa(404)
        val bogota: (Long) -> Long = { -5 * 3_600_000L }
        val t0 = 1_788_374_520_000L // 2026-09-02 18:42 UTC = 13:42 en Bogotá
        fun neo4j(ms: Long) = """{"low":${(ms and 0xFFFFFFFFL).toInt()},"high":${(ms shr 32).toInt()}}"""
        fun wf(id: String, desc: String, creado: String, pasos: Int) =
            """{"id":"$id","description":"$desc","summary":"","sourceOrigin":"android://com.miui.calculator","sourceTitle":"Calculadora","createdAt":$creado,"totalSteps":$pasos}"""
        val t = TransporteGuionado(
            // Como llega de Graph: del más viejo al más nuevo.
            ok("""{"workflows":[${wf("wf_viejo", "Workflow sin descripción", neo4j(t0), 6)},${wf("wf_medio", "User workflow summary:", neo4j(t0 + 300_000), 3)},${wf("wf_nuevo", "Radicar factura", "${t0 + 540_000}", 1)}]}"""),
            ok("""{"workflows":[{"id":"wf_1","description":""},{"id":"wf_3","description":"No description"},{"id":"wf_2"}]}"""),
        )
        val c = cliente(t)
        val lista = c.listarWorkflows()
        assertEquals(listOf("wf_nuevo", "wf_medio", "wf_viejo"), lista.map { it.id }, "$p · lo último que enseñaste es lo primero que ves")
        val sinFecha = c.listarWorkflows()
        assertEquals(listOf("wf_3", "wf_2", "wf_1"), sinFecha.map { it.id }, "$p · sin fecha, por id descendente")
        assertEquals("GET", t.llamadas[0].metodo, p)

        val nombres = (lista + sinFecha).associate { it.id to it.nombre(bogota) }
        for ((id, nombre) in nombres) {
            assertTrue(nombre.isNotBlank(), "$p · $id no tiene nombre")
            assertFalse(NombreDeWorkflow.esRelleno(nombre), "$p · $id se presenta como «$nombre»")
            assertTrue(listOf("sin descripción", "summary", "no description").none { it in nombre.lowercase() }, "$p · $id se presenta como «$nombre»")
        }
        val viejo = nombres.getValue("wf_viejo")
        assertTrue("com.miui.calculator" in viejo && "Calculadora" in viejo && "2 sep 13:42" in viejo && "6 pasos" in viejo, "$p · salió «$viejo»")
        val medio = nombres.getValue("wf_medio")
        assertTrue("2 sep 13:47" in medio && "3 pasos" in medio, "$p · salió «$medio»")
        assertEquals("Radicar factura", nombres.getValue("wf_nuevo"), "$p · una descripción de verdad se respeta tal cual")
        assertEquals("0 pasos", nombres.getValue("wf_2"), p)

        assertEquals("com.miui.calculator · Calculadora · 2 sep 13:42 · 1 paso", NombreDeWorkflow.derivar("com.miui.calculator", "Calculadora", t0, 1, bogota), p)
        assertEquals("Claude · 2 sep 13:42 · 2 pasos", NombreDeWorkflow.derivar("Claude", "claude", t0, 2, bogota), "$p · la ventana igual a la app no se repite")
        for (relleno in listOf("Workflow sin descripción", "workflow sin descripcion", "User workflow summary: registrar", "Resumen:", "No description", "", "  ", null)) {
            assertTrue(NombreDeWorkflow.esRelleno(relleno), "$p · «$relleno» pasó por nombre")
        }
        assertFalse(NombreDeWorkflow.esRelleno("Radicar factura"), p)
    }

    @Test
    fun promesa405() = corre {
        val p = promesa(405)
        // Borrar lo que ya no existe: borrado, sin reintento y sin excepción.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(404, """{"error":"Workflow not found"}"""))
            cliente(t, esperas).borrar("wf_ido")
            assertEquals("DELETE https://graph.test/api/v1/workflows/wf_ido", t.llamadas.single().let { "${it.metodo} ${it.url}" }, p)
            assertTrue(esperas.isEmpty(), p)
        }
        run {
            val t = TransporteGuionado(ok(""))
            cliente(t).borrar("wf_1")
            assertEquals(1, t.llamadas.size, p)
        }
        run {
            val t = TransporteGuionado(TransportReply(500, """{"error":"neo4j caído"}"""))
            val e = lanzaExacto<GraphException>(p) { cliente(t).borrar("wf_1") }
            assertEquals(500, e.status, p)
            assertTrue("neo4j caído" in e.message.orEmpty(), "$p · mensaje: ${e.message}")
        }
        // Alinear: best-effort, pero su error queda en el log.
        run {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(TransportReply(500, """{"error":"no sé alinear esta app"}"""))
            assertFalse(cliente(t, lineas = lineas).prependAlignment("wf_7"), p)
            assertTrue(lineas.any { "wf_7" in it && "no sé alinear esta app" in it }, "$p · el log no lo dice: $lineas")
        }
        run {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(antes = { throw IllegalStateException("socket cerrado") })
            assertFalse(cliente(t, lineas = lineas).prependAlignment("wf_7"), p)
            assertTrue(lineas.any { "wf_7" in it && "socket cerrado" in it }, "$p · el log no lo dice: $lineas")
        }
        run {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(ok("{}"))
            assertTrue(cliente(t, lineas = lineas).prependAlignment("wf_8"), p)
            assertTrue(lineas.none { "falló" in it }, "$p · un 200 dejó un fallo en el log: $lineas")
        }
        run {
            val t = TransporteGuionado(ok("{}"), antes = { throw CancellationException("el usuario canceló") })
            lanzaExacto<CancellationException>(p) { cliente(t).prependAlignment("wf_7") }
        }
    }

    @Test
    fun promesa406() = corre {
        val p = promesa(406)
        val sinRespuesta = listOf(
            Triple("503 ×4", arrayOf(TransportReply(503, ""), TransportReply(503, ""), TransportReply(503, ""), TransportReply(503, "")), "HTTP 503"),
            Triple("lectura agotada", arrayOf(TransportReply(-1, "Read timed out")), "no respondió a tiempo"),
            Triple("HTTP 500", arrayOf(TransportReply(500, """{"error":"gemini sin créditos"}""")), "gemini sin créditos"),
            Triple("cuerpo ilegible", arrayOf(ok("<html>504</html>")), "no se pudo leer"),
            Triple("sin interpretation", arrayOf(ok("{}")), "sin interpretación"),
            Triple("interpretation null", arrayOf(ok("""{"interpretation":null}""")), "sin interpretación"),
            Triple("interpretation vacía", arrayOf(ok("""{"interpretation":""}""")), "sin interpretación"),
        )
        for ((caso, guion, causa) in sinRespuesta) {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(*guion)
            assertNull(cliente(t, lineas = lineas).interpretSteps("android://com.x/Registro", pasosDeLaDemo), "$p · $caso")
            assertTrue(lineas.any { "no opinó" in it && causa in it }, "$p · $caso: el log no lo dice: $lineas")
        }
        run {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(antes = { throw IllegalArgumentException("no protocol: htps//graph.test") })
            assertNull(cliente(t, lineas = lineas).interpretSteps("android://com.x/Registro", pasosDeLaDemo), p)
            assertTrue(lineas.any { "no opinó" in it && "no protocol" in it }, "$p · el log no lo dice: $lineas")
        }
        for ((caso, key, pasos) in listOf(Triple("sin key", " ", pasosDeLaDemo), Triple("sin pasos", "miracle_k", emptyList()))) {
            val lineas = mutableListOf<String>()
            val t = TransporteGuionado(ok("""{"interpretation":{"campos":[]}}"""))
            assertNull(cliente(t, lineas = lineas, key = key).interpretSteps("android://com.x/Registro", pasos), "$p · $caso")
            assertTrue(t.llamadas.isEmpty(), "$p · $caso: llamó a Graph")
            assertTrue(lineas.any { "no opinó" in it }, "$p · $caso: el log no lo dice: $lineas")
        }
        run {
            val interpretacion = """{"campos":[{"campo":"Nombre","esDato":true,"significado":"el paciente"}],"recuerdos":[]}"""
            val t = TransporteGuionado(ok("""{"interpretation":$interpretacion}"""))
            assertEquals(Json.parseToJsonElement(interpretacion), cliente(t).interpretSteps("android://com.x/Registro", pasosDeLaDemo), p)
        }
        run {
            val t = TransporteGuionado(ok("{}"), antes = { throw CancellationException("el usuario canceló") })
            lanzaExacto<CancellationException>("$p · cancelar no es que el modelo no opinó") {
                cliente(t).interpretSteps("android://com.x/Registro", pasosDeLaDemo)
            }
        }
    }
}
