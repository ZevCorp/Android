package graph.core.contrato

import graph.core.application.ExecutionEngine
import graph.core.domain.AgentAction
import graph.core.domain.Gestures
import graph.core.domain.Mcp
import graph.core.domain.Phone
import graph.core.domain.ScreenState
import graph.core.domain.SystemApi
import graph.core.graph.AndroidSurface
import graph.core.graph.Credential
import graph.core.graph.GraphBrain
import graph.core.graph.GraphCredentials
import graph.core.graph.GraphHeaders
import graph.core.graph.TransportReply
import graph.core.graph.TurnJson
import graph.core.graph.TurnResponse
import graph.core.graph.TurnTransport
import graph.core.graph.toBrainTurn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CONTRATO 001 — EL CEREBRO VIVE EN GRAPH (docs/specs/001-el-cerebro-vive-en-graph.md).
 *
 * Cada `promesaNN` es una fila de la tabla de la spec, con el enunciado literal en [PROMESAS]. Se
 * escribieron ANTES que el código que juzgan: nacieron rojas. Ninguna toca red, Android ni disco:
 * el transporte es un guion y la espera entre reintentos se anota en vez de dormir.
 */
class Contrato001CerebroEnGraph {

    companion object {
        val PROMESAS = mapOf(
            1 to "El primer turno lleva el objetivo y ningún session; los siguientes llevan el session que devolvió Graph y no repiten el objetivo.",
            2 to "Los resultados de las acciones viajan en `results` en el mismo orden; la respuesta a una pregunta viaja en `inform` una sola vez y nunca en `results`.",
            3 to "La captura viaja solo cuando el turno anterior la pidió, como PNG en base64 sin prefijo data-uri; si no la pidió, el campo no viaja.",
            4 to "Cada acción de Graph se traduce a la acción local equivalente (tap, type, scroll, swipe, key, wait, mcp); una acción desconocida no rompe la corrida: su resultado es \"acción desconocida: <kind>\".",
            5 to "`done`, `question`, `text`, `narration`, `speech` e `intents` de Graph llegan al motor tal cual.",
            6 to "Un HTTP transitorio (0, 408, 429, 502, 503, 504) se reintenta hasta 3 veces con espera creciente; 401 o 403 no se reintenta y dice que la key de Graph no vale; un `error` en el cuerpo termina el turno con ese texto.",
            7 to "El cliente no manda modelo, prompt ni catálogo de herramientas: el request solo tiene session, goal, userId, state, results e inform.",
            8 to "Cada request lleva `X-API-Key`, `X-Miracle-App: android_app` y `X-Miracle-Feature: conscious_bridge`; el email y el id de dispositivo viajan solo si existen.",
            9 to "La key de Graph se resuelve prefs sobre compilada; sin key, el proveedor GRAPH no llama a nadie y dice en una línea qué falta.",
            10 to "La superficie se deriva del paquete y la pantalla: origin `android://<paquete>`, pathname `/<pantalla>`, id = origin + pathname.",
            11 to "Un `session` devuelto por Graph se conserva byte a byte y vuelve en el siguiente request aunque contenga JSON, comillas o caracteres no ASCII.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"
    }

    /* ---------- El mapa a mano: transporte guionado y estado de pantalla fijo ---------- */

    class Grabado(val url: String, val body: String, val headers: Map<String, String>) {
        val json: JsonObject get() = TurnJson.parseToJsonElement(body).jsonObject
    }

    /** Devuelve las respuestas en el orden del guion y graba cada request. */
    class TransporteGuionado(vararg guion: TransportReply) : TurnTransport {
        val requests = mutableListOf<Grabado>()
        private val cola = ArrayDeque(guion.toList())
        override suspend fun post(url: String, body: String, headers: Map<String, String>): TransportReply {
            requests += Grabado(url, body, headers)
            return cola.removeFirstOrNull() ?: error("guion agotado: request nº${requests.size} sin respuesta prevista")
        }
    }

    private fun ok(json: String) = TransportReply(200, json)
    private fun respuesta(r: TurnResponse) = ok(TurnJson.encodeToString(r))
    private val fin = ok("""{"session":"s-fin","done":true,"text":"listo"}""")

    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val pantalla = ScreenState("com.miui.calculator · Calculadora", "botones: 1 2 3", 1080, 2400)
    private val pantallaConFoto = ScreenState("com.miui.calculator · Calculadora", "botones: 1 2 3", 1080, 2400, PNG)

    private fun cerebro(
        transporte: TurnTransport,
        esperas: MutableList<Long> = mutableListOf(),
        key: String = "miracle_k",
        email: String? = null,
        deviceId: String? = null,
    ) = GraphBrain(
        transport = transporte,
        credentials = { key },
        baseUrl = { "https://graph.test/" },
        userId = { "u-1" },
        email = { email },
        deviceId = { deviceId },
        listApps = { listOf("Calculadora", "Ajustes") },
        sleep = { esperas += it },
    )

    private fun JsonObject.texto(k: String) = this[k]?.jsonPrimitive?.content
    private fun JsonObject.lista(k: String) = this[k]?.jsonArray?.map { it.jsonPrimitive.content }

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa01() = corre {
        val t = TransporteGuionado(ok("""{"session":"s1","actions":[{"kind":"wait","ms":1}]}"""), fin)
        val b = cerebro(t)
        b.begin("abrí la calculadora")
        b.next(pantalla, emptyList())
        b.next(pantalla, listOf("ok"))

        val r1 = t.requests[0].json
        val r2 = t.requests[1].json
        assertEquals("abrí la calculadora", r1.texto("goal"), promesa(1))
        assertNull(r1["session"], promesa(1))
        assertEquals("s1", r2.texto("session"), promesa(1))
        assertNull(r2["goal"], promesa(1))
        assertEquals("s-fin", b.interactionId, promesa(1))
    }

    @Test
    fun promesa02() = corre {
        val t = TransporteGuionado(
            ok("""{"session":"s1","question":"¿cuál?"}"""),
            ok("""{"session":"s2","actions":[{"kind":"wait","ms":1}]}"""),
            fin,
        )
        val b = cerebro(t)
        b.begin("elegí una")
        val turno = b.next(pantalla, emptyList())
        assertEquals("¿cuál?", turno.question, promesa(2))
        b.inform("la roja")
        b.next(pantalla, listOf("ok", "no se pudo ejecutar la acción"))
        b.next(pantalla, listOf("ok"))

        val r2 = t.requests[1].json
        val r3 = t.requests[2].json
        assertEquals(listOf("ok", "no se pudo ejecutar la acción"), r2.lista("results"), promesa(2))
        assertEquals("la roja", r2.texto("inform"), promesa(2))
        assertNull(r3["inform"], promesa(2))
        assertFalse(t.requests.any { "la roja" in (it.json.lista("results") ?: emptyList()) }, promesa(2))
    }

    @Test
    fun promesa03() = corre {
        val t = TransporteGuionado(
            ok("""{"session":"s1","needsScreenshot":false}"""),
            ok("""{"session":"s2","needsScreenshot":true}"""),
            fin,
        )
        val b = cerebro(t)
        b.begin("mirá")
        b.next(pantalla, emptyList())            // nadie pidió captura todavía
        b.next(pantallaConFoto, emptyList())     // el turno 1 no la pidió: no viaja aunque haya PNG
        b.next(pantallaConFoto, emptyList())     // el turno 2 la pidió: viaja

        val estado2 = t.requests[1].json["state"]!!.jsonObject
        val estado3 = t.requests[2].json["state"]!!.jsonObject
        assertNull(estado2["screenshot"], promesa(3))
        assertEquals("iVBORw0KGgo=", estado3.texto("screenshot"), promesa(3))
    }

    @Test
    fun promesa04() = corre {
        val r = TurnJson.decodeFromString(
            TurnResponse.serializer(),
            """{"session":"s","actions":[
                {"kind":"tap","x":10,"y":20},
                {"kind":"type","x":30,"y":40,"text":"hola"},
                {"kind":"scroll","down":true},
                {"kind":"swipe","x1":1,"y1":2,"x2":3,"y2":4,"ms":500},
                {"kind":"key","key":"back"},
                {"kind":"wait","ms":250},
                {"kind":"mcp","tool":"set_alarm","args":{"hour":"7","minute":"30"}},
                {"kind":"teleport"}
            ]}""",
        )
        val a = r.toBrainTurn().actions
        assertEquals(8, a.size, promesa(4))
        assertIs<AgentAction.Tap>(a[0], promesa(4)).let { assertEquals(10 to 20, it.x to it.y, promesa(4)) }
        assertIs<AgentAction.Type>(a[1], promesa(4)).let { assertEquals(Triple(30, 40, "hola"), Triple(it.x, it.y, it.text), promesa(4)) }
        assertIs<AgentAction.Scroll>(a[2], promesa(4)).let { assertTrue(it.down, promesa(4)) }
        assertIs<AgentAction.Swipe>(a[3], promesa(4)).let { assertEquals(listOf(1, 2, 3, 4, 500L), listOf(it.x1, it.y1, it.x2, it.y2, it.ms), promesa(4)) }
        assertIs<AgentAction.Key>(a[4], promesa(4)).let { assertEquals("back", it.key, promesa(4)) }
        assertIs<AgentAction.Wait>(a[5], promesa(4)).let { assertEquals(250L, it.ms, promesa(4)) }
        assertIs<AgentAction.Mcp>(a[6], promesa(4)).let { assertEquals("set_alarm" to mapOf("hour" to "7", "minute" to "30"), it.tool to it.args, promesa(4)) }
        assertIs<AgentAction.Unknown>(a[7], promesa(4)).let { assertEquals("teleport", it.kind, promesa(4)) }

        // La corrida entera con el motor real: la desconocida no aborta y su resultado vuelve a Graph.
        val t = TransporteGuionado(
            ok("""{"session":"s1","actions":[{"kind":"teleport"},{"kind":"wait","ms":1}]}"""),
            fin,
        )
        val motor = ExecutionEngine(brain = { cerebro(t) }, phone = TelefonoFalso, mcp = Mcp(GestosFalsos, SistemaFalso), stepDelay = { 0 })
        assertEquals("listo", motor.run("probá", announce = false), promesa(4))
        assertEquals(listOf("acción desconocida: teleport", "ok"), t.requests[1].json.lista("results"), promesa(4))
    }

    @Test
    fun promesa05() {
        val r = TurnJson.decodeFromString(
            TurnResponse.serializer(),
            """{"session":"s","done":true,"question":"¿seguro?","text":"terminé","narration":"voy",
                "speech":"listo","intents":["abro","toco"],"needsScreenshot":true}""",
        )
        val turno = r.toBrainTurn()
        assertTrue(turno.done, promesa(5))
        assertEquals("¿seguro?", turno.question, promesa(5))
        assertEquals("terminé", turno.text, promesa(5))
        assertEquals("voy", turno.narration, promesa(5))
        assertEquals("listo", turno.speech, promesa(5))
        assertEquals(listOf("abro", "toco"), turno.intents, promesa(5))
        assertTrue(turno.needsScreenshot, promesa(5))
    }

    @Test
    fun promesa06() = corre {
        // Transitorio que se recupera: 3 requests, dos esperas crecientes.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(503, ""), TransportReply(0, ""), ok("""{"session":"s1"}"""))
            val b = cerebro(t, esperas); b.begin("x")
            b.next(pantalla, emptyList())
            assertEquals(3, t.requests.size, promesa(6))
            assertEquals(listOf(800L, 1600L), esperas, promesa(6))
        }
        // Transitorio que no se recupera: 1 intento + 3 reintentos y se rinde.
        run {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(502, ""), TransportReply(408, ""), TransportReply(429, ""), TransportReply(504, ""))
            val b = cerebro(t, esperas); b.begin("x")
            assertFailsWith<IllegalStateException>(promesa(6)) { b.next(pantalla, emptyList()) }
            assertEquals(4, t.requests.size, promesa(6))
            assertEquals(listOf(800L, 1600L, 3200L), esperas, promesa(6))
        }
        // Key inválida: no se reintenta y lo dice.
        for (code in listOf(401, 403)) {
            val esperas = mutableListOf<Long>()
            val t = TransporteGuionado(TransportReply(code, """{"error":"unauthorized"}"""), ok("""{"session":"s1"}"""))
            val b = cerebro(t, esperas); b.begin("x")
            val e = assertFailsWith<IllegalStateException>(promesa(6)) { b.next(pantalla, emptyList()) }
            assertTrue("la key de graph no vale" in e.message.orEmpty(), promesa(6) + " · mensaje: ${e.message}")
            assertEquals(1, t.requests.size, promesa(6))
            assertTrue(esperas.isEmpty(), promesa(6))
        }
        // `error` en el cuerpo (aun con HTTP 200): el turno termina con ese texto.
        run {
            val t = TransporteGuionado(ok("""{"session":"s1","error":"sin cupo"}"""))
            val b = cerebro(t); b.begin("x")
            val e = assertFailsWith<IllegalStateException>(promesa(6)) { b.next(pantalla, emptyList()) }
            assertEquals("sin cupo", e.message, promesa(6))
        }
    }

    @Test
    fun promesa07() = corre {
        val t = TransporteGuionado(ok("""{"session":"s1","question":"¿?"}"""), fin)
        val b = cerebro(t); b.begin("x")
        b.next(pantallaConFoto, emptyList())
        b.inform("sí")
        b.next(pantalla, listOf("ok"))
        val permitidas = setOf("session", "goal", "userId", "state", "results", "inform")
        for (req in t.requests) {
            assertTrue(req.json.keys.all { it in permitidas }, promesa(7) + " · claves: ${req.json.keys}")
        }
        assertEquals(setOf("session", "userId", "state", "results", "inform"), t.requests[1].json.keys, promesa(7))
    }

    @Test
    fun promesa08() = corre {
        val sin = GraphHeaders.build("miracle_k", null, null)
        assertEquals("miracle_k", sin["X-API-Key"], promesa(8))
        assertEquals("android_app", sin["X-Miracle-App"], promesa(8))
        assertEquals("conscious_bridge", sin["X-Miracle-Feature"], promesa(8))
        assertFalse("X-Miracle-User-Email" in sin, promesa(8))
        assertFalse("X-Miracle-Device-Id" in sin, promesa(8))

        val con = GraphHeaders.build("miracle_k", "dev@itsmiracleai.com", "abc123")
        assertEquals("dev@itsmiracleai.com", con["X-Miracle-User-Email"], promesa(8))
        assertEquals("abc123", con["X-Miracle-Device-Id"], promesa(8))

        val t = TransporteGuionado(ok("""{"session":"s1"}"""))
        val b = cerebro(t, email = "dev@itsmiracleai.com", deviceId = "abc123"); b.begin("x")
        b.next(pantalla, emptyList())
        val h = t.requests[0].headers
        assertEquals("miracle_k", h["X-API-Key"], promesa(8))
        assertEquals("android_app", h["X-Miracle-App"], promesa(8))
        assertEquals("conscious_bridge", h["X-Miracle-Feature"], promesa(8))
        assertEquals("dev@itsmiracleai.com", h["X-Miracle-User-Email"], promesa(8))
        assertEquals("abc123", h["X-Miracle-Device-Id"], promesa(8))
        assertEquals("https://graph.test/api/v1/agent/turn", t.requests[0].url, promesa(8))
    }

    @Test
    fun promesa09() = corre {
        assertEquals("pref", assertIs<Credential.Ok>(GraphCredentials.resolve("pref", "comp"), promesa(9)).key, promesa(9))
        assertEquals("comp", assertIs<Credential.Ok>(GraphCredentials.resolve("  ", "comp"), promesa(9)).key, promesa(9))
        assertEquals("pref", assertIs<Credential.Ok>(GraphCredentials.resolve("pref", null), promesa(9)).key, promesa(9))
        val falta = assertIs<Credential.Falta>(GraphCredentials.resolve(null, ""), promesa(9))
        assertEquals(GraphCredentials.FALTA, falta.message, promesa(9))
        assertFalse('\n' in falta.message, promesa(9))

        val t = TransporteGuionado(ok("""{"session":"s1"}"""))
        val b = cerebro(t, key = " "); b.begin("x")
        val e = assertFailsWith<IllegalStateException>(promesa(9)) { b.next(pantalla, emptyList()) }
        assertEquals(GraphCredentials.FALTA, e.message, promesa(9))
        assertTrue(t.requests.isEmpty(), promesa(9))
    }

    @Test
    fun promesa10() {
        val s = AndroidSurface.from("com.miui.calculator · Calculadora")
        assertEquals("android://com.miui.calculator", s.origin, promesa(10))
        assertEquals("/calculadora", s.pathname, promesa(10))
        assertEquals("android://com.miui.calculator/calculadora", s.id, promesa(10))

        val sinTitulo = AndroidSurface.from("com.android.settings")
        assertEquals("android://com.android.settings", sinTitulo.origin, promesa(10))
        assertEquals("/", sinTitulo.pathname, promesa(10))
        assertEquals("android://com.android.settings/", sinTitulo.id, promesa(10))

        assertEquals("/ajustes-de-red", AndroidSurface.from("com.android.settings · Ajustes de red").pathname, promesa(10))
    }

    @Test
    fun promesa11() = corre {
        val session = """{"hilo":"x \"q\" ñ 日本","n":1,"t":"a\\b"}"""
        val t = TransporteGuionado(respuesta(TurnResponse(session = session)), fin)
        val b = cerebro(t); b.begin("x")
        b.next(pantalla, emptyList())
        assertEquals(session, b.interactionId, promesa(11))
        b.next(pantalla, emptyList())
        assertEquals(session, t.requests[1].json.texto("session"), promesa(11))
    }

    /* ---------- Superficie falsa para correr el motor real ---------- */

    object TelefonoFalso : Phone {
        override suspend fun state(withScreenshot: Boolean) = ScreenState("com.x · X", "", 1, 1)
        override suspend fun tap(x: Int, y: Int) = true
        override suspend fun type(x: Int, y: Int, text: String) = true
        override suspend fun openApp(query: String) = true
        override suspend fun scroll(down: Boolean) = true
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) = true
        override suspend fun pressKey(key: String) = true
    }

    object GestosFalsos : Gestures {
        override suspend fun home() = true
        override suspend fun appDrawer() = true
        override suspend fun notifications() = true
        override suspend fun panHome(right: Boolean) = true
        override suspend fun scrollMenu(down: Boolean) = true
    }

    object SistemaFalso : SystemApi {
        override suspend fun openApp(name: String) = true
        override suspend fun setAlarm(hour: Int, minute: Int, message: String) = true
        override suspend fun setTimer(seconds: Int, message: String) = true
        override suspend fun showAlarms() = true
        override suspend fun createEvent(title: String, startIso: String, location: String) = true
        override suspend fun dial(number: String) = true
        override suspend fun call(number: String) = true
        override suspend fun sendSms(number: String, message: String) = true
        override suspend fun sendEmail(to: String, subject: String, body: String) = true
        override suspend fun webSearch(query: String) = true
        override suspend fun openUrl(url: String) = true
        override suspend fun maps(query: String) = true
        override suspend fun directions(destination: String) = true
        override suspend fun openCamera() = true
        override suspend fun openSettings(section: String) = true
        override suspend fun shareText(text: String) = true
        override suspend fun setClipboard(text: String) = true
        override suspend fun setVolume(stream: String, percent: Int) = true
        override suspend fun adjustVolume(stream: String, direction: String) = true
    }
}
