package graph.core.contrato

import graph.core.voz.Argumento
import graph.core.voz.CompuertaDeEco
import graph.core.voz.DetectorDeInterrupcion
import graph.core.voz.Hecho
import graph.core.voz.Llamada
import graph.core.voz.ModoDeCaptura
import graph.core.voz.ProtocoloGptLive
import graph.core.voz.Resultado
import graph.core.voz.TurnosSinMarca
import graph.core.voz.Utensilio
import graph.core.voz.causaFatal
import graph.core.voz.esSilencio
import graph.core.voz.pico
import graph.core.voz.rms
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CONTRATO 002 — LA VOZ ES GPT-LIVE (docs/specs/002-la-voz-es-gpt-live.md).
 *
 * Cada `promesaNNN` es una fila de la tabla de la spec, con el enunciado literal en [PROMESAS]. Se
 * escribieron ANTES que el código que juzgan: nacieron rojas. Ninguna toca red, micrófono, altavoz
 * ni Android: los mensajes son los que capturó U-Windows-App contra el servidor, el PCM se arma byte
 * a byte y el reloj es una variable. Lo emitido se juzga PARSEADO, nunca como texto crudo.
 */
@OptIn(ExperimentalEncodingApi::class)
class Contrato002VozGptLive {

    companion object {
        val PROMESAS = mapOf(
            201 to "La apertura es un solo `session.start` a `wss://api.openai.com/v1/live/sessions` con la clave en la cabecera y no en la URL; modelo gpt-live-1, voz marin, audio PCM a 24 kHz, y las herramientas viajan solo dentro de la delegación a gpt-5.6-luna, con las instrucciones del delegado byte a byte.",
            202 to "Escribir manda el mensaje del usuario y pide respuesta; entregar resultados es un mensaje por llamada y no pide respuesta, y pedirla es un mensaje aparte.",
            203 to "De las tres copias de una llamada solo cuenta `response.output_item.done`; sus argumentos llegan como mapa de texto y un valor no texto viaja como su JSON crudo; un JSON ilegible no revienta: en los argumentos da un mapa vacío y en el mensaje entero, ningún hecho.",
            204 to "El audio de salida vacío o hecho de ceros no suena; una pausa de pico 1 y una muestra con solo el byte alto sí suenan con el PCM exacto; el micrófono viaja en `session.input_audio.append` con su PCM exacto en base64.",
            205 to "Las transcripciones se traducen a lo que dijo el usuario y a lo que dijo Ü; `error` da Falla con su code literal (vacío si no trae), `session.closed` da Falla con su motivo y code vacío; ningún mensaje de GPT-Live produce CierraElTurno ni HablaronEncima; solo `session.started` es Abierta.",
            206 to "`session.usage.updated` da la Duración acumulada solo si trae segundos numéricos.",
            207 to "Un resultado de herramienta nunca pasa de 32 768 bytes serializados: si no cabe se recorta sin partir caracteres y dice cuánto se recortó de cuánto; si cabe, viaja entero; y GPT-Live no se declara capaz de mirar, porque una captura no cabe.",
            208 to "Cambiar de modo no reabre la sesión: manda `session.update` con la delegación entera y detrás `session.instructions.append` con el prefijo literal de cambio de modo y las reglas nuevas, o con el de vuelta y la persona de la voz cuando se regresa al modo de siempre.",
            209 to "Dictar es `session.commentary.append` con delegation_id nulo y el prefijo literal delante del texto; no abre sesión nueva ni pide `response.create`.",
            210 to "El primer trozo del usuario abre turno y el segundo no; el turno se cierra una sola vez a los 2000 ms exactos del último trozo, no a los 1999, y el audio en ceros no retrasa el cierre.",
            211 to "No se cierra el turno con llamadas en curso; la devolución de la última vuelve a contar el silencio desde ese momento; y una llamada devuelta antes de oírse no queda en curso.",
            212 to "Una pausa sin respuesta de Ü sigue siendo la misma petición; cerrar sin que Ü contestara no abre una petición nueva.",
            213 to "El audio de Ü con pico por encima de 1000 sostiene el turno abierto y el de pico 1000 o menos no; sonido sin nada dicho no abre un turno que cerrar.",
            214 to "La compuerta nace abierta; mientras Ü suena el micrófono sale como ceros del mismo tamaño, la gracia aguanta 300 ms tras vaciarse la cola y luego el trozo pasa idéntico; abrir la reabre sin esperar, y los ms tragados se cuentan.",
            215 to "Por defecto el micrófono viaja siempre sin compuerta; con AEC no actúa; forzarla la activa siempre.",
            216 to "La voz sostenida sobre la línea base dispara la interrupción; un golpe corto y el eco fuerte no disparan; tras disparar no vuelve a disparar hasta que Ü suene otra vez.",
            217 to "Sin crédito, clave inválida (incluido HTTP 401) o modelo inexistente son fatales y se dicen con su causa; cualquier otro código, prosa o vacío se puede reintentar.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"
    }

    /* ---------- El mapa a mano: JSON parseado, PCM byte a byte, reloj de mentira ---------- */

    private val p = ProtocoloGptLive()

    private fun json(mensaje: String): JsonObject = Json.parseToJsonElement(mensaje).jsonObject

    private fun JsonElement?.en(vararg camino: String): JsonElement? {
        var e = this
        for (paso in camino) e = (e as? JsonObject)?.get(paso) ?: return null
        return e
    }

    /** El campo si es texto; null si falta o es de otra forma. */
    private fun JsonElement?.texto(vararg camino: String): String? =
        (en(*camino) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bytes(s: String) = s.encodeToByteArray().size

    private fun delta(pcm: ByteArray) = """{"type":"session.output_audio.delta","delta":"${Base64.encode(pcm)}"}"""

    /** Muestras PCM16LE. */
    private fun pcm(vararg muestras: Int) = ByteArray(muestras.size * 2).also {
        for ((i, v) in muestras.withIndex()) { it[2 * i] = v.toByte(); it[2 * i + 1] = (v shr 8).toByte() }
    }

    /** 20 ms con el pico en una muestra NEGATIVA (así se juzga el valor absoluto) y ruido por debajo. */
    private fun pcmConPico(pico: Int) = IntArray(240).also { it[7] = -pico; it[100] = pico / 2 }.let { pcm(*it) }

    private fun seno(pico: Int) = IntArray(2400) { (pico * sin(2 * PI * 220 * it / 24000.0)).toInt() }.let { pcm(*it) }

    /** 100 ms a 24 kHz sin ninguna muestra nula: si una sola sobreviviera a la compuerta, se ve. */
    private fun vozDeLaSala() = ByteArray(4800) { (it % 251 + 1).toByte() }

    /** Unas instrucciones del tamaño de las de Ü y con todo lo que JSON escapa, terminadas en la regla que se pierde si alguien las corta. */
    private fun instruccionesComoLasDeU(primeraLinea: String) = buildString {
        append(primeraLinea).append('\n')
        var i = 1
        while (length < 24_000) {
            append("Regla ${i.toString().padStart(4, '0')}: «no anuncies», pulsa \"NV44\" en SAP\\GUI, ñandú, Ü y 😀;\ttermina.\n")
            i++
        }
        append("ÚLTIMA REGLA: la que se pierde si alguien las recorta.")
    }

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa201() {
        val clave = "sk-clave-de-prueba"
        assertEquals("wss://api.openai.com/v1/live/sessions", p.url, promesa(201))
        assertFalse(clave in p.url, promesa(201))
        assertEquals(mapOf("Authorization" to "Bearer $clave"), p.cabeceras(clave), promesa(201))
        assertEquals(24_000, p.ritmo, promesa(201))

        val completas = instruccionesComoLasDeU("ERES Ü Y ESTAS SON TUS INSTRUCCIONES COMPLETAS")
        val persona = "Eres Ü. Hablas corto y delegas todo lo que sea mirar u operar."
        val utensilios = listOf(Utensilio("map_look", "Mira la pantalla", listOf(Argumento("que", "qué mirar"))))
        val apertura = p.apertura(persona, completas, utensilios)
        assertFalse(clave in apertura, promesa(201))

        val m = json(apertura)
        assertEquals(setOf("type", "session"), m.keys, promesa(201))
        assertEquals("session.start", m.texto("type"), promesa(201))
        // La sesión de la VOZ no lleva herramientas: el servidor no las acepta ahí.
        assertEquals(setOf("model", "instructions", "audio", "delegation"), m["session"]?.jsonObject?.keys, promesa(201))
        assertEquals("gpt-live-1", m.texto("session", "model"), promesa(201))
        assertEquals(persona, m.texto("session", "instructions"), promesa(201))
        assertEquals("audio/pcm", m.texto("session", "audio", "format", "type"), promesa(201))
        assertEquals(24_000, (m.en("session", "audio", "format", "rate") as? JsonPrimitive)?.intOrNull, promesa(201))
        assertEquals("marin", m.texto("session", "audio", "output", "voice"), promesa(201))

        assertEquals("responses", m.texto("session", "delegation", "type"), promesa(201))
        assertEquals("gpt-5.6-luna", m.texto("session", "delegation", "responses", "model"), promesa(201))
        assertEquals("auto", m.texto("session", "delegation", "responses", "tool_choice"), promesa(201))
        val tools = assertIs<JsonArray>(m.en("session", "delegation", "responses", "tools"), promesa(201))
        assertEquals(1, tools.size, promesa(201))
        val t = tools[0]
        assertEquals("function", t.texto("type"), promesa(201))
        assertEquals("map_look", t.texto("name"), promesa(201))
        assertEquals("Mira la pantalla", t.texto("description"), promesa(201))
        assertEquals("object", t.texto("parameters", "type"), promesa(201))
        assertEquals("string", t.texto("parameters", "properties", "que", "type"), promesa(201))
        assertEquals("qué mirar", t.texto("parameters", "properties", "que", "description"), promesa(201))
        assertEquals(JsonArray(emptyList()), t.en("parameters", "required"), promesa(201))

        val alDelegado = m.texto("session", "delegation", "responses", "instructions")
        assertContentEquals(completas.encodeToByteArray(), alDelegado?.encodeToByteArray(),
            promesa(201) + " · instrucciones del delegado: ${bytes(completas)} B mandados, ${alDelegado?.let(::bytes)} B llegan")

        val terra = ProtocoloGptLive(delegado = "gpt-5.6-terra")
        assertEquals("gpt-5.6-terra", json(terra.apertura(persona, completas, utensilios)).texto("session", "delegation", "responses", "model"), promesa(201))
    }

    @Test
    fun promesa202() {
        val escrito = p.texto("abre la admisión")
        assertEquals(2, escrito.size, promesa(202))
        val item = json(escrito[0])
        assertEquals("response.item.create", item.texto("type"), promesa(202))
        assertEquals("message", item.texto("item", "type"), promesa(202))
        assertEquals("user", item.texto("item", "role"), promesa(202))
        val contenido = assertIs<JsonArray>(item.en("item", "content"), promesa(202))
        assertEquals(1, contenido.size, promesa(202))
        assertEquals("input_text", contenido[0].texto("type"), promesa(202))
        assertEquals("abre la admisión", contenido[0].texto("text"), promesa(202))
        assertEquals("response.create", json(escrito[1]).texto("type"), promesa(202))

        val resultados = p.resultados(listOf(Resultado("call_1", "SAP Easy Access"), Resultado("call_2", "NWP1")))
        assertEquals(2, resultados.size, promesa(202))
        val leidos = resultados.map(::json)
        for ((i, esperado) in listOf("call_1" to "SAP Easy Access", "call_2" to "NWP1").withIndex()) {
            assertEquals("response.item.create", leidos[i].texto("type"), promesa(202))
            assertEquals("function_call_output", leidos[i].texto("item", "type"), promesa(202))
            assertEquals(esperado.first, leidos[i].texto("item", "call_id"), promesa(202))
            assertEquals(esperado.second, leidos[i].texto("item", "output"), promesa(202))
        }
        assertTrue(leidos.none { it.texto("type") == "response.create" }, promesa(202))

        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("response.create"))), json(p.pedirRespuesta()), promesa(202))
    }

    @Test
    fun promesa203() {
        // Las tres copias de UNA llamada, capturadas por U el 2026-09-12: added a 1551 ms, arguments.done a 1788, output_item.done a 1822.
        val tresCopias = listOf(
            """{"event_id":"event_ENQCU7SRZjenI4jZV3IPk","type":"response.event","delegation_id":"item_ENQCUyla2TB9mFPFUmDHi","event":{"type":"response.output_item.added","item":{"id":"fc_0135f681d41dbe77006aa5cd96d41c87d183fffa8e5ea4ae5b","type":"function_call","status":"in_progress","arguments":"","call_id":"call_ydaLTWADFkH6AtEXUxsfdltF","name":"map_look"},"output_index":0,"sequence_number":2}}""",
            """{"event_id":"event_ENQCVuB7VByR5RTl3N7bO","type":"response.event","delegation_id":"item_ENQCUyla2TB9mFPFUmDHi","event":{"type":"response.function_call_arguments.done","arguments":"{\"que\":\"Mira la pantalla completa y describe brevemente qué aparece, especialmente cualquier texto, botón o elemento relevante para la solicitud del usuario.\"}","item_id":"fc_0135f681d41dbe77006aa5cd96d41c87d183fffa8e5ea4ae5b","output_index":0,"sequence_number":33}}""",
            """{"event_id":"event_ENQCVNq9s9tBGNWcT02bm","type":"response.event","delegation_id":"item_ENQCUyla2TB9mFPFUmDHi","event":{"type":"response.output_item.done","item":{"id":"fc_0135f681d41dbe77006aa5cd96d41c87d183fffa8e5ea4ae5b","type":"function_call","status":"completed","arguments":"{\"que\":\"Mira la pantalla completa y describe brevemente qué aparece, especialmente cualquier texto, botón o elemento relevante para la solicitud del usuario.\"}","call_id":"call_ydaLTWADFkH6AtEXUxsfdltF","name":"map_look"},"output_index":0,"sequence_number":34}}""",
        )
        assertTrue(p.leer(tresCopias[0]).none { it is Hecho.Pide }, promesa(203) + " · output_item.added no es la llamada")
        assertTrue(p.leer(tresCopias[1]).none { it is Hecho.Pide }, promesa(203) + " · arguments.done no es otra llamada")
        val llamadas = tresCopias.flatMap { p.leer(it) }.filterIsInstance<Hecho.Pide>().flatMap { it.llamadas }
        assertEquals(
            listOf(Llamada("call_ydaLTWADFkH6AtEXUxsfdltF", "map_look", mapOf("que" to "Mira la pantalla completa y describe brevemente qué aparece, especialmente cualquier texto, botón o elemento relevante para la solicitud del usuario."))),
            llamadas, promesa(203),
        )

        fun llamadaCon(argumentos: String) =
            """{"type":"response.event","event":{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_x","name":"map_set","arguments":${JsonPrimitive(argumentos)}}}}"""
        fun argsDe(mensaje: String): Map<String, String>? =
            p.leer(mensaje).filterIsInstance<Hecho.Pide>().singleOrNull()?.llamadas?.singleOrNull()?.args

        assertEquals(
            mapOf("que" to "x", "n" to "7", "ok" to "true", "filtro" to """{"a":[1,2]}""", "nada" to "null", "lista" to """["a"]"""),
            argsDe(llamadaCon("""{"que":"x","n":7,"ok":true,"filtro":{"a":[1,2]},"nada":null,"lista":["a"]}""")),
            promesa(203),
        )
        for (ilegibles in listOf("{no es json", "[1,2]", "", "\"texto\"")) {
            assertEquals(emptyMap<String, String>(),argsDe(llamadaCon(ilegibles)), promesa(203) + " · argumentos «$ilegibles»")
        }
        assertEquals(emptyMap<String, String>(),argsDe("""{"type":"response.event","event":{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_x","name":"map_set"}}}"""), promesa(203))

        val mensajeDelDelegado = """{"type":"response.event","event":{"type":"response.output_item.done","item":{"id":"msg_1","type":"message","status":"completed","content":[]}}}"""
        assertTrue(p.leer(mensajeDelDelegado).isEmpty(), promesa(203))

        for (roto in listOf("no es json", "", "{", "[]", "null", """{"type":7}""", """{"sin":"type"}""",
            """{"type":"session.output_audio.delta","delta":"%%%no-es-base64"}""", """{"type":"response.event","event":"texto"}""")) {
            assertEquals(emptyList<Hecho>(),p.leer(roto), promesa(203) + " · mensaje «$roto»")
        }
    }

    @Test
    fun promesa204() {
        assertTrue(p.leer("""{"type":"session.output_audio.delta","delta":""}""").isEmpty(), promesa(204))
        assertTrue(p.leer(delta(ByteArray(4800))).isEmpty(), promesa(204) + " · 100 ms de ceros exactos no suenan")

        fun suenaExacto(pcm: ByteArray, que: String) {
            val h = p.leer(delta(pcm))
            assertEquals(1, h.size, promesa(204) + " · $que")
            assertContentEquals(pcm, assertIs<Hecho.Suena>(h[0], promesa(204)).pcm, promesa(204) + " · $que")
        }
        val pausa = ByteArray(4800).also { it[2400] = 0x01; it[3600] = 0xFF.toByte(); it[3601] = 0xFF.toByte() }
        suenaExacto(pausa, "pausa de pico 1 (+1 y −1)")
        suenaExacto(ByteArray(4800).also { it[1001] = 0x01 }, "muestra 256: solo el byte alto")
        suenaExacto(pcm(0, 0, -32768, 0), "muestra −32768")
        suenaExacto(seno(7000), "voz de pico 7000")

        assertTrue(esSilencio(ByteArray(0)), promesa(204))
        assertTrue(esSilencio(byteArrayOf(0, 0, 0)), promesa(204) + " · tamaño impar con el byte suelto a cero")
        assertFalse(esSilencio(byteArrayOf(0, 0, 1)), promesa(204) + " · tamaño impar con el byte suelto distinto de cero")

        val mic = vozDeLaSala()
        val audio = json(p.audio(mic))
        assertEquals(setOf("type", "audio"), audio.keys, promesa(204))
        assertEquals("session.input_audio.append", audio.texto("type"), promesa(204))
        assertContentEquals(mic, Base64.decode(audio.texto("audio")!!), promesa(204))
    }

    @Test
    fun promesa205() {
        val dice = p.leer("""{"type":"session.output_transcript.delta","delta":" Veo la pantalla principal"}""")
        assertEquals(listOf<Hecho>(Hecho.DiceU(" Veo la pantalla principal")), dice, promesa(205))
        val oye = p.leer("""{"type":"session.input_transcript.delta","delta":"mira la pantalla"}""")
        assertEquals(listOf<Hecho>(Hecho.DiceElUsuario("mira la pantalla")), oye, promesa(205))
        assertTrue(p.leer("""{"type":"session.output_transcript.delta","delta":""}""").isEmpty(), promesa(205))
        assertTrue(p.leer("""{"type":"session.input_transcript.delta","delta":""}""").isEmpty(), promesa(205))

        val sinCredito = p.leer("""{"type":"error","event_id":"event_7f0763e4-314d-4930-9bfa-eb831d673918","error":{"type":"invalid_request_error","code":"credit_balance_exhausted","message":"You have no credits remaining. Add credits to continue using the API at https://platform.openai.com/settings/organization/billing/."}}""")
        assertEquals(listOf<Hecho>(Hecho.Falla("You have no credits remaining. Add credits to continue using the API at https://platform.openai.com/settings/organization/billing/.", "credit_balance_exhausted")), sinCredito, promesa(205))
        val sinModelo = p.leer("""{"type":"error","event_id":"event_d7252ece-60b5-4b34-88a4-30b46a4124f4","error":{"type":"invalid_request_error","code":"invalid_model","message":"Model \"gpt-live-inexistente-9\" is not supported in realtime mode."}}""")
        assertEquals(listOf<Hecho>(Hecho.Falla("Model \"gpt-live-inexistente-9\" is not supported in realtime mode.", "invalid_model")), sinModelo, promesa(205))
        // Sin code no hay código: ni el type, que comparten todos los errores, ni el message.
        val sinCode = p.leer("""{"type":"error","event_id":"event_sin_code","error":{"type":"invalid_request_error","message":"Algo que el servidor no clasificó."}}""")
        assertEquals(listOf<Hecho>(Hecho.Falla("Algo que el servidor no clasificó.", "")), sinCode, promesa(205))
        val codeNoTexto = p.leer("""{"type":"error","error":{"message":"raro","code":401}}""")
        assertEquals(listOf<Hecho>(Hecho.Falla("raro", "")), codeNoTexto, promesa(205))
        val sinMessage = p.leer("""{"type":"error","error":{"type":"invalid_request_error","code":"x_y"}}""")
        val falla = assertIs<Hecho.Falla>(sinMessage.single(), promesa(205))
        assertEquals("x_y", falla.codigo, promesa(205))
        assertEquals(json("""{"type":"invalid_request_error","code":"x_y"}"""), Json.parseToJsonElement(falla.que), promesa(205) + " · sin message, el error crudo")
        assertEquals(listOf<Hecho>(Hecho.Falla("error sin detalle", "")), p.leer("""{"type":"error","error":"boom"}"""), promesa(205))

        val cerrada = p.leer("""{"event_id":"event_ENOyNXoVEuKQV2BXwf0YH","type":"session.closed","reason":"close_requested","usage":{"seconds":13.0},"client_event_id":"sonda_fin"}""")
        assertEquals(listOf<Hecho>(Hecho.Falla("sesión cerrada: close_requested", "")), cerrada, promesa(205))
        assertEquals(listOf<Hecho>(Hecho.Falla("sesión cerrada: sin motivo", "")), p.leer("""{"type":"session.closed"}"""), promesa(205))

        val abre = p.leer("""{"type":"session.started","session":{"id":"live_u2_ENOy6GhblDeLrMlDOGSX1","model":"gpt-live-1","status":"active","input":[]}}""")
        assertEquals(listOf<Hecho>(Hecho.Abierta), abre, promesa(205))

        val otros = listOf(
            """{"event_id":"event_ENQAm4enOKNwSSblCsgzt","type":"session.updated","session":{"id":"live_u2_ENQAkzfUN8f6pBngGHwcm","expires_at":1789258059,"model":"gpt-live-1","status":"active"}}""",
            """{"type":"session.delegation.created","offset_ms":2600,"delegation":{"id":"item_ENOyA2AApz1ePY8UFCDIw","type":"delegation","response_id":"resp_0b0f65fd","target":"responses"},"client_event_id":"sonda_pide"}""",
            """{"type":"response.event","delegation_id":"item_ENOyA2AApz1ePY8UFCDIw","event":{"type":"response.completed"}}""",
            """{"type":"session.usage.updated","usage":{"seconds":12.0},"context_window":{"usage_ratio":0.01003125}}""",
            """{"type":"error","event_id":"event_ENQ9zsNQ1Zl59krrM4MFC","error":{"type":"invalid_request_error","code":"response_input_buffer_full","message":"Backend response input history is limited to 128 items and 32768 UTF-8 bytes per session.","param":"item"}}""",
            delta(seno(7000)),
            """{"type":"response.event","event":{"type":"response.output_item.done","item":{"type":"function_call","call_id":"c","name":"n","arguments":"{}"}}}""",
        )
        val sinAbrir = otros.flatMap { p.leer(it) } + dice + oye + sinCredito + sinModelo + sinCode + cerrada
        assertTrue(sinAbrir.none { it is Hecho.Abierta }, promesa(205) + " · solo session.started abre")
        val todos = sinAbrir + abre
        assertTrue(todos.none { it is Hecho.CierraElTurno || it is Hecho.HablaronEncima }, promesa(205))
        assertFalse(p.marcaLosTurnos, promesa(205))
        assertTrue(p.confirmaQueAbrio, promesa(205))
    }

    @Test
    fun promesa206() {
        fun leidos(mensaje: String) = p.leer(mensaje).filterIsInstance<Hecho.Duracion>().map { it.segundos }
        assertEquals(listOf(12.0), leidos("""{"type":"session.usage.updated","usage":{"seconds":12.0},"context_window":{"usage_ratio":0.0102343750},"event_id":"event_ENOzZAVHSZZ5pO7sNDsal"}"""), promesa(206))
        assertEquals(listOf(25.0), leidos("""{"type":"session.usage.updated","usage":{"seconds":25.0},"context_window":{"usage_ratio":0.0153984375},"event_id":"event_ENOzotrjrxl5LZz8nedUQ"}"""), promesa(206) + " · el acumulado, no la diferencia")
        assertEquals(listOf(7.0), leidos("""{"type":"session.usage.updated","usage":{"seconds":7}}"""), promesa(206))
        for (sinSegundos in listOf(
            """{"type":"session.usage.updated","context_window":{"usage_ratio":0.01}}""",
            """{"type":"session.usage.updated","usage":{"seconds":"12"}}""",
            """{"type":"session.usage.updated","usage":{"seconds":true}}""",
            """{"type":"session.usage.updated","usage":{"seconds":null}}""",
            """{"type":"session.usage.updated","usage":{}}""",
            """{"type":"session.usage.updated","usage":12}""",
        )) {
            assertEquals(emptyList<Hecho>(),p.leer(sinSegundos), promesa(206) + " · «$sinSegundos»")
        }
        assertTrue(leidos("""{"type":"session.closed","reason":"close_requested","usage":{"seconds":13.0}}""").isEmpty(), promesa(206))
    }

    @Test
    fun promesa207() {
        val tope = 32_768
        val marca = "…[recortado: "
        fun unoSolo(id: String, texto: String): String {
            val r = p.resultados(listOf(Resultado(id, texto)))
            assertEquals(1, r.size, promesa(207))
            return r.single()
        }
        fun salida(mensaje: String) = json(mensaje).texto("item", "output")!!
        fun partes(mensaje: String): Pair<String, String> {
            val s = salida(mensaje)
            val corte = s.lastIndexOf(marca)
            assertTrue(corte > 0, promesa(207) + " · sin marca de recorte: «…${s.takeLast(60)}»")
            return s.substring(0, corte) to s.substring(corte)
        }

        // 40 KB con tildes, emoji, comillas y barra invertida, como el resultado que el servidor rechazó.
        val frase = "Estás en «SAP Easy Access» — \"NWP1\" Gestión de pacientes 😀 ñandú C:\\ruta. "
        val grande = frase.repeat(40 * 1024 / bytes(frase) + 1)
        val mg = unoSolo("call_grande", grande)
        assertTrue(bytes(mg) <= tope, promesa(207) + " · mensaje de ${bytes(mg)} B")
        assertTrue(bytes(mg) > tope - 16, promesa(207) + " · aprovecha el tope: ${bytes(mg)} B")
        val g = json(mg)
        assertEquals("response.item.create", g.texto("type"), promesa(207))
        assertEquals("function_call_output", g.texto("item", "type"), promesa(207))
        assertEquals("call_grande", g.texto("item", "call_id"), promesa(207))
        val (guardado, cola) = partes(mg)
        assertTrue(grande.startsWith(guardado), promesa(207))
        assertFalse(guardado.last().isHighSurrogate(), promesa(207))
        assertEquals("…[recortado: ${bytes(guardado)} de ${bytes(grande)} bytes]", cola, promesa(207))

        // Solo emoji: cada uno son dos Char, y cortar entre los dos manda medio carácter.
        val emojis = "😀".repeat(9000)
        val me = unoSolo("call_emoji", emojis)
        assertTrue(bytes(me) <= tope, promesa(207))
        val (enteros, colaEmoji) = partes(me)
        assertTrue(enteros.isNotEmpty() && enteros.length % 2 == 0 && emojis.startsWith(enteros), promesa(207) + " · ${enteros.length} Char guardados")
        assertFalse('\uFFFD' in salida(me), promesa(207))
        assertEquals("…[recortado: ${bytes(enteros)} de ${bytes(emojis)} bytes]", colaEmoji, promesa(207))

        // Comillas: 20 KB de texto que serializados son 40 KB. Si se contara el texto, cabría.
        val comillas = "\"".repeat(20_000)
        assertTrue(bytes(comillas) < tope, promesa(207))
        val mc = unoSolo("call_comillas", comillas)
        assertTrue(bytes(mc) in (tope - 16)..tope, promesa(207) + " · mensaje de ${bytes(mc)} B")
        val (entreComillas, colaComillas) = partes(mc)
        assertTrue(comillas.startsWith(entreComillas), promesa(207))
        assertEquals("…[recortado: ${bytes(entreComillas)} de 20000 bytes]", colaComillas, promesa(207))

        // EL BORDE: un mensaje de 32 768 B exactos va entero; uno más, recortado. Con tildes, emoji y comillas dentro.
        val base = bytes(unoSolo("call_borde", ""))
        val unidad = "á😀\""
        val porUnidad = bytes(unoSolo("call_borde", unidad)) - base
        val unidades = (tope - base) / porUnidad - 1
        val justo = unidad.repeat(unidades) + "a".repeat(tope - base - unidades * porUnidad)
        val mj = unoSolo("call_borde", justo)
        assertEquals(tope, bytes(mj), promesa(207))
        assertEquals(justo, salida(mj), promesa(207) + " · 32 768 B exactos viajan enteros")
        val pasado = unoSolo("call_borde", justo + "a")
        assertTrue(bytes(pasado) <= tope, promesa(207) + " · 32 769 B se recortan: ${bytes(pasado)} B")
        val (delBorde, colaBorde) = partes(pasado)
        assertTrue(justo.startsWith(delBorde), promesa(207))
        assertEquals("…[recortado: ${bytes(delBorde)} de ${bytes(justo) + 1} bytes]", colaBorde, promesa(207))

        // Lo que cabe va entero: 17 KB con acentos y comillas, del tamaño de los que el servidor aceptó ocho veces.
        val mediano = "Estás en «SAP Easy Access»; puertas: NWP1 Gestión de pacientes, NV2000 \"Admisión\". ".repeat(150)
        assertEquals(mediano, salida(unoSolo("call_mediano", mediano)), promesa(207))

        assertFalse(p.mira, promesa(207))
    }

    @Test
    fun promesa208() {
        val persona = "Eres Ü. Hablas corto y delegas."
        val completas = "ERES Ü Y ESTAS SON TUS INSTRUCCIONES COMPLETAS DE OPERAR"
        val aprendiz = "Eres Ü, y ahora mismo te están ENSEÑANDO.\n  · Habla muy poco. Mientras te explican, asiente con algo corto: «ajá», «uhum», «entiendo».\n  · No hagas nada, no lo intentes, no digas que lo vas a hacer."
        val nuevas = listOf(Utensilio("map_where_am_i", "Dice en qué pantalla está", listOf(Argumento("detalle", "cuánto detalle"))))
        // LOS PREFIJOS MEDIDOS, escritos aquí letra por letra y no leídos de la constante: cambiarlos es volver a medir.
        val alCambiarDeModo = "CAMBIO DE MODO. Desde ahora mandan estas reglas sobre cuándo y cómo hablas, por encima de las anteriores:\n"
        val alVolver = "VUELVES A TU MODO DE SIEMPRE. Lo anterior sobre el modo especial ya no manda; desde ahora mandan estas reglas:\n"

        val aAprendiz = p.cambiarDeModo(aprendiz, nuevas, vuelve = false, instruccionesVoz = persona).map(::json)
        assertEquals(listOf("session.update", "session.instructions.append"), aAprendiz.map { it.texto("type") }, promesa(208))
        val upd = aAprendiz[0]
        assertEquals(setOf("delegation"), upd["session"]?.jsonObject?.keys, promesa(208) + " · no toca nada fuera de la delegación")
        assertEquals("responses", upd.texto("session", "delegation", "type"), promesa(208))
        assertEquals("gpt-5.6-luna", upd.texto("session", "delegation", "responses", "model"), promesa(208))
        assertEquals("auto", upd.texto("session", "delegation", "responses", "tool_choice"), promesa(208))
        assertContentEquals(aprendiz.encodeToByteArray(), upd.texto("session", "delegation", "responses", "instructions")?.encodeToByteArray(), promesa(208))
        val tools = assertIs<JsonArray>(upd.en("session", "delegation", "responses", "tools"), promesa(208))
        assertEquals(listOf("map_where_am_i"), tools.map { it.texto("name") }, promesa(208))
        assertEquals("string", tools[0].texto("parameters", "properties", "detalle", "type"), promesa(208))
        val append = aAprendiz[1]
        assertEquals(setOf("type", "delegation_id", "content"), append.keys, promesa(208))
        assertEquals(JsonNull, append["delegation_id"], promesa(208))
        assertEquals(alCambiarDeModo + aprendiz, append.texto("content"), promesa(208))

        val aNormal = p.cambiarDeModo(completas, nuevas, vuelve = true, instruccionesVoz = persona).map(::json)
        assertEquals(listOf("session.update", "session.instructions.append"), aNormal.map { it.texto("type") }, promesa(208))
        assertEquals(completas, aNormal[0].texto("session", "delegation", "responses", "instructions"), promesa(208))
        assertEquals(JsonNull, aNormal[1]["delegation_id"], promesa(208))
        val vuelta = aNormal[1].texto("content")
        assertEquals(alVolver + persona, vuelta, promesa(208) + " · de vuelta, su persona de siempre")
        assertFalse(completas in vuelta.orEmpty(), promesa(208) + " · y no las instrucciones de operar, que no caben en un append")
        assertTrue((aAprendiz + aNormal).none { it.texto("type") == "session.start" }, promesa(208))
    }

    @Test
    fun promesa209() {
        val d = json(p.dictar(" voy por el peso "))
        assertEquals(setOf("type", "delegation_id", "content"), d.keys, promesa(209))
        assertEquals("session.commentary.append", d.texto("type"), promesa(209))
        assertEquals(JsonNull, d["delegation_id"], promesa(209))
        assertEquals("Di exactamente esto, sin añadir nada ni comentarlo: voy por el peso", d.texto("content"), promesa(209))
    }

    @Test
    fun promesa210() {
        var ahora = 0L
        val turnos = TurnosSinMarca({ ahora })
        // El servidor manda audio SIN PARAR, también en silencio: se le da un trozo de ceros antes de cada pregunta.
        fun cierra(): Boolean { turnos.oye(Hecho.Suena(ByteArray(480))); return turnos.tocaCerrar() }

        ahora = 0; assertFalse(cierra(), promesa(210) + " · recién nacido no hay nada que cerrar")
        ahora = 60_000; assertFalse(cierra(), promesa(210) + " · un minuto de audio sin nada dicho no cierra nada")

        ahora = 100_000; val abre = turnos.oye(Hecho.DiceElUsuario("abre el"))
        ahora = 100_300; val abreOtra = turnos.oye(Hecho.DiceElUsuario(" bloc de notas"))
        assertTrue(abre, promesa(210)); assertFalse(abreOtra, promesa(210))
        ahora = 101_300; assertFalse(cierra(), promesa(210) + " · 1000 ms")
        ahora = 102_000; assertFalse(cierra(), promesa(210) + " · 2000 ms del PRIMER trozo, no del último")
        ahora = 102_299; assertFalse(cierra(), promesa(210) + " · 1999 ms")
        ahora = 102_300; assertTrue(cierra(), promesa(210) + " · 2000 ms exactos")
        ahora = 102_400; assertFalse(cierra(), promesa(210) + " · una sola vez")

        ahora = 102_500; assertFalse(turnos.oye(Hecho.DiceU("Listo, ")), promesa(210) + " · lo que dice Ü no abre turno del usuario")
        ahora = 104_499; assertFalse(cierra(), promesa(210))
        ahora = 104_500; assertTrue(cierra(), promesa(210) + " · lo que dice Ü también se cierra por silencio")
        ahora = 110_000; assertTrue(turnos.oye(Hecho.DiceElUsuario("mira la pantalla")), promesa(210))
    }

    @Test
    fun promesa211() {
        var ahora = 0L
        fun cierra(t: TurnosSinMarca): Boolean { t.oye(Hecho.Suena(ByteArray(480))); return t.tocaCerrar() }

        val m = TurnosSinMarca({ ahora })
        ahora = 0; m.oye(Hecho.DiceElUsuario("abre la configuración"))
        val abrir = Llamada("call_abrir", "map_open_app", emptyMap())
        ahora = 1_200; m.oye(Hecho.Pide(listOf(abrir)))
        assertEquals(1, m.llamadasEnCurso, promesa(211))
        ahora = 6_000; assertFalse(cierra(m), promesa(211) + " · 6000 ms con una llamada sin devolver")
        m.devuelta(listOf(abrir))
        assertEquals(0, m.llamadasEnCurso, promesa(211))
        ahora = 7_999; assertFalse(cierra(m), promesa(211) + " · 1999 ms tras devolverla")
        ahora = 8_000; assertTrue(cierra(m), promesa(211) + " · 2000 ms tras devolverla")
        ahora = 8_100; assertFalse(cierra(m), promesa(211))

        val m2 = TurnosSinMarca({ ahora })
        ahora = 10_000; m2.oye(Hecho.DiceElUsuario("mira y abre la configuración"))
        val mirar = Llamada("call_mirar", "map_look", emptyMap())
        val abrir2 = Llamada("call_abrir2", "map_open_app", emptyMap())
        ahora = 10_500; m2.oye(Hecho.Pide(listOf(mirar, abrir2)))
        ahora = 11_000; m2.devuelta(listOf(mirar))
        ahora = 14_000; assertFalse(cierra(m2), promesa(211) + " · una de dos devuelta")
        m2.devuelta(listOf(abrir2))
        ahora = 15_999; assertFalse(cierra(m2), promesa(211))
        ahora = 16_000; assertTrue(cierra(m2), promesa(211))

        // El hilo que ejecuta puede ganarle al que recibe.
        val m3 = TurnosSinMarca({ ahora })
        ahora = 20_000; m3.oye(Hecho.DiceElUsuario("silénciate"))
        val callar = Llamada("call_callar", "self_mute", emptyMap())
        ahora = 20_300; m3.devuelta(listOf(callar)); m3.oye(Hecho.Pide(listOf(callar)))
        assertEquals(0, m3.llamadasEnCurso, promesa(211))
        ahora = 22_300; assertTrue(cierra(m3), promesa(211) + " · y el turno no queda abierto para siempre")

        // Un call_id puede venir vacío: dos llamadas iguales en campos son dos llamadas.
        val m4 = TurnosSinMarca({ ahora })
        ahora = 30_000; m4.oye(Hecho.DiceElUsuario("dos cosas"))
        val una = Llamada("", "map_look", emptyMap())
        val otra = Llamada("", "map_look", emptyMap())
        m4.oye(Hecho.Pide(listOf(una, otra)))
        m4.devuelta(listOf(una))
        assertEquals(1, m4.llamadasEnCurso, promesa(211) + " · se cuentan por instancia, no por campos")
        ahora = 40_000; assertFalse(cierra(m4), promesa(211))
    }

    @Test
    fun promesa212() {
        var ahora = 0L
        val m = TurnosSinMarca({ ahora })
        ahora = 0; val abre = m.oye(Hecho.DiceElUsuario("abre la configuración"))
        ahora = 2_000; val cerroLaPausa = m.tocaCerrar()
        ahora = 2_200; val sigue = m.oye(Hecho.DiceElUsuario(" y entra en Bluetooth"))
        assertTrue(abre && cerroLaPausa, promesa(212))
        assertFalse(sigue, promesa(212) + " · tras la pausa, sin respuesta de Ü, es la misma petición")
        ahora = 4_200; assertTrue(m.tocaCerrar(), promesa(212))
        ahora = 4_300; m.oye(Hecho.DiceU("Listo, abierta."))
        ahora = 6_300; assertTrue(m.tocaCerrar(), promesa(212))
        ahora = 7_000; assertTrue(m.oye(Hecho.DiceElUsuario("ahora el sonido")), promesa(212) + " · cuando Ü contestó, es otra petición")

        val m2 = TurnosSinMarca({ ahora })
        ahora = 10_000; m2.oye(Hecho.DiceElUsuario("mira la pantalla"))
        ahora = 10_400; m2.oye(Hecho.DiceU("Claro."))
        ahora = 12_000; m2.oye(Hecho.DiceU(" Veo SAP."))
        ahora = 14_000; assertTrue(m2.tocaCerrar(), promesa(212))
        ahora = 14_500; assertTrue(m2.oye(Hecho.DiceElUsuario("abre NWP1")), promesa(212) + " · la respuesta de Ü cuenta dentro del mismo turno")

        val m3 = TurnosSinMarca({ ahora })
        ahora = 20_000; m3.oye(Hecho.DiceElUsuario("abre el"))
        ahora = 20_300; m3.oye(Hecho.DiceU("Claro"))
        ahora = 20_700; val encima = m3.oye(Hecho.DiceElUsuario(" bloc de notas"))
        ahora = 22_700; val cerro3 = m3.tocaCerrar()
        ahora = 23_000; val porFavor = m3.oye(Hecho.DiceElUsuario(" por favor"))
        assertFalse(encima, promesa(212)); assertTrue(cerro3, promesa(212))
        assertFalse(porFavor, promesa(212) + " · Ü no contestó a lo último del usuario")

        val m4 = TurnosSinMarca({ ahora })
        ahora = 30_000; m4.oye(Hecho.DiceU("Hola, te escucho."))
        ahora = 32_000; assertTrue(m4.tocaCerrar(), promesa(212))
        ahora = 32_500; assertTrue(m4.oye(Hecho.DiceElUsuario("abre el bloc de notas")), promesa(212) + " · el saludo no contesta a nada")
    }

    @Test
    fun promesa213() {
        var ahora = 0L
        val voz = pcmConPico(1001)
        val floja = pcmConPico(1000)
        assertEquals(1001, pico(voz), promesa(213))
        assertEquals(32_768, pico(pcm(1, -32768, 5)), promesa(213) + " · −32768 es el pico más alto, no un desbordamiento")

        val m = TurnosSinMarca({ ahora })
        ahora = 30_000; m.oye(Hecho.DiceU("Estás en SAP Easy Access."))
        for (ms in 30_100L..33_000L step 100) { ahora = ms; m.oye(Hecho.Suena(voz)) }
        assertFalse(m.tocaCerrar(), promesa(213) + " · la voz que suena sostiene el turno 3000 ms después de la transcripción")
        ahora = 34_999; assertFalse(m.tocaCerrar(), promesa(213))
        ahora = 35_000; assertTrue(m.tocaCerrar(), promesa(213) + " · el silencio cuenta desde que calla la voz")

        val m2 = TurnosSinMarca({ ahora })
        ahora = 40_000; m2.oye(Hecho.DiceU("Listo."))
        for (ms in 40_100L..41_900L step 100) { ahora = ms; m2.oye(Hecho.Suena(floja)) }
        ahora = 42_000; assertTrue(m2.tocaCerrar(), promesa(213) + " · pico 1000 no es voz y no retrasa el cierre")

        val m3 = TurnosSinMarca({ ahora })
        for (ms in 50_000L..51_000L step 100) { ahora = ms; m3.oye(Hecho.Suena(seno(7000))) }
        ahora = 54_000; assertFalse(m3.tocaCerrar(), promesa(213) + " · sonido sin nada dicho no abre un turno")
    }

    @Test
    fun promesa214() {
        val voz = vozDeLaSala()

        val c = CompuertaDeEco()
        assertSame(voz, c.filtrar(voz, sonando = false, ahora = 0), promesa(214) + " · nace abierta")
        val sonando = c.filtrar(voz, sonando = true, ahora = 1_000)
        assertNotSame(voz, sonando, promesa(214))
        assertEquals(voz.size, sonando.size, promesa(214))
        assertTrue(sonando.all { it == 0.toByte() }, promesa(214) + " · ni una muestra de la sala viaja")
        val enGracia = c.filtrar(voz, sonando = false, ahora = 1_299)
        assertTrue(enGracia.size == voz.size && enGracia.all { it == 0.toByte() }, promesa(214) + " · a 299 ms sigue tragando")
        assertSame(voz, c.filtrar(voz, sonando = false, ahora = 1_300), promesa(214) + " · a 300 ms pasa idéntico")
        assertTrue(voz.none { it == 0.toByte() }, promesa(214) + " · filtrar no toca el trozo original")

        val c2 = CompuertaDeEco()
        c2.filtrar(voz, true, 0); c2.filtrar(voz, true, 100); c2.filtrar(voz, true, 200)
        assertEquals(300L, c2.msTragados, promesa(214))
        c2.filtrar(voz, false, 5_000)
        assertEquals(300L, c2.msTragados, promesa(214) + " · lo que pasa no engorda la cuenta")

        val c3 = CompuertaDeEco()
        c3.filtrar(voz, sonando = true, ahora = 5_000)
        c3.abrir()
        assertSame(voz, c3.filtrar(voz, sonando = false, ahora = 5_050), promesa(214) + " · abrir no espera la gracia")
    }

    @Test
    fun promesa215() {
        assertFalse(ModoDeCaptura.activa(forzada = false, aec = false), promesa(215) + " · sin declarar nada, el micrófono viaja")
        assertFalse(ModoDeCaptura.activa(forzada = false, aec = false, sinCaminoDeEco = true), promesa(215))
        assertTrue(ModoDeCaptura.activa(forzada = false, aec = false, sinCaminoDeEco = false), promesa(215) + " · con parlantes declarados y sin AEC, la compuerta actúa")
        assertFalse(ModoDeCaptura.activa(forzada = false, aec = true, sinCaminoDeEco = false), promesa(215) + " · con AEC no actúa")
        for (aec in listOf(false, true)) for (sinEco in listOf(false, true)) {
            assertTrue(ModoDeCaptura.activa(forzada = true, aec = aec, sinCaminoDeEco = sinEco), promesa(215) + " · forzada con aec=$aec sinCaminoDeEco=$sinEco")
        }
    }

    @Test
    fun promesa216() {
        var t = 0L
        fun paso(): Long { t += 100; return t }
        fun disparaEn(d: DetectorDeInterrupcion, rms: Double, trozos: Int): Boolean {
            var disparo = false
            repeat(trozos) { if (!disparo) disparo = d.oye(rms, sonando = true, ahora = paso()) }
            return disparo
        }

        val d = DetectorDeInterrupcion()
        repeat(10) { assertFalse(d.oye(800.0, sonando = true, ahora = paso()), promesa(216) + " · el eco solo no dispara") }
        assertEquals(800.0, d.lineaBase, 1e-9, promesa(216))
        assertTrue(disparaEn(d, 6000.0, 5), promesa(216) + " · voz sostenida muy por encima del eco dispara")
        repeat(5) { assertFalse(d.oye(6000.0, sonando = true, ahora = paso()), promesa(216) + " · con la voz todavía encima no se ametralla") }
        repeat(10) { assertFalse(d.oye(6000.0, sonando = false, ahora = paso()), promesa(216) + " · cortada la cola no re-dispara") }
        repeat(10) { d.oye(800.0, sonando = true, ahora = paso()) }
        assertTrue(disparaEn(d, 6000.0, 5), promesa(216) + " · con Ü sonando otra vez, la guardia vuelve")

        val golpe = DetectorDeInterrupcion()
        repeat(10) { golpe.oye(800.0, true, paso()) }
        assertFalse(golpe.oye(20_000.0, true, paso()), promesa(216) + " · un golpe de un trozo")
        assertFalse(golpe.oye(800.0, true, paso()), promesa(216))
        assertFalse(golpe.oye(20_000.0, true, paso()), promesa(216) + " · ráfaga, primer trozo")
        assertFalse(golpe.oye(20_000.0, true, paso()), promesa(216) + " · ráfaga, segundo trozo bajo el sostén")
        assertFalse(golpe.oye(800.0, true, paso()), promesa(216))
        repeat(3) { golpe.oye(400.0, false, paso()) }
        repeat(20) { assertFalse(golpe.oye(2600.0, true, paso()), promesa(216) + " · la frase nueva más fuerte siembra la base con su eco") }

        // El piso es el de la app (500), no el del contrato de Windows (1500): voz 1000 sobre eco 250 dispara…
        val bajo = DetectorDeInterrupcion()
        repeat(10) { bajo.oye(250.0, true, paso()) }
        assertTrue(disparaEn(bajo, 1000.0, 5), promesa(216) + " · voz de RMS 1000 sobre eco 250")
        // …y por debajo del piso no hay voz encima, aunque triplique el eco.
        val piso = DetectorDeInterrupcion()
        repeat(10) { piso.oye(100.0, true, paso()) }
        assertFalse(disparaEn(piso, 400.0, 10), promesa(216) + " · RMS 400 no llega al piso")

        assertEquals(6000.0, rms(pcm(6000, -6000, 6000, -6000)), promesa(216))
        assertEquals(0.0, rms(ByteArray(0)), promesa(216))
        assertEquals(0.0, rms(byteArrayOf(9)), promesa(216))
    }

    @Test
    fun promesa217() {
        val causas = listOf(
            "crédito" to listOf("credit_balance_exhausted", "insufficient_quota", "insufficient_quota.credit_balance_exhausted"),
            "clave" to listOf("invalid_api_key", "invalid_request_error.invalid_api_key", "401"),
            "modelo" to listOf("invalid_model", "model_not_found", "invalid_request_error.model_not_found"),
        )
        for ((palabra, codigos) in causas) {
            val dichas = codigos.map { causaFatal(it) }
            for ((i, dicha) in dichas.withIndex()) {
                assertNotNull(dicha, promesa(217) + " · «${codigos[i]}» es fatal")
                assertTrue(palabra in dicha, promesa(217) + " · «${codigos[i]}» dice «$dicha»")
                assertTrue(causas.filter { it.first != palabra }.none { it.first in dicha }, promesa(217) + " · «$dicha» no nombra otra causa")
            }
            assertEquals(1, dichas.distinct().size, promesa(217) + " · una causa, una frase: $dichas")
        }
        assertEquals("la cuenta no tiene crédito", causaFatal("credit_balance_exhausted"), promesa(217))
        assertEquals("la clave (OPENAI_API_KEY) no vale", causaFatal("401"), promesa(217))
        assertEquals("el modelo no existe o esta cuenta no tiene acceso a él", causaFatal("invalid_model"), promesa(217))

        val reintentables = listOf(
            null, "", "   ",
            "response_input_buffer_full", "function_call_outputs_required", "unknown_parameter",
            "invalid_request_error", "invalid_request_error.unknown_parameter",
            "close_requested", "fin", "sesión cerrada: close_requested",
            "1013", "3000", "4004", "403", "4011",
            "You have no credits remaining. Add credits to continue using the API at https://platform.openai.com/settings/organization/billing/.",
            "Model \"gpt-live-inexistente-9\" is not supported in realtime mode.",
            "The server returned status code '401' when status code '101' was expected.",
        )
        for (codigo in reintentables) {
            assertNull(causaFatal(codigo), promesa(217) + " · «$codigo» se puede reintentar")
        }
    }
}
