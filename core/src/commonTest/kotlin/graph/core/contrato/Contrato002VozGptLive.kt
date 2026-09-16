package graph.core.contrato

import graph.core.domain.LearnedTool
import graph.core.domain.Mcp
import graph.core.domain.ScreenState
import graph.core.graph.AndroidSurface
import graph.core.graph.TOPE_DE_UNA_ETIQUETA
import graph.core.graph.TurnScreenState
import graph.core.graph.etiquetaDePantalla
import graph.core.graph.toTurnState
import graph.core.telemetria.PuertaDeTelemetria
import graph.core.voz.Apertura
import graph.core.voz.Argumento
import graph.core.voz.CanalDeVoz
import graph.core.voz.CatalogoDeVoz
import graph.core.voz.ColaDeReproduccion
import graph.core.voz.CompuertaDeEco
import graph.core.voz.ConversacionViva
import graph.core.voz.DetectorDeInterrupcion
import graph.core.voz.HerramientasDeVoz
import graph.core.voz.Hecho
import graph.core.voz.Llamada
import graph.core.voz.ModoDeCaptura
import graph.core.voz.OjosDeLaVoz
import graph.core.voz.PersonaDeLaVoz
import graph.core.voz.ProtocoloGptLive
import graph.core.voz.Recibido
import graph.core.voz.Reloj
import graph.core.voz.Resultado
import graph.core.voz.TelemetriaDeVoz
import graph.core.voz.TurnosSinMarca
import graph.core.voz.TOPE_DE_UN_RESULTADO
import graph.core.voz.Utensilio
import graph.core.voz.bytesUtf8
import graph.core.voz.causaFatal
import graph.core.voz.esSilencio
import graph.core.voz.pico
import graph.core.voz.rms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

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
            203 to "De las tres copias de una llamada solo cuenta `response.output_item.done`; sus argumentos llegan como mapa de texto y un valor no texto viaja como su JSON crudo; un JSON ilegible no revienta, ni uno demasiado anidado: en los argumentos da un mapa vacío y en el mensaje entero, ningún hecho.",
            204 to "El audio de salida vacío o hecho de ceros no suena; una pausa de pico 1 y una muestra con solo el byte alto sí suenan con el PCM exacto; el micrófono viaja en `session.input_audio.append` con su PCM exacto en base64.",
            205 to "Las transcripciones se traducen a lo que dijo el usuario y a lo que dijo Ü; `error` da Falla con su code literal (vacío si no trae), `session.closed` da Falla con su motivo y code vacío; ningún mensaje de GPT-Live produce CierraElTurno ni HablaronEncima; solo `session.started` es Abierta.",
            206 to "`session.usage.updated` da la Duración acumulada solo si trae segundos numéricos, finitos y no negativos.",
            207 to "Un resultado de herramienta nunca pasa de 32 768 bytes serializados: si no cabe se recorta sin partir caracteres y dice cuánto se recortó de cuánto; si cabe, viaja entero; y GPT-Live no se declara capaz de mirar, porque una captura no cabe.",
            208 to "Cambiar de modo no reabre la sesión: manda `session.update` con la delegación entera y detrás `session.instructions.append` con el prefijo literal de cambio de modo y las reglas nuevas, o con el de vuelta y la persona de la voz cuando se regresa al modo de siempre.",
            209 to "Dictar es `session.commentary.append` con delegation_id nulo y el prefijo literal delante del texto; no abre sesión nueva ni pide `response.create`, y en blanco no manda nada.",
            210 to "El primer trozo del usuario abre turno y el segundo no; el turno se cierra una sola vez a los 2000 ms exactos del último trozo, no a los 1999, y el audio en ceros no retrasa el cierre.",
            211 to "No se cierra el turno con llamadas en curso; la devolución de la última vuelve a contar el silencio desde ese momento; y una llamada devuelta antes de oírse no queda en curso.",
            212 to "Una pausa sin respuesta de Ü sigue siendo la misma petición; cerrar sin que Ü contestara no abre una petición nueva.",
            213 to "El audio de Ü con pico por encima de 1000 sostiene el turno abierto y el de pico 1000 o menos no; sonido sin nada dicho no abre un turno que cerrar.",
            214 to "La compuerta nace abierta; mientras Ü suena el micrófono sale como ceros del mismo tamaño, la gracia aguanta 300 ms tras vaciarse la cola y luego el trozo pasa idéntico; abrir la reabre sin esperar, y los ms tragados se cuentan.",
            215 to "Por defecto el micrófono viaja siempre sin compuerta; con AEC no actúa; forzarla la activa siempre.",
            216 to "La voz sostenida sobre la línea base dispara la interrupción; un golpe corto y el eco fuerte no disparan; tras disparar no vuelve a disparar hasta que Ü suene otra vez.",
            217 to "Sin crédito, clave inválida (incluido HTTP 401) o modelo inexistente son fatales y se dicen con su causa; cualquier otro código, prosa o vacío se puede reintentar.",
            218 to "Sin credencial la voz no llama a nadie y dice qué falta, y la credencial se pide de nuevo en cada apertura; un error de red al abrir se reintenta hasta 3 veces con esperas de 1 s y 2 s; un 401 del apretón de manos, una causa fatal o un fallo al abrir que no es de red no se reintentan.",
            219 to "«Sesión abierta» y el mensaje de conexión se dicen una sola vez y solo al confirmarse la sesión, nunca al conectar el socket, y «olvidé lo último» solo si antes se confirmó alguna; sin sesión confirmada el micrófono no viaja.",
            220 to "Una tanda de llamadas se contesta entera y pide respuesta una sola vez, solo cuando no queda ninguna llamada sin contestar; una llamada retirada no se ejecuta.",
            221 to "Una herramienta que revienta se contesta con su error y nunca deja el turno abierto; su resultado pasa por el recorte.",
            222 to "El turno se cierra por silencio incluso cuando llega un mensaje sin hechos; con una llamada en curso no se cierra.",
            223 to "Una causa fatal termina la voz y se dice una sola vez, llegue por error, por cierre o por el apretón de manos; un corte de red reconecta hasta 4 veces con espera creciente, y cerrar un turno devuelve el contador a cero.",
            224 to "Todas las vías de terminar la escucha (cierre, excepción, cancelación) pasan por la misma decisión; detener nunca reconecta ni anuncia un fatal, y una cancelación que llega del canal con la voz viva es un corte y reconecta.",
            225 to "Cada conexión empieza con el marcador de turnos nuevo y sin la falla de antes de abrir de la anterior; los segundos de voz se suman entre conexiones y se reportan al detener.",
            226 to "Un aviso del sistema espera a que la sesión se confirme y a que no queden llamadas pendientes, sale una sola vez con su respuesta pedida y no abre una petición del usuario; con la voz muerta se descarta, lo devuelve y lo deja en el log.",
            227 to "El audio, las transcripciones y los mensajes del delegado y los argumentos de las llamadas nunca se escriben en el log.",
            228 to "Al acercarse al tope de 128 items por sesión, contando cada llamada del delegado, se avisa una vez en el log, sin cortar la conversación.",
            229 to "Con el barge-in por energía encendido, cuando el detector dispara el altavoz se calla, la compuerta se reabre y el trozo viaja intacto, y cada frase nueva de Ü vuelve a sembrar su eco; por defecto está apagado, y sin compuerta activa el detector no actúa.",
            230 to "Cambiar de modo en plena sesión manda la delegación nueva sin reabrir, y si la sesión se corta, la reapertura ya abre en el modo vigente.",
            231 to "Detener corta cualquier espera en curso: la voz termina enseguida, no cuando vence la espera.",
            232 to "Una herramienta que se cancela por su cuenta o lanza un error grave se contesta con su motivo y la voz sigue atendiendo las siguientes; solo terminar la conversación la cancela, y entonces no se contesta.",
            233 to "Al reconectar, lo que quedó corriendo de la conexión anterior se cancela, no se contesta en la nueva y no bloquea sus herramientas.",
            234 to "Parar y las herramientas de control no esperan detrás de una herramienta que actúa en la pantalla; las que actúan en la pantalla siguen yendo de a una.",
            235 to "Retirar una llamada la contesta como no ejecutada, para que el servidor no quede esperando su salida.",
            236 to "La conversación se atiende de a una cosa por vez aunque la llamen desde varios hilos: ninguna llamada queda en curso por una carrera y ningún envío se intercala con otro.",
            237 to "Al log de la voz nunca llega el contenido de una herramienta ni de un error: solo su tipo y un motivo saneado.",
            238 to "Lo escrito con llamadas sin contestar abre su petición y espera en la misma cola que los avisos, sin prefijo, hasta salir con un solo pedido de respuesta; si la conexión muere, lo escrito en cola se descarta y los avisos pasan a la siguiente.",
            239 to "El canal real entrega un apretón de manos rechazado como rechazo con su código HTTP y su código de error de cabecera; un 401 nunca se confunde con falta de red.",
            240 to "Un servidor inalcanzable es falta de red con un motivo que no trae la clave ni la URL completa.",
            241 to "Los mensajes del servidor llegan en el orden en que se mandaron y enviar no espera a que se lea lo recibido.",
            242 to "Un cierre del servidor llega con su código y motivo; una conexión que se cae sin cerrar llega como cierre por red.",
            243 to "La clave viaja solo en la cabecera, nunca en la URL ni en el log, y cerrar el canal dos veces no rompe nada ni deja hilos vivos.",
            244 to "La cola del altavoz guarda como mucho 30 segundos y al llenarse descarta lo más viejo; suena solo si tiene bytes, nunca por volumen, y callar la vacía en el acto.",
            245 to "A la telemetría remota de la voz solo llega la medida: el largo de cada frase y el cierre del turno; ninguna frase, argumento ni texto del delegado sale del teléfono.",
            246 to "La voz en vivo solo se arranca desde el panel de desarrollador y toma su clave del build interno, nunca de la configuración remota.",
            247 to "Dónde estoy: la voz contesta con la app al frente, el tipo de pantalla y su tamaño, leídos del mismo estado que ya arma el turno de Graph y sin pedir captura; si no hay pantalla que leer lo dice y no se la inventa.",
            248 to "Qué veo: las etiquetas visibles, cuántos elementos se pueden tocar y el campo enfocado salen del `uiContext` que ya viaja a Graph; un filtro de hasta 60 caracteres contesta si algo está en pantalla sin mirar tildes ni mayúsculas, y lo que la pantalla no deja leer se dice tal cual.",
            249 to "Qué puedo hacer: el catálogo de capacidades se deriva del catálogo real de acciones, así que una acción nueva aparece sin tocar la voz; va agrupado por vía y cabe en un resultado aunque una descripción sea enorme.",
            250 to "Las tres herramientas de la voz solo leen: no reciben manos, así que ninguna toca la pantalla ni abre nada, y cualquier otra llamada del delegado se contesta «todavía no» sin ejecutar nada.",
            251 to "La sesión abre con las tres herramientas dentro de la delegación y ninguna en la voz; declararlas no gasta items, así que la conversación empieza en cero de los 128, y la apertura entera cabe de sobra en los 32 768 bytes de la sesión.",
            252 to "Leer no congela la charla: las tres son de control, así que una lectura retenida no frena a las que vienen detrás, ni el micrófono, ni el cierre del turno.",
            253 to "Del teléfono solo sale la medida de lo que se mira —cuántas etiquetas y cuántos caracteres—: ni una etiqueta, ni el filtro, ni lo que la pantalla muestra llegan al log local, y lo que llega a la telemetría remota pasa por el filtro de la voz y por la puerta sin una palabra de la pantalla.",
            254 to "Mirar no congela la conversación: la lectura de pantalla corre en su propio despachador y no en el hilo de la voz, así que aunque BLOQUEE el hilo el micrófono sigue viajando y las demás llamadas se contestan; y si tarda más que el tope se contesta que no se pudo mirar, en vez de dejar muda a la voz.",
            255 to "La sesión cuenta sus bytes además de sus items: cada item suma lo que ocupa, se avisa una vez antes de cruzar los 32 768 bytes que admite el servidor y sin cortar nada, y lo que devuelve el catálogo está acotado para que una sola respuesta no se gaste el presupuesto entero.",
            256 to "El catálogo que lee el delegado trae las herramientas aprendidas con el mismo criterio que una corrida: la voz y la anticipación se las piden al único sitio que lo decide, y ninguna de las dos escribe una lista vacía a mano.",
            257 to "Una etiqueta de la pantalla se sanea en origen —sin saltos de línea ni el separador con que se unen— y quien la lee es tolerante: una etiqueta rara no hace decir «no lo veo» de algo que está ni infla la cuenta, y el campo enfocado sale entero aunque su texto traiga comillas y paréntesis.",
            258 to "Sin servicio de accesibilidad las tres herramientas dicen la misma causa con las mismas palabras: qué puedo hacer ya no la calla devolviendo un catálogo vacío, que se lee como que Ü no sabe hacer nada.",
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

    /**
     * Unas instrucciones del tamaño de las de Ü y con todo lo que JSON escapa, terminadas en la regla que se pierde si
     * alguien las corta. Empiezan con un espacio y acaban en salto de línea: un `trim()` en el camino también se ve.
     */
    private fun instruccionesComoLasDeU(primeraLinea: String) = buildString {
        append(' ').append(primeraLinea).append('\n')
        var i = 1
        while (length < 24_000) {
            append("Regla ${i.toString().padStart(4, '0')}: «no anuncies», pulsa \"NV44\" en SAP\\GUI, ñandú, Ü y 😀;\ttermina.\n")
            i++
        }
        append("ÚLTIMA REGLA: la que se pierde si alguien las recorta.\n")
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

        // DEMASIADO ANIDADO. kotlinx 1.7.1 no tiene tope de profundidad: re-serializar ~1000 niveles o parsear miles lanza
        // StackOverflowError, que ningún catch(Exception) atrapa. Y los argumentos los escribe el delegado: basta una
        // inyección desde la pantalla. El tope es el de U (System.Text.Json, MaxDepth 64). Se juzga en pila chica: si
        // revienta, la promesa sale roja en vez de llevarse el runner.
        fun <T> sinReventar(que: String, bloque: () -> T): T =
            enPilaChica(bloque).getOrElse { fail(promesa(203) + " · $que revienta: $it", it) }
        fun anidados(n: Int) = "[".repeat(n) + "]".repeat(n)
        fun pideCon(argumentos: String): Llamada? =
            sinReventar("argumentos de ${argumentos.length} car.") { p.leer(llamadaCon(argumentos)) }
                .filterIsInstance<Hecho.Pide>().singleOrNull()?.llamadas?.singleOrNull()

        val errorHondo = """{"type":"error","error":{"type":"invalid_request_error","code":"x_y","detalle":""" + "{\"a\":".repeat(998) + "1" + "}".repeat(998) + "}}"
        // Los `{` DENTRO DE UNA LISTA también son niveles. El parser aguanta objetos hondos; lo que revienta es lo que viene
        // detrás de leerlos: el `toString` del valor de un argumento y el vuelco del mensaje al log.
        val objetosEnLista = "[" + "{\"a\":".repeat(4000) + "1" + "}".repeat(4000) + "]"
        for ((que, hondo) in listOf(
            "«[» × 4000" to "[".repeat(4000),
            "de 4000 objetos" to """{"type":"response.event","event":""" + "{\"a\":".repeat(4000) + "1" + "}".repeat(4001),
            "error sin message de 1000 niveles" to errorHondo,
            "de 4000 objetos dentro de una lista" to objetosEnLista,
        )) {
            assertEquals(emptyList<Hecho>(), sinReventar("el mensaje $que") { p.leer(hondo) }, promesa(203) + " · mensaje $que")
        }

        // Argumentos legibles de 1000 niveles (2 KB): la llamada llega, sin argumentos. Van DENTRO de un texto con las
        // comillas escapadas: medir el mensaje sin respetar textos y escapes lo daría por anidado y perdería la llamada.
        assertEquals(Llamada("call_x", "map_set", emptyMap()), pideCon("""{"a":${anidados(999)}}"""), promesa(203) + " · argumentos de 1000 niveles")
        assertEquals(Llamada("call_x", "map_set", emptyMap()), pideCon("[".repeat(4000)), promesa(203) + " · argumentos «[» × 4000")
        assertEquals(Llamada("call_x", "map_set", emptyMap()), pideCon("""{"a":$objetosEnLista}"""), promesa(203) + " · un argumento que es una lista de 4000 objetos")
        // El borde es el de U: 64 niveles se leen, 65 no.
        assertEquals(mapOf("a" to anidados(63)), pideCon("""{"a":${anidados(63)}}""")?.args, promesa(203) + " · 64 niveles se leen")
        assertEquals(emptyMap<String, String>(), pideCon("""{"a":${anidados(64)}}""")?.args, promesa(203) + " · 65 niveles ya no")


        // Un error sin message se dice con su texto CRUDO, tal cual llegó y recortado: re-serializarlo es lo que reventaba.
        val crudo = """{ "type": "invalid_request_error",  "code": "x_y" }"""
        assertEquals(listOf<Hecho>(Hecho.Falla(crudo, "x_y")), p.leer("""{"type":"error","error": $crudo }"""), promesa(203) + " · el error crudo, con sus espacios")
        val largo = """{"code":"x_y","detalle":"${"é".repeat(3000)}"}"""
        val recortado = assertIs<Hecho.Falla>(p.leer("""{"type":"error","error":$largo}""").single(), promesa(203)).que
        assertTrue(recortado.length <= 401 && recortado.endsWith("…") && largo.startsWith(recortado.dropLast(1)), promesa(203) + " · crudo recortado: ${recortado.length} car.")

        // Y en la voz: lo que no se lee tampoco se vuelca parseándolo a pelo, y la conversación sigue oyendo.
        sinReventar("la conversación") {
            corre {
                val v = Voz()
                v.guion(llega(sesionAbierta), llega("[".repeat(4000)), llega(errorHondo), llega(objetosEnLista), llega(usuario("sigo aquí")))
                v.conv.conversar()
                assertEquals(1, v.conv.peticiones, promesa(203) + " · la voz siguió oyendo: ${v.log.map { it.take(120) }}")
                assertTrue(v.log.none { "[[[[" in it || "{\"a\":{\"a\":" in it }, promesa(203) + " · el mensaje anidado no se vuelca")
            }
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
            // NaN, un número fuera de rango (infinito) o uno negativo no son una duración: NaN envenenaba el acumulado.
            """{"type":"session.usage.updated","usage":{"seconds":NaN}}""",
            """{"type":"session.usage.updated","usage":{"seconds":1e999}}""",
            """{"type":"session.usage.updated","usage":{"seconds":-5}}""",
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
        val emitidos = p.dictar(" voy por el peso ")
        assertEquals(1, emitidos.size, promesa(209))
        val d = json(emitidos.single())
        assertEquals(setOf("type", "delegation_id", "content"), d.keys, promesa(209))
        assertEquals("session.commentary.append", d.texto("type"), promesa(209))
        assertEquals(JsonNull, d["delegation_id"], promesa(209))
        assertEquals("Di exactamente esto, sin añadir nada ni comentarlo: voy por el peso", d.texto("content"), promesa(209))
        // En blanco no hay nada que decir, y el prefijo solo hace improvisar a la voz. U sale igual (ConversacionEnVivo.cs:1260).
        for (blanco in listOf("", "   ", "\n\t ")) {
            assertEquals(emptyList<String>(), p.dictar(blanco), promesa(209) + " · «$blanco»")
        }
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

        // Y ESE ES EL DEFAULT DE LA CONVERSACIÓN: construida sin decir nada de la compuerta, el micrófono viaja idéntico
        // aunque Ü esté sonando.
        corre {
            val v = Voz(aPelo = true)
            val sala = vozDeLaSala()
            v.guion(llega(sesionAbierta), hace {
                v.sonando = true
                v.conv.oirMicrofono(sala)
            })
            v.conv.conversar()
            assertEquals(listOf(sala.toList()), v.audios().map { it.toList() }, promesa(215) + " · sin compuerta por defecto, lo que suena encima no se traga")
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

        // LAS DEFENSAS, cada una con el caso que la muerde si alguien la afloja.
        // La base es una media móvil corta: 0.8 lo aprendido y 0.2 lo nuevo. Tras 800 y 1800, 1000.
        val media = DetectorDeInterrupcion()
        media.oye(800.0, true, paso()); media.oye(1800.0, true, paso())
        assertEquals(1000.0, media.lineaBase, 1e-9, promesa(216) + " · la base pesa 0.8 lo aprendido y 0.2 lo nuevo")

        // La siembra incluye su borde: el trozo a 250 ms justos del arranque todavía es eco y alimenta la base.
        val borde = DetectorDeInterrupcion()
        val arranque = paso()
        borde.oye(250.0, true, arranque)
        assertFalse(borde.oye(2600.0, true, arranque + 250), promesa(216))
        assertEquals(720.0, borde.lineaBase, 1e-9, promesa(216) + " · a 250 ms justos del arranque aún se siembra")
        assertFalse(borde.oye(2600.0, true, arranque + 500), promesa(216) + " · y el sostén no empezó en el borde")
        t = arranque + 500

        // La siembra es de CADA frase nueva: tras 300 ms de silencio, un eco 12 veces la base aprendida no es voz encima.
        val siembra = DetectorDeInterrupcion()
        repeat(10) { siembra.oye(250.0, true, paso()) }
        repeat(3) { siembra.oye(0.0, false, paso()) }
        repeat(20) { assertFalse(siembra.oye(3000.0, true, paso()), promesa(216) + " · frase nueva de Ü con eco 3000 sobre una base de 250") }

        // El sostén cuenta desde el trozo ANTERIOR al primero encima: cada trozo es lo que sonó desde el anterior. A
        // trozos de 120 ms, dos encima son 240 ms de voz y disparan.
        val sosten = DetectorDeInterrupcion()
        repeat(10) { sosten.oye(800.0, true, paso()) }
        val ultimoEco = t
        assertFalse(sosten.oye(6000.0, true, ultimoEco + 120), promesa(216) + " · 120 ms encima")
        assertTrue(sosten.oye(6000.0, true, ultimoEco + 240), promesa(216) + " · 240 ms encima, en dos trozos de 120, disparan")

        assertEquals(6000.0, rms(pcm(6000, -6000, 6000, -6000)), promesa(216))
        assertEquals(0.0, rms(ByteArray(0)), promesa(216))
        assertEquals(0.0, rms(byteArrayOf(9)), promesa(216))
    }

    @Test
    fun promesa217() {
        val causas = listOf(
            "crédito" to listOf("credit_balance_exhausted", "insufficient_quota", "insufficient_quota.credit_balance_exhausted"),
            "clave" to listOf("invalid_api_key", "invalid_request_error.invalid_api_key", "401", "fin 401"),
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

    /* ---------- La conversación viva: un canal con guion, un reloj a mano y un ejecutor que obedece ---------- */

    /** Lo que hace el servidor, en orden y a través de las conexiones: cada `recibir` consume pasos hasta devolver algo. */
    sealed interface Paso {
        class Llega(val json: String) : Paso
        class Cierra(val codigo: Int, val motivo: String, val porRed: Boolean) : Paso
        class Hace(val que: suspend () -> Unit) : Paso
        class Revienta(val motivo: String) : Paso
        data object Cuelga : Paso

        /** El adaptador cancela su `Channel`: `recibir` lanza una `CancellationException` que no es de la voz. */
        data object Cancela : Paso
    }

    private fun llega(json: String) = Paso.Llega(json)
    private fun hace(que: suspend () -> Unit) = Paso.Hace(que)
    private fun cierra(codigo: Int, motivo: String) = Paso.Cierra(codigo, motivo, porRed = false)

    /** El socket muere sin trama de cierre, como lo aborta GPT-Live tras un error. */
    private fun corte() = Paso.Cierra(1006, "", porRed = true)

    class RelojAMano : Reloj {
        var ms = 0L
        val esperas = mutableListOf<Long>()

        /** Con puerta, una espera no vence hasta abrirla: así se ve si alguien la corta antes. */
        var puerta: CompletableDeferred<Unit>? = null
        override fun ahora() = ms
        override suspend fun esperar(ms: Long) {
            esperas += ms
            puerta?.await()
            this.ms += ms
            yield()
        }
    }

    /**
     * El socket con guion. Antes de cada paso cede tres veces, para que el trabajo lanzado (las herramientas) corra
     * donde correría con un socket de verdad: mientras la recepción espera.
     */
    class CanalGuionado : CanalDeVoz {
        val aperturas = ArrayDeque<Apertura>()
        val urls = mutableListOf<String>()
        val cabeceras = mutableListOf<Map<String, String>>()
        val enviados = mutableListOf<String>()
        val cierres = mutableListOf<String>()
        val guion = ArrayDeque<Paso>()
        var alAcabarElGuion: suspend () -> Unit = {}
        private var cerrado = true

        /** Las aperturas, contadas desde 1, en las que vence un `withTimeout` del adaptador: una cancelación que no es de la voz. */
        val vencenAlAbrir = mutableSetOf<Int>()

        /** Las aperturas, contadas desde 1, en las que el adaptador lanza algo que no es la red: TLS roto, URL mala. */
        val lanzanAlAbrir = mutableMapOf<Int, Exception>()

        /** Los envíos que fallan como si el socket hubiera muerto al mandarlos: no quedan en [enviados]. */
        var fallaAlEnviar: (String) -> Boolean = { false }

        /** Los envíos en los que vence un `withTimeout` del adaptador: una cancelación que no es de la voz. No quedan en [enviados]. */
        var vencenAlEnviar: (String) -> Boolean = { false }

        override suspend fun abrir(url: String, cabeceras: Map<String, String>): Apertura {
            urls += url
            this.cabeceras += cabeceras
            if (urls.size in vencenAlAbrir) {
                cerrado = true
                withTimeout(0) { awaitCancellation() }
            }
            lanzanAlAbrir[urls.size]?.let {
                cerrado = true
                throw it
            }
            val a = aperturas.removeFirstOrNull() ?: Apertura.Ok
            cerrado = a != Apertura.Ok
            return a
        }

        override suspend fun enviar(texto: String) {
            if (vencenAlEnviar(texto)) withTimeout(0) { awaitCancellation() }
            if (fallaAlEnviar(texto)) throw IllegalStateException("el socket murió al mandar")
            enviados += texto
        }

        override suspend fun recibir(): Recibido {
            while (true) {
                repeat(3) { yield() }
                if (cerrado) return Recibido.Cierre(1000, "fin", porRed = false)
                when (val paso = guion.removeFirstOrNull()) {
                    null -> {
                        alAcabarElGuion()
                        if (!cerrado) throw AssertionError("se acabó el guion y el canal sigue abierto")
                    }
                    is Paso.Llega -> return Recibido.Mensaje(paso.json)
                    is Paso.Cierra -> { cerrado = true; return Recibido.Cierre(paso.codigo, paso.motivo, paso.porRed) }
                    is Paso.Hace -> paso.que()
                    is Paso.Revienta -> { cerrado = true; throw IllegalStateException(paso.motivo) }
                    Paso.Cuelga -> awaitCancellation()
                    Paso.Cancela -> { cerrado = true; Channel<Recibido>().apply { cancel() }.receive() }
                }
            }
        }

        override fun cerrar(motivo: String) {
            cerrado = true
            cierres += motivo
        }
    }

    private val clave = "sk-prueba"
    private val sesionAbierta = """{"type":"session.started","session":{"id":"live_u2_ENOy6GhblDeLrMlDOGSX1","model":"gpt-live-1","status":"active","input":[]}}"""
    private val sinHechos = """{"type":"session.updated","session":{"id":"live_u2_ENOy6GhblDeLrMlDOGSX1","model":"gpt-live-1","status":"active"}}"""
    private fun usuario(t: String) = """{"type":"session.input_transcript.delta","delta":${JsonPrimitive(t)}}"""
    private fun dichoPorU(t: String) = """{"type":"session.output_transcript.delta","delta":${JsonPrimitive(t)}}"""
    private fun pideCon(id: String, nombre: String, argumentos: String) =
        """{"type":"response.event","event":{"type":"response.output_item.done","item":{"type":"function_call","call_id":"$id","name":"$nombre","arguments":${JsonPrimitive(argumentos)}}}}"""
    private fun pide(id: String, nombre: String) =
        """{"type":"response.event","event":{"type":"response.output_item.done","item":{"type":"function_call","call_id":"$id","name":"$nombre","arguments":"{}"}}}"""
    private fun fallo(code: String, message: String) =
        """{"type":"error","error":{"type":"invalid_request_error","code":"$code","message":${JsonPrimitive(message)}}}"""
    private fun duracion(segundos: Double) = """{"type":"session.usage.updated","usage":{"seconds":$segundos}}"""

    /**
     * Una conversación entera sobre el canal con guion, con todo lo que toca grabado. El guion acaba deteniéndola.
     * [aPelo] la construye sin NINGÚN parámetro con default —compuerta, pantalla, barge-in—, y entonces los de aquí no
     * cuentan: lo que se juzga es el default de la clase.
     */
    private inner class Voz(
        credencial: String? = clave,
        compuertaActiva: Boolean = false,
        ejecutor: suspend (Llamada) -> String = { "hecho: ${it.nombre}" },
        actuaEnPantalla: (String) -> Boolean = { true },
        utensilios: List<Utensilio> = listOf(Utensilio("pulsar", "Pulsa algo", listOf(Argumento("que", "qué pulsar")))),
        bargeIn: Boolean? = null,
        aPelo: Boolean = false,
    ) {
        /** La que devuelve `credencial()` en este momento: se puede rotar entre conexiones. */
        var credencialVigente = credencial
        val canal = CanalGuionado()
        val reloj = RelojAMano()
        val dicho = mutableListOf<String>()
        val log = mutableListOf<String>()
        val ejecutadas = mutableListOf<String>()
        val sonado = mutableListOf<ByteArray>()
        var sonando = false
        var callado = 0
        private val instruccionesVoz = "Eres Ü. Hablas corto y delegas."
        private val instruccionesDelegado = "ERES Ü Y ESTAS SON TUS INSTRUCCIONES COMPLETAS"
        private val ejecutar: suspend (Llamada) -> String = { ejecutadas += it.id; ejecutor(it) }
        private val reproducir: (ByteArray) -> Unit = { sonado += it }
        private val callar: () -> Unit = { callado++; sonando = false }   // callar vacía la cola: deja de sonar
        private val registrar: (String, String) -> Unit = { tag, m -> log += "$tag: $m" }

        // El contrato común corre en el hilo único de `corre`: la voz se queda en él. El despachador de verdad lo juzga la 236.
        // SIN `bargeIn` LA BANDERA NO SE PASA: lo que se juzga «por defecto» es el default de la clase, no el de esta prueba.
        val conv = when {
            aPelo -> ConversacionViva(
                canal = canal, credencial = { credencialVigente }, instruccionesVoz = instruccionesVoz,
                instruccionesDelegado = instruccionesDelegado, utensilios = utensilios, ejecutar = ejecutar, reproducir = reproducir,
                callar = callar, sonando = { sonando }, dice = { dicho += it }, log = registrar, reloj = reloj,
                hilo = EmptyCoroutineContext,
            )
            bargeIn == null -> ConversacionViva(
                canal = canal, credencial = { credencialVigente }, instruccionesVoz = instruccionesVoz,
                instruccionesDelegado = instruccionesDelegado, utensilios = utensilios, ejecutar = ejecutar, reproducir = reproducir,
                callar = callar, sonando = { sonando }, dice = { dicho += it }, log = registrar, reloj = reloj,
                compuertaActiva = compuertaActiva, actuaEnPantalla = actuaEnPantalla, hilo = EmptyCoroutineContext,
            )
            else -> ConversacionViva(
                canal = canal, credencial = { credencialVigente }, instruccionesVoz = instruccionesVoz,
                instruccionesDelegado = instruccionesDelegado, utensilios = utensilios, ejecutar = ejecutar, reproducir = reproducir,
                callar = callar, sonando = { sonando }, dice = { dicho += it }, log = registrar, reloj = reloj,
                compuertaActiva = compuertaActiva, actuaEnPantalla = actuaEnPantalla, hilo = EmptyCoroutineContext,
                bargeInPorEnergia = bargeIn,
            )
        }

        init {
            canal.alAcabarElGuion = { conv.detener() }
        }

        fun guion(vararg pasos: Paso) = canal.guion.addAll(pasos)
        fun enviados(): List<JsonObject> = canal.enviados.map { json(it) }
        fun tipos(): List<String?> = enviados().map { it.texto("type") }
        fun cuenta(tipo: String) = tipos().count { it == tipo }
        fun enLog(texto: String) = log.count { texto in it }
        fun salidas() = enviados().filter { it.texto("item", "type") == "function_call_output" }
        fun textos() = enviados().filter { it.texto("item", "type") == "message" }.map { (it.en("item", "content") as JsonArray)[0].texto("text") }
        fun audios() = enviados().filter { it.texto("type") == "session.input_audio.append" }.map { Base64.decode(it.texto("audio")!!) }
    }

    @Test
    fun promesa218() = corre {
        for (sin in listOf(null, "", "   ")) {
            val v = Voz(credencial = sin)
            v.conv.conversar()
            assertTrue(v.canal.urls.isEmpty(), promesa(218) + " · credencial «$sin»: no se llama a nadie")
            assertEquals(1, v.dicho.size, promesa(218) + " · ${v.dicho}")
            assertTrue("falta" in v.dicho.single() && "clave" in v.dicho.single(), promesa(218) + " · dice qué falta: «${v.dicho.single()}»")
            assertFalse(v.conv.viva, promesa(218))
        }

        val vuelve = Voz()
        vuelve.canal.aperturas += listOf(Apertura.SinRed("sin ruta al host"), Apertura.SinRed("sin ruta al host"))
        vuelve.guion(llega(sesionAbierta))
        vuelve.conv.conversar()
        assertEquals(3, vuelve.canal.urls.size, promesa(218) + " · tres intentos")
        assertEquals(listOf(1000L, 2000L), vuelve.reloj.esperas, promesa(218))
        assertTrue(vuelve.canal.urls.all { it == "wss://api.openai.com/v1/live/sessions" }, promesa(218))
        assertTrue(vuelve.canal.cabeceras.all { it == mapOf("Authorization" to "Bearer $clave") }, promesa(218))
        assertEquals(listOf<String?>("session.start"), vuelve.tipos(), promesa(218) + " · un solo session.start, en el intento que abrió")
        assertEquals(listOf("Te escucho."), vuelve.dicho, promesa(218))

        val sinRed = Voz()
        repeat(4) { sinRed.canal.aperturas += Apertura.SinRed("sin ruta al host") }
        sinRed.conv.conversar()
        assertEquals(3, sinRed.canal.urls.size, promesa(218) + " · y no más de tres")
        assertEquals(listOf(1000L, 2000L), sinRed.reloj.esperas, promesa(218))
        assertTrue(sinRed.canal.enviados.isEmpty(), promesa(218))
        assertEquals(1, sinRed.dicho.size, promesa(218) + " · ${sinRed.dicho}")
        assertTrue("conexión" in sinRed.dicho.single(), promesa(218) + " · «${sinRed.dicho.single()}»")
        assertFalse(sinRed.conv.viva, promesa(218))

        val claveFalsa = Voz()
        claveFalsa.canal.aperturas += Apertura.Rechazo(401, "invalid_api_key")
        claveFalsa.conv.conversar()
        assertEquals(1, claveFalsa.canal.urls.size, promesa(218) + " · un 401 no se reintenta")
        assertTrue(claveFalsa.reloj.esperas.isEmpty(), promesa(218))
        assertEquals(listOf("No sigo con la voz en vivo: la clave (OPENAI_API_KEY) no vale («HTTP 401 invalid_api_key»)."), claveFalsa.dicho, promesa(218))

        val otroRechazo = Voz()
        otroRechazo.canal.aperturas += Apertura.Rechazo(503)
        otroRechazo.conv.conversar()
        assertEquals(1, otroRechazo.canal.urls.size, promesa(218) + " · un rechazo HTTP no es la red")
        assertEquals(listOf("No pude abrir la voz en vivo. El servidor dice: HTTP 503"), otroRechazo.dicho, promesa(218))

        val sinCredito = Voz()
        sinCredito.guion(llega(fallo("credit_balance_exhausted", "You have no credits remaining.")), corte())
        sinCredito.conv.conversar()
        assertEquals(1, sinCredito.canal.urls.size, promesa(218) + " · una causa fatal no se reintenta")
        assertEquals(listOf("No sigo con la voz en vivo: la cuenta no tiene crédito («You have no credits remaining.»)."), sinCredito.dicho, promesa(218))

        // LA CABECERA TAMBIÉN ES UNA PUERTA: un HTTP que por sí solo no es fatal con `invalid_api_key` en la cabecera es la clave.
        val cabeceraFatal = Voz()
        cabeceraFatal.canal.aperturas += Apertura.Rechazo(403, "invalid_api_key")
        cabeceraFatal.conv.conversar()
        assertEquals(1, cabeceraFatal.canal.urls.size, promesa(218) + " · la causa de la cabecera no se reintenta")
        assertEquals(listOf("No sigo con la voz en vivo: la clave (OPENAI_API_KEY) no vale («HTTP 403 invalid_api_key»)."), cabeceraFatal.dicho, promesa(218))

        // LA CREDENCIAL SE PIDE EN CADA APERTURA: el token efímero de mañana caduca, y una clave rotada vale desde la siguiente.
        val rotada = Voz()
        rotada.guion(
            llega(sesionAbierta), hace { rotada.credencialVigente = "sk-rotada" }, corte(),
            llega(sesionAbierta), hace { rotada.credencialVigente = "   " }, corte(),
        )
        rotada.conv.conversar()
        assertEquals(listOf<String?>("Bearer $clave", "Bearer sk-rotada"), rotada.canal.cabeceras.map { it["Authorization"] }, promesa(218) + " · la reapertura va con la credencial vigente")
        assertEquals(2, rotada.canal.urls.size, promesa(218) + " · sin credencial al reabrir no se llama a nadie")
        assertEquals("No hay voz en vivo: falta la clave de la voz (OPENAI_API_KEY).", rotada.dicho.last(), promesa(218) + " · y se dice qué falta: ${rotada.dicho}")
        assertFalse(rotada.conv.viva, promesa(218))

        // SOLO LA RED SE REINTENTA. Un TLS roto o una URL mala fallan igual tres veces: se dicen una vez, sin culpar al internet.
        val tlsRoto = Voz()
        tlsRoto.canal.lanzanAlAbrir[1] = IllegalStateException("Handshake failed")
        tlsRoto.conv.conversar()
        assertEquals(1, tlsRoto.canal.urls.size, promesa(218) + " · un fallo que no es de red no se reintenta")
        assertTrue(tlsRoto.reloj.esperas.isEmpty(), promesa(218) + " · ${tlsRoto.reloj.esperas}")
        assertEquals(listOf("No pude abrir la voz en vivo: IllegalStateException: Handshake failed."), tlsRoto.dicho, promesa(218))
        assertFalse(tlsRoto.conv.viva, promesa(218))
    }

    @Test
    fun promesa219() = corre {
        val v = Voz()
        val mic = vozDeLaSala()
        v.guion(
            hace { v.conv.oirMicrofono(mic) },
            llega(sinHechos),
            hace {
                assertTrue(v.dicho.isEmpty(), promesa(219) + " · conectar el socket no es abrir la sesión: ${v.dicho}")
                assertEquals(0, v.enLog("sesión abierta"), promesa(219) + " · ${v.log}")
                v.conv.oirMicrofono(mic)
                assertTrue(v.audios().isEmpty(), promesa(219) + " · sin sesión confirmada el micrófono no viaja")
            },
            llega(sesionAbierta),
            hace { v.conv.oirMicrofono(mic) },
            llega(sesionAbierta),
            corte(),
            hace {
                assertEquals(listOf("Te escucho."), v.dicho, promesa(219) + " · ni al reconectar el socket")
                v.conv.oirMicrofono(mic)
            },
            llega(sinHechos),
            llega(sesionAbierta),
        )
        v.conv.conversar()
        assertEquals(listOf("Te escucho.", "Se cortó un instante. Sigo, pero olvidé lo último que hablábamos."), v.dicho, promesa(219))
        assertEquals(2, v.enLog("sesión abierta"), promesa(219) + " · una por conexión confirmada: ${v.log.filter { "sesión abierta" in it }}")
        assertEquals(1, v.audios().size, promesa(219) + " · solo viaja el trozo oído con la sesión confirmada")
        assertContentEquals(mic, v.audios().single(), promesa(219))
        assertEquals(2, v.cuenta("session.start"), promesa(219))

        // SIN CONFIRMAR NO HUBO CONVERSACIÓN: si la apertura de la primera conexión no llegó a salir, la que confirma arranca.
        val sinApertura = Voz()
        var aperturasFallidas = 0
        sinApertura.canal.fallaAlEnviar = { json(it).texto("type") == "session.start" && aperturasFallidas++ == 0 }
        sinApertura.guion(llega(sesionAbierta))
        sinApertura.conv.conversar()
        assertEquals(2, sinApertura.canal.urls.size, promesa(219) + " · la apertura que no salió reconecta")
        assertEquals(listOf("Te escucho."), sinApertura.dicho, promesa(219) + " · y no se dice «olvidé lo último» de algo que nunca empezó")
    }

    @Test
    fun promesa220() = corre {
        val puertas = mapOf("call_mirar" to CompletableDeferred<String>(), "call_abrir" to CompletableDeferred(), "call_pulsar" to CompletableDeferred())
        val v = Voz(ejecutor = { puertas.getValue(it.id).await() })
        v.guion(
            llega(sesionAbierta),
            llega(usuario("mira, abre la cámara y pulsa grabar")),
            llega(pide("call_mirar", "que_veo")),
            llega(pide("call_abrir", "abrir_app")),
            llega(pide("call_pulsar", "pulsar")),
            hace { v.conv.retirar(listOf("call_pulsar")) },
            hace { puertas.getValue("call_mirar").complete("veo el inicio") },
            llega(sinHechos),
            hace {
                assertEquals(listOf<String?>("call_mirar"), v.salidas().map { it.texto("item", "call_id") }, promesa(220))
                assertEquals(0, v.cuenta("response.create"), promesa(220) + " · quedan llamadas sin contestar: no se pide respuesta")
            },
            hace { puertas.getValue("call_abrir").complete("cámara abierta") },
            llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(listOf("call_mirar", "call_abrir"), v.ejecutadas, promesa(220) + " · la retirada no se ejecuta")
        assertEquals(listOf<String?>("call_mirar", "call_abrir", "call_pulsar"), v.salidas().map { it.texto("item", "call_id") }, promesa(220) + " · la tanda se contesta entera, la retirada también (235)")
        assertEquals(listOf<String?>("veo el inicio", "cámara abierta", "retirada: no se ejecutó"), v.salidas().map { it.texto("item", "output") }, promesa(220))
        assertEquals(1, v.cuenta("response.create"), promesa(220) + " · una sola vez: ${v.tipos()}")
        assertEquals("response.create", v.tipos().last(), promesa(220) + " · detrás de todas las salidas: ${v.tipos()}")
    }

    @Test
    fun promesa221() = corre {
        val grande = "Estás en «Ajustes» — \"Cámara\" 😀 ".repeat(1500)
        val v = Voz(ejecutor = { if (it.nombre == "romper") throw IllegalStateException("no hay pantalla") else grande })
        v.guion(
            llega(sesionAbierta),
            llega(usuario("rompe algo y lee todo")),
            hace { v.reloj.ms = 1_000 }, llega(pide("call_romper", "romper")),
            hace { v.reloj.ms = 1_500 }, llega(pide("call_leer", "leer")),
            hace { v.reloj.ms = 3_499 }, llega(sinHechos),
            hace { assertEquals(0, v.enLog("usuario dijo"), promesa(221) + " · 1999 ms tras la última devolución") },
            hace { v.reloj.ms = 3_500 }, llega(sinHechos),
        )
        v.conv.conversar()
        val (romper, leer) = v.salidas()
        assertEquals("call_romper", romper.texto("item", "call_id"), promesa(221))
        assertEquals("la herramienta falló: no hay pantalla", romper.texto("item", "output"), promesa(221))
        assertEquals(1, v.enLog("usuario dijo: rompe algo y lee todo"), promesa(221) + " · el turno se cerró: ${v.log}")
        val bytesLeer = bytes(v.canal.enviados.first { "call_leer" in it })
        assertTrue(bytesLeer <= 32_768, promesa(221) + " · $bytesLeer B")
        assertTrue("…[recortado: " in leer.texto("item", "output").orEmpty(), promesa(221))

        // LA DEVOLUCIÓN VA EN FINALLY. Una herramienta que cancela su propia corrutina saca la excepción de la tanda: sin el
        // finally, la llamada quedaba en curso y el turno no se cerraba nunca.
        val seCancela = Voz(actuaEnPantalla = { false }, ejecutor = {
            if (it.nombre == "cortar") {
                currentCoroutineContext().cancel()
                awaitCancellation()
            }
            "hecho: ${it.nombre}"
        })
        seCancela.guion(
            llega(sesionAbierta),
            hace { seCancela.reloj.ms = 1_000 }, llega(usuario("corta y mira")),
            llega(pide("call_cortar", "cortar")), llega(pide("call_mirar", "que_veo")),
            llega(sinHechos),
            hace { seCancela.reloj.ms = 2_999 }, llega(sinHechos),
            hace { assertEquals(0, seCancela.enLog("usuario dijo"), promesa(221) + " · 1999 ms") },
            hace { seCancela.reloj.ms = 3_000 }, llega(sinHechos),
        )
        seCancela.conv.conversar()
        assertEquals(listOf("call_cortar", "call_mirar"), seCancela.ejecutadas, promesa(221))
        assertEquals(1, seCancela.enLog("usuario dijo: corta y mira"), promesa(221) + " · la que se canceló a sí misma no deja el turno abierto: ${seCancela.log}")
    }

    @Test
    fun promesa222() = corre {
        val puerta = CompletableDeferred<String>()
        val v = Voz(ejecutor = { puerta.await() })
        val ceros = delta(ByteArray(4800))
        v.guion(
            llega(sesionAbierta),
            hace { v.reloj.ms = 10_000 }, llega(usuario("abre la cámara")),
            hace { v.reloj.ms = 11_999 }, llega(ceros), llega(sinHechos),
            hace { assertEquals(0, v.enLog("usuario dijo"), promesa(222) + " · 1999 ms") },
            hace { v.reloj.ms = 12_000 }, llega(ceros),
            hace { assertEquals(1, v.enLog("usuario dijo: abre la cámara"), promesa(222) + " · el audio en ceros es el tic: ${v.log}") },
            hace { v.reloj.ms = 20_000 }, llega(usuario("y graba")), llega(pide("call_grabar", "pulsar")),
            hace { v.reloj.ms = 30_000 }, llega(sinHechos), llega(ceros),
            hace { assertEquals(0, v.enLog("usuario dijo: y graba"), promesa(222) + " · con una llamada en curso no se cierra") },
            hace { puerta.complete("grabando") },
            llega(sinHechos),
            hace { v.reloj.ms = 31_999 }, llega(sinHechos),
            hace { assertEquals(0, v.enLog("usuario dijo: y graba"), promesa(222)) },
            hace { v.reloj.ms = 32_000 }, llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(1, v.enLog("usuario dijo: y graba"), promesa(222) + " · ${v.log}")
    }

    @Test
    fun promesa223() = corre {
        val porError = Voz()
        porError.guion(llega(sesionAbierta), llega(fallo("insufficient_quota", "You exceeded your current quota.")), corte())
        porError.conv.conversar()
        assertEquals(listOf("Te escucho.", "No sigo con la voz en vivo: la cuenta no tiene crédito («You exceeded your current quota.»)."), porError.dicho, promesa(223))
        assertEquals(1, porError.canal.urls.size, promesa(223) + " · por error no reconecta")
        assertTrue(porError.reloj.esperas.isEmpty(), promesa(223))

        val porCierre = Voz()
        porCierre.guion(llega(sesionAbierta), cierra(1013, "insufficient_quota.credit_balance_exhausted"))
        porCierre.conv.conversar()
        assertEquals(listOf("Te escucho.", "No sigo con la voz en vivo: la cuenta no tiene crédito («insufficient_quota.credit_balance_exhausted»)."), porCierre.dicho, promesa(223))
        assertEquals(1, porCierre.canal.urls.size, promesa(223) + " · por cierre no reconecta")

        val laPrimera = Voz()
        laPrimera.guion(llega(sesionAbierta), llega(fallo("model_not_found", "The model does not exist.")), cierra(3000, "invalid_request_error.invalid_api_key"))
        laPrimera.conv.conversar()
        assertEquals(listOf("Te escucho.", "No sigo con la voz en vivo: el modelo no existe o esta cuenta no tiene acceso a él («The model does not exist.»)."), laPrimera.dicho, promesa(223) + " · la primera causa gana")

        val alVolver = Voz()
        alVolver.canal.aperturas += listOf(Apertura.Ok, Apertura.Rechazo(401))
        alVolver.guion(llega(sesionAbierta), corte())
        alVolver.conv.conversar()
        assertEquals(2, alVolver.canal.urls.size, promesa(223))
        assertEquals(listOf(300L), alVolver.reloj.esperas, promesa(223))
        assertEquals(listOf("Te escucho.", "No sigo con la voz en vivo: la clave (OPENAI_API_KEY) no vale («HTTP 401»)."), alVolver.dicho, promesa(223) + " · por el apretón de manos al reconectar")

        val cortes = Voz()
        repeat(5) { cortes.guion(llega(sesionAbierta), corte()) }
        cortes.conv.conversar()
        assertEquals(5, cortes.canal.urls.size, promesa(223) + " · la conexión y 4 reconexiones")
        assertEquals(listOf(300L, 600L, 900L, 1200L), cortes.reloj.esperas, promesa(223))
        assertEquals("Se me cortó la conexión y no consigo volver. Vuelve a darle al micrófono.", cortes.dicho.last(), promesa(223))
        assertEquals(1, cortes.dicho.count { "no consigo volver" in it }, promesa(223))
        assertTrue(cortes.dicho.none { "No sigo" in it }, promesa(223) + " · un corte no es un fatal")
        assertFalse(cortes.conv.viva, promesa(223))

        val conTurno = Voz()
        conTurno.guion(
            llega(sesionAbierta), corte(),
            llega(sesionAbierta), corte(),
            llega(sesionAbierta),
            hace { conTurno.reloj.ms = 100_000 }, llega(usuario("hola")),
            hace { conTurno.reloj.ms = 102_000 }, llega(sinHechos),
            corte(),
            llega(sesionAbierta),
        )
        conTurno.conv.conversar()
        assertEquals(1, conTurno.enLog("usuario dijo: hola"), promesa(223))
        assertEquals(listOf(300L, 600L, 300L), conTurno.reloj.esperas, promesa(223) + " · cerrar un turno devuelve el contador a cero")
    }

    @Test
    fun promesa224() = corre {
        fun Voz.decisiones() = log.filter { "fin de la escucha" in it }

        val cierre = Voz()
        cierre.guion(llega(sesionAbierta), cierra(1000, "close_requested"), llega(sesionAbierta))
        cierre.conv.conversar()
        assertEquals(2, cierre.canal.urls.size, promesa(224) + " · un cierre sin causa reconecta")
        assertEquals(2, cierre.decisiones().size, promesa(224) + " · ${cierre.decisiones()}")
        assertEquals(1, cierre.enLog("fin de la escucha por cierre"), promesa(224) + " · ${cierre.decisiones()}")

        val excepcion = Voz()
        excepcion.guion(llega(sesionAbierta), Paso.Revienta("el socket murió"), llega(sesionAbierta))
        excepcion.conv.conversar()
        assertEquals(2, excepcion.canal.urls.size, promesa(224) + " · una excepción en la recepción reconecta")
        assertEquals(2, excepcion.decisiones().size, promesa(224) + " · ${excepcion.decisiones()}")
        assertEquals(1, excepcion.enLog("fin de la escucha por corte"), promesa(224) + " · ${excepcion.decisiones()}")

        val detenida = Voz()
        detenida.guion(llega(sesionAbierta), llega(fallo("insufficient_quota", "sin cuota")), hace { detenida.conv.detener() })
        detenida.conv.conversar()
        assertEquals(1, detenida.canal.urls.size, promesa(224) + " · detener no reconecta")
        assertEquals(listOf("Te escucho."), detenida.dicho, promesa(224) + " · ni anuncia el fatal que había guardado")
        assertEquals(1, detenida.decisiones().size, promesa(224) + " · ${detenida.decisiones()}")
        assertEquals(1, detenida.enLog("fin de la escucha por cancelación"), promesa(224) + " · ${detenida.decisiones()}")
        assertFalse(detenida.conv.viva, promesa(224))

        val cancelada = Voz()
        cancelada.guion(llega(sesionAbierta), llega(fallo("insufficient_quota", "sin cuota")), Paso.Cuelga)
        coroutineScope {
            val trabajo = launch { cancelada.conv.conversar() }
            repeat(20) { yield() }
            assertTrue(cancelada.conv.viva, promesa(224))
            trabajo.cancelAndJoin()
        }
        assertEquals(1, cancelada.canal.urls.size, promesa(224) + " · cancelar no reconecta")
        assertEquals(listOf("Te escucho."), cancelada.dicho, promesa(224) + " · ni anuncia el fatal")
        assertEquals(1, cancelada.decisiones().size, promesa(224) + " · ${cancelada.decisiones()}")
        assertEquals(1, cancelada.enLog("fin de la escucha por cancelación"), promesa(224) + " · ${cancelada.decisiones()}")
        assertEquals(1, cancelada.enLog("sesión cerrada"), promesa(224))
        assertFalse(cancelada.conv.viva, promesa(224))

        // EL CANAL TAMBIÉN CANCELA. Un adaptador que cancela su Channel en recibir(), o al que le vence un withTimeout en
        // abrir(), lanza una CancellationException con la voz viva: no es la de la voz, es un corte, y reconecta.
        val delCanal = Voz()
        delCanal.canal.vencenAlAbrir += 2
        delCanal.guion(llega(sesionAbierta), Paso.Cancela, llega(sesionAbierta))
        val r = runCatching { delCanal.conv.conversar() }
        assertNull(r.exceptionOrNull(), promesa(224) + " · la cancelación del canal no sale de conversar: ${r.exceptionOrNull()}")
        assertEquals(3, delCanal.canal.urls.size, promesa(224) + " · reconecta al cancelarse recibir, y otra vez al vencer abrir")
        assertEquals(listOf(300L, 600L), delCanal.reloj.esperas, promesa(224))
        assertEquals(listOf("Te escucho.", "Se cortó un instante. Sigo, pero olvidé lo último que hablábamos."), delCanal.dicho, promesa(224))
        assertEquals(3, delCanal.decisiones().size, promesa(224) + " · ${delCanal.decisiones()}")
        assertEquals(1, delCanal.enLog("fin de la escucha por cancelación"), promesa(224) + " · la única cancelación es la de detener al final: ${delCanal.decisiones()}")

        // EL MICRÓFONO TAMBIÉN RECIBE CANCELACIONES AJENAS. Un adaptador que vence su withTimeout al mandar un trozo lanza una
        // CancellationException con la voz viva: el trozo se pierde con su línea en el log y oirMicrofono no la relanza, porque
        // el bucle del micrófono que lo llama moriría callado.
        val mic = Voz()
        mic.canal.vencenAlEnviar = { json(it).texto("type") == "session.input_audio.append" }
        mic.guion(
            llega(sesionAbierta),
            hace {
                val oido = runCatching { repeat(2) { mic.conv.oirMicrofono(vozDeLaSala()) } }
                assertNull(oido.exceptionOrNull(), promesa(224) + " · el micrófono no muere callado: ${oido.exceptionOrNull()}")
                assertTrue(mic.conv.viva, promesa(224))
            },
            llega(sinHechos),
        )
        mic.conv.conversar()
        assertEquals(1, mic.enLog("un trozo de micrófono no llegó al servidor: TimeoutCancellationException"), promesa(224) + " · una línea por motivo: ${mic.log}")
        assertEquals(1, mic.canal.urls.size, promesa(224) + " · un trozo perdido no es un corte")
        assertEquals(1, mic.decisiones().size, promesa(224) + " · ${mic.decisiones()}")
        assertEquals(1, mic.enLog("fin de la escucha por cancelación"), promesa(224) + " · la de detener: ${mic.decisiones()}")
    }

    @Test
    fun promesa225() = corre {
        val v = Voz(ejecutor = { awaitCancellation() })
        v.guion(
            llega(fallo("rate_limit_exceeded", "Slow down.")),
            llega(sesionAbierta),
            llega(duracion(12.0)), llega(duracion(25.0)),
            llega(usuario("abre la cámara")), llega(pide("call_colgada", "abrir_app")),
            corte(),
            corte(),
            llega(sesionAbierta),
            hace { v.reloj.ms = 50_000 }, llega(usuario("otra cosa")),
            hace { v.reloj.ms = 52_000 }, llega(sinHechos),
            llega(duracion(7.0)),
        )
        v.conv.conversar()
        assertEquals(3, v.canal.urls.size, promesa(225) + " · la conexión 2 se cortó sin confirmar y sin falla propia: se reconecta")
        assertTrue(v.dicho.none { "No pude abrir" in it }, promesa(225) + " · la falla de antes de abrir era de la conexión 1: ${v.dicho}")
        assertEquals(1, v.enLog("usuario dijo: otra cosa"), promesa(225) + " · lo de la conexión 1 no sujeta el turno de la 3: ${v.log}")
        val duro = v.log.filter { "la voz duró" in it }
        assertEquals(1, duro.size, promesa(225) + " · una línea: $duro")
        assertTrue("32.0 s" in duro.single(), promesa(225) + " · 25 de la primera y 7 de la tercera: «${duro.single()}»")
    }

    @Test
    fun promesa226() = corre {
        val puerta = CompletableDeferred<String>()
        val v = Voz(ejecutor = { puerta.await() })
        fun avisos() = v.enviados().filter { it.texto("item", "role") == "user" }.map { (it.en("item", "content") as JsonArray)[0].texto("text") }
        v.guion(
            llega(sesionAbierta),
            llega(usuario("haz la tarea larga")), llega(pide("call_hacer", "hacer")),
            hace { v.conv.avisar("la tarea terminó: abrí la cámara") },
            llega(sinHechos),
            hace {
                assertTrue(avisos().isEmpty(), promesa(226) + " · con una llamada pendiente el aviso espera")
                assertEquals(0, v.cuenta("response.create"), promesa(226))
            },
            hace { puerta.complete("empecé la tarea") },
            llega(sinHechos),
            hace { v.conv.avisar("la tarea terminó: grabé 10 s") },
            llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(
            listOf<String?>("session.start", "response.item.create", "response.item.create", "response.create", "response.item.create", "response.create"),
            v.tipos(), promesa(226),
        )
        assertEquals("call_hacer", v.enviados()[1].texto("item", "call_id"), promesa(226) + " · la salida va antes que el aviso")
        assertEquals(listOf<String?>("[aviso del sistema] la tarea terminó: abrí la cámara", "[aviso del sistema] la tarea terminó: grabé 10 s"), avisos(), promesa(226) + " · cada aviso una sola vez")
        assertEquals(1, v.conv.peticiones, promesa(226) + " · el aviso no abre una petición del usuario")

        // Muerta la voz, el aviso no va a ninguna parte: lo devuelve, y queda la línea de por qué.
        assertFalse(v.conv.avisar("la tarea terminó: tarde"), promesa(226) + " · con la voz muerta se descarta")
        assertEquals(1, v.enLog("aviso del sistema descartado"), promesa(226) + " · ${v.log}")
        assertTrue(v.textos().none { "tarde" in it.orEmpty() }, promesa(226))

        // ANTES DE CONFIRMAR EL SERVIDOR NO ESCUCHA: el aviso se acepta y espera a `session.started`.
        val temprano = Voz()
        temprano.guion(
            hace { assertTrue(temprano.conv.avisar("la tarea terminó: temprano"), promesa(226) + " · con la voz viva se acepta") },
            llega(sinHechos),
            hace { assertEquals(listOf<String?>("session.start"), temprano.tipos(), promesa(226) + " · antes de session.started no sale") },
            llega(sesionAbierta),
            llega(sinHechos),
        )
        temprano.conv.conversar()
        assertEquals(listOf<String?>("session.start", "response.item.create", "response.create"), temprano.tipos(), promesa(226) + " · sale al confirmarse, una vez")
        assertEquals(listOf<String?>("[aviso del sistema] la tarea terminó: temprano"), temprano.textos(), promesa(226))
    }

    @Test
    fun promesa227() = corre {
        val secreto = "SECRETO-DEL-DELEGADO"
        val voz = seno(7000)
        val v = Voz()
        v.guion(
            llega(sesionAbierta),
            llega(delta(voz)),
            llega(delta(ByteArray(4800))),
            llega("""{"type":"session.output_audio.delta","delta":""}"""),
            llega("""{"type":"response.event","delegation_id":"item_1","event":{"type":"response.output_text.delta","delta":"$secreto"}}"""),
            llega("""{"type":"response.event","delegation_id":"item_1","event":{"type":"response.function_call_arguments.delta","delta":"{\"que\":\"$secreto"}}"""),
            llega("""{"type":"response.event","delegation_id":"item_1","event":{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$secreto"}]}}}"""),
            llega(pideCon("call_escribir", "escribir", """{"texto":"mi clave es 1234"}""")),
            llega(pideCon("call_buscar", "buscar", """{"que":"$secreto","cuantos":4321}""")),
            llega(dichoPorU("Veo la cámara")), llega(dichoPorU(" abierta.")),
            llega("""{"type":"session.delegation.created","delegation":{"id":"item_1","type":"delegation"}}"""),
            hace { v.reloj.ms = 5_000 }, llega(sinHechos),
        )
        v.conv.conversar()
        val b64 = Base64.encode(voz)
        assertTrue(v.log.none { b64.substring(8, 72) in it }, promesa(227) + " · el audio de Ü")
        assertTrue(v.log.none { "AAAAAAAAAAAAAAAA" in it }, promesa(227) + " · ni el silencio")
        assertTrue(v.log.none { secreto in it }, promesa(227) + " · ni lo que teclea el delegado: ${v.log.filter { secreto in it }}")
        assertEquals(listOf("call_escribir", "call_buscar"), v.ejecutadas, promesa(227))
        assertTrue(v.log.none { "mi clave" in it || "1234" in it || "4321" in it }, promesa(227) + " · ni los argumentos de una llamada: ${v.log.filter { "1234" in it || "4321" in it }}")
        assertEquals(1, v.enLog("response.event «response.output_item.done»"), promesa(227) + " · del mensaje del delegado, solo su tipo: ${v.log}")
        assertEquals(1, v.enLog("Veo la cámara"), promesa(227) + " · lo que dijo Ü, una vez y al cerrar el turno: ${v.log}")
        assertEquals(1, v.enLog("Ü dijo: Veo la cámara abierta."), promesa(227))
        assertEquals(1, v.enLog("session.delegation.created"), promesa(227) + " · lo que no se traduce sí se vuelca")
        assertContentEquals(voz, v.sonado.single(), promesa(227))
    }

    @Test
    fun promesa228() = corre {
        val v = Voz()
        val aviso = "de los 128"
        v.guion(
            llega(sesionAbierta),
            llega(usuario("mira")), llega(pide("call_mirar", "que_veo")),
            llega(sinHechos),
            hace {
                assertEquals(2, v.conv.itemsEnSesion, promesa(228) + " · la llamada del delegado ocupa un item del servidor, y su resultado otro")
                v.conv.avisar("la tarea terminó")
                assertEquals(3, v.conv.itemsEnSesion, promesa(228) + " · un aviso también")
                repeat(116) { v.conv.escribir("frase $it") }
                assertEquals(119, v.conv.itemsEnSesion, promesa(228))
                assertEquals(0, v.enLog(aviso), promesa(228))
                v.conv.escribir("la 120")
                assertEquals(1, v.enLog(aviso), promesa(228) + " · al llegar a 120")
                repeat(15) { v.conv.escribir("más $it") }
                assertEquals(135, v.conv.itemsEnSesion, promesa(228))
                assertEquals(1, v.enLog(aviso), promesa(228) + " · una sola vez")
            },
            corte(),
            llega(sesionAbierta),
            hace { assertEquals(0, v.conv.itemsEnSesion, promesa(228) + " · la sesión nueva empieza de cero") },
        )
        v.conv.conversar()
        assertEquals(133, v.enviados().count { it.texto("item", "type") == "message" }, promesa(228) + " · nada se cortó")
        assertTrue(v.enviados().any { (it.en("item", "content") as? JsonArray)?.get(0).texto("text") == "más 14" }, promesa(228))
    }

    @Test
    fun promesa229() = corre {
        fun cuadrada(a: Int) = pcm(*IntArray(2400) { if (it % 2 == 0) a else -a })
        val eco = cuadrada(800)
        val ecoFuerte = cuadrada(3000)
        val quedo = cuadrada(100)
        val encima = cuadrada(6000)
        val gritando = cuadrada(12000)
        suspend fun Voz.oye(trozo: ByteArray) {
            reloj.ms += 100
            conv.oirMicrofono(trozo)
        }
        fun ceros(a: ByteArray) = a.all { it == 0.toByte() }

        val v = Voz(compuertaActiva = true, bargeIn = true)
        v.guion(llega(sesionAbierta), hace {
            v.sonando = true
            repeat(10) { v.oye(eco) }
            assertEquals(10, v.audios().size, promesa(229))
            assertTrue(v.audios().all(::ceros), promesa(229) + " · mientras Ü suena la compuerta traga")
            var trozos = 0
            while (v.callado == 0 && trozos < 10) { v.oye(encima); trozos++ }
            assertEquals(1, v.callado, promesa(229) + " · la voz sostenida encima calla el altavoz")
            assertContentEquals(encima, v.audios().last(), promesa(229) + " · el trozo que disparó viaja intacto")
            assertTrue(v.audios().dropLast(1).all(::ceros), promesa(229) + " · lo anterior se tragó")
            v.oye(encima)
            assertContentEquals(encima, v.audios().last(), promesa(229) + " · la compuerta se reabrió sin esperar la gracia")
            assertEquals(1, v.callado, promesa(229))

            // EL REARME. Ü vuelve a hablar, y más fuerte: sus primeros 250 ms son eco por decreto y siembran la base de esta
            // frase. Rearmado, quien le habla encima la vuelve a callar.
            repeat(3) { v.oye(quedo) }
            v.sonando = true
            repeat(8) { v.oye(ecoFuerte) }
            assertEquals(1, v.callado, promesa(229) + " · la frase nueva de Ü no se toma por interrupción")
            trozos = 0
            while (v.callado == 1 && trozos < 10) { v.oye(gritando); trozos++ }
            assertEquals(2, v.callado, promesa(229) + " · tras disparar se rearma cuando Ü suena otra vez")
            assertContentEquals(gritando, v.audios().last(), promesa(229))
        })
        v.conv.conversar()

        // LA SIEMBRA ES POR FRASE. Ü calla, el eco muere en la gracia y el micrófono vuelve a viajar: el detector lo oye en
        // silencio. Sin eso seguía en la frase anterior, y el eco más fuerte de la siguiente pasaba por alguien encima.
        val frases = Voz(compuertaActiva = true, bargeIn = true)
        frases.guion(llega(sesionAbierta), hace {
            frases.sonando = true
            repeat(10) { frases.oye(eco) }
            frases.sonando = false
            repeat(4) { frases.oye(quedo) }
            assertContentEquals(quedo, frases.audios().last(), promesa(229) + " · pasada la gracia, el micrófono viaja")
            frases.sonando = true
            repeat(8) { frases.oye(ecoFuerte) }
            assertEquals(0, frases.callado, promesa(229) + " · el eco de la frase nueva siembra, no dispara")
            assertTrue(frases.audios().takeLast(8).all(::ceros), promesa(229))
        })
        frases.conv.conversar()

        // POR DEFECTO, APAGADO, como en U (`U_BARGEIN_ENERGIA`): allí la voz del usuario llegaba más débil que el eco y el
        // detector solo disparaba en falso. La compuerta sigue tragando.
        val porDefecto = Voz(compuertaActiva = true)
        porDefecto.guion(llega(sesionAbierta), hace {
            porDefecto.sonando = true
            repeat(10) { porDefecto.oye(eco) }
            repeat(6) { porDefecto.oye(encima) }
            assertEquals(0, porDefecto.callado, promesa(229) + " · por defecto el barge-in por energía no actúa")
            assertTrue(porDefecto.audios().all(::ceros), promesa(229) + " · y lo que suena encima se traga")
        })
        porDefecto.conv.conversar()

        val sin = Voz(bargeIn = true)
        // Se juzga DENTRO del guion: al terminar, la voz calla el altavoz por su cuenta, y eso no es el detector.
        sin.guion(llega(sesionAbierta), hace {
            sin.sonando = true
            repeat(10) { sin.oye(eco) }
            repeat(6) { sin.oye(encima) }
            assertEquals(0, sin.callado, promesa(229) + " · sin compuerta activa el detector no actúa")
            assertEquals(List(10) { eco.toList() } + List(6) { encima.toList() }, sin.audios().map { it.toList() }, promesa(229) + " · y todo viaja intacto")
        })
        sin.conv.conversar()
    }

    @Test
    fun promesa230() = corre {
        val persona = "Eres Ü. Hablas corto y delegas."
        val completas = "ERES Ü Y ESTAS SON TUS INSTRUCCIONES COMPLETAS"
        val aprendiz = "Eres Ü, y ahora te están ENSEÑANDO: asiente corto y no hagas nada."
        val revision = "Eres Ü, y ahora REVISAS lo hecho: solo miras."
        val paraAprender = listOf(Utensilio("map_where_am_i", "Dice en qué pantalla está", emptyList()))
        val paraRevisar = listOf(Utensilio("map_look", "Mira la pantalla", emptyList()))
        val v = Voz()
        fun aperturas() = v.enviados().filter { it.texto("type") == "session.start" }
        fun JsonObject.delegado() = texto("session", "delegation", "responses", "instructions")
        fun JsonObject.herramientas() = (en("session", "delegation", "responses", "tools") as? JsonArray)?.map { it.texto("name") }
        v.guion(
            llega(sesionAbierta),
            hace { v.conv.cambiarModo(aprendiz, paraAprender, vuelve = false) },
            llega(sinHechos),
            hace {
                assertEquals(listOf<String?>("session.start", "session.update", "session.instructions.append"), v.tipos(), promesa(230) + " · en plena sesión no se reabre")
                assertEquals(1, v.canal.urls.size, promesa(230))
                assertEquals(aprendiz, v.enviados()[1].delegado(), promesa(230))
                assertEquals(listOf<String?>("map_where_am_i"), v.enviados()[1].herramientas(), promesa(230))
            },
            corte(),
            hace { assertEquals("session.start", v.tipos().last(), promesa(230) + " · el append de la reapertura espera a que la sesión se confirme") },
            llega(sesionAbierta),
            hace { v.conv.cambiarModo(revision, paraRevisar, vuelve = false) },
            corte(),
            llega(sesionAbierta),
        )
        v.conv.conversar()
        assertEquals(3, v.canal.urls.size, promesa(230) + " · la conexión y dos reaperturas")
        assertEquals(3, aperturas().size, promesa(230) + " · ${v.tipos()}")
        val (primera, segunda, tercera) = aperturas()
        assertEquals(completas, primera.delegado(), promesa(230))
        assertEquals(aprendiz, segunda.delegado(), promesa(230) + " · la reapertura abre en el modo vigente, no en el inicial")
        assertEquals(listOf<String?>("map_where_am_i"), segunda.herramientas(), promesa(230) + " · con sus herramientas")
        assertEquals(revision, tercera.delegado(), promesa(230) + " · el último modo es el que manda")
        assertEquals(listOf<String?>("map_look"), tercera.herramientas(), promesa(230))
        assertTrue(aperturas().all { it.texto("session", "instructions") == persona }, promesa(230) + " · la voz reabre con su persona")

        // LA VOZ TAMBIÉN VUELVE AL MODO. El `session.start` abre con su persona: sin el append, el delegado reabría en un
        // modo y la voz en el de siempre. Confirmada la sesión, oye otra vez el cambio de modo con las reglas vigentes.
        val alCambiarDeModo = "CAMBIO DE MODO. Desde ahora mandan estas reglas sobre cuándo y cómo hablas, por encima de las anteriores:\n"
        fun Voz.appends() = enviados().filter { it.texto("type") == "session.instructions.append" }.map { it.texto("content") }
        assertEquals(
            listOf<String?>(
                "session.start", "session.update", "session.instructions.append",
                "session.start", "session.instructions.append", "session.update", "session.instructions.append",
                "session.start", "session.instructions.append",
            ),
            v.tipos(), promesa(230) + " · la reapertura en un modo especial le repite el modo a la voz",
        )
        assertEquals(
            listOf<String?>(alCambiarDeModo + aprendiz, alCambiarDeModo + aprendiz, alCambiarDeModo + revision, alCambiarDeModo + revision),
            v.appends(), promesa(230) + " · con el prefijo de cambio de modo y las reglas del modo vigente",
        )

        // De vuelta al modo de siempre, la reapertura ya es la de siempre: ningún append.
        val deVuelta = Voz()
        val deSiempre = listOf(Utensilio("pulsar", "Pulsa algo", listOf(Argumento("que", "qué pulsar"))))
        deVuelta.guion(
            llega(sesionAbierta),
            hace { deVuelta.conv.cambiarModo(aprendiz, paraAprender, vuelve = false) },
            hace { deVuelta.conv.cambiarModo(completas, deSiempre, vuelve = true) },
            corte(),
            llega(sesionAbierta),
            llega(sinHechos),
        )
        deVuelta.conv.conversar()
        assertEquals(
            listOf<String?>("session.start", "session.update", "session.instructions.append", "session.update", "session.instructions.append", "session.start"),
            deVuelta.tipos(), promesa(230) + " · en el modo de siempre la reapertura no manda append",
        )
        assertEquals(completas, deVuelta.enviados().last().delegado(), promesa(230))

        // CAMBIAR DE MODO CON LA SESIÓN ABIERTA Y SIN CONFIRMAR. El session.start ya salió con el modo de antes: al confirmarse,
        // el append solo dejaba a la voz en un modo y al delegado, con sus instrucciones y sus herramientas, en el otro.
        val sinConfirmar = Voz()
        sinConfirmar.guion(
            hace { sinConfirmar.conv.cambiarModo(aprendiz, paraAprender, vuelve = false) },
            llega(sinHechos),
            hace { assertEquals(listOf<String?>("session.start"), sinConfirmar.tipos(), promesa(230) + " · sin confirmar no sale nada") },
            llega(sesionAbierta),
            llega(sinHechos),
        )
        sinConfirmar.conv.conversar()
        assertEquals(
            listOf<String?>("session.start", "session.update", "session.instructions.append"),
            sinConfirmar.tipos(), promesa(230) + " · el start llevaba el modo de antes: al confirmar sale el cambio entero",
        )
        val (abrio, delegacion) = sinConfirmar.enviados()
        assertEquals(completas, abrio.delegado(), promesa(230))
        assertEquals(aprendiz, delegacion.delegado(), promesa(230) + " · el delegado también cambia de modo")
        assertEquals(listOf<String?>("map_where_am_i"), delegacion.herramientas(), promesa(230) + " · con sus herramientas")
        assertEquals(listOf<String?>(alCambiarDeModo + aprendiz), sinConfirmar.appends(), promesa(230))

        // Y DE VUELTA: la reapertura salió en aprendiz y, antes de confirmarse, se vuelve al de siempre.
        val alVolver = "VUELVES A TU MODO DE SIEMPRE. Lo anterior sobre el modo especial ya no manda; desde ahora mandan estas reglas:\n"
        val vuelveSinConfirmar = Voz()
        vuelveSinConfirmar.guion(
            llega(sesionAbierta),
            hace { vuelveSinConfirmar.conv.cambiarModo(aprendiz, paraAprender, vuelve = false) },
            corte(),
            hace { vuelveSinConfirmar.conv.cambiarModo(completas, deSiempre, vuelve = true) },
            llega(sesionAbierta),
            llega(sinHechos),
        )
        vuelveSinConfirmar.conv.conversar()
        assertEquals(
            listOf<String?>("session.start", "session.update", "session.instructions.append", "session.start", "session.update", "session.instructions.append"),
            vuelveSinConfirmar.tipos(), promesa(230) + " · la reapertura salió en aprendiz y al confirmar vuelve entera al de siempre",
        )
        val deLaVuelta = vuelveSinConfirmar.enviados()
        assertEquals(aprendiz, deLaVuelta[3].delegado(), promesa(230))
        assertEquals(completas, deLaVuelta[4].delegado(), promesa(230) + " · el delegado no se queda en aprendiz toda la sesión")
        assertEquals(listOf<String?>("pulsar"), deLaVuelta[4].herramientas(), promesa(230))
        assertEquals(alVolver + persona, deLaVuelta[5].texto("content"), promesa(230) + " · y la voz vuelve a su persona")
    }

    @Test
    fun promesa231() = corre {
        /** Detiene la voz en plena espera, con una espera que no vence sola. Si la voz no termina, la abre: la prueba sale roja, no colgada. */
        suspend fun detenerEsperando(v: Voz, que: String) {
            val puerta = CompletableDeferred<Unit>().also { v.reloj.puerta = it }
            coroutineScope {
                val trabajo = launch { v.conv.conversar() }
                var vueltas = 0
                while (v.reloj.esperas.isEmpty() && vueltas++ < 200) yield()
                assertEquals(1, v.reloj.esperas.size, promesa(231) + " · $que: la espera empezó")
                v.conv.detener()
                repeat(20) { yield() }
                val termino = trabajo.isCompleted
                if (!termino) {
                    puerta.complete(Unit)
                    trabajo.cancelAndJoin()
                }
                assertTrue(termino, promesa(231) + " · $que: detener la corta y la voz termina sin esperar a que venza")
            }
        }

        val reintento = Voz()
        reintento.canal.aperturas += Apertura.SinRed("sin ruta al host")
        detenerEsperando(reintento, "el reintento de abrir")
        assertEquals(listOf(1000L), reintento.reloj.esperas, promesa(231))
        assertEquals(0L, reintento.reloj.ms, promesa(231) + " · el reloj no avanzó")
        assertEquals(1, reintento.canal.urls.size, promesa(231) + " · ni se reabrió")
        assertTrue(reintento.dicho.isEmpty(), promesa(231) + " · ni se dice que no hay red: ${reintento.dicho}")
        assertFalse(reintento.conv.viva, promesa(231))

        val reconexion = Voz()
        reconexion.guion(llega(sesionAbierta), corte())
        detenerEsperando(reconexion, "la reconexión")
        assertEquals(listOf(300L), reconexion.reloj.esperas, promesa(231))
        assertEquals(0L, reconexion.reloj.ms, promesa(231) + " · el reloj no avanzó")
        assertEquals(1, reconexion.canal.urls.size, promesa(231) + " · detener no reconecta")
        assertEquals(listOf("Te escucho."), reconexion.dicho, promesa(231))
        assertFalse(reconexion.conv.viva, promesa(231))
    }

    /** Lo que lanza el freno de la fase 3A cuando el usuario para una tarea: una cancelación que NO es de la voz. */
    class Paraste : CancellationException("paraste la tarea")

    @Test
    fun promesa232() = corre {
        val v = Voz(ejecutor = {
            when (it.nombre) {
                "parada" -> throw Paraste()
                "vencida" -> withTimeout(0) { "no llega" }
                "sin_hacer" -> TODO("sin manos todavía")
                else -> "hecho: ${it.nombre}"
            }
        })
        v.guion(
            llega(sesionAbierta),
            hace { v.reloj.ms = 1_000 }, llega(usuario("para eso, espera, hazlo y mira")),
            llega(pide("call_parada", "parada")),
            llega(pide("call_vencida", "vencida")),
            llega(pide("call_todo", "sin_hacer")),
            llega(pide("call_mirar", "que_veo")),
            llega(sinHechos),
            hace { v.reloj.ms = 3_000 }, llega(sinHechos),
        )
        // Un Error que se escapa lo relanza conversar(): se atrapa aquí para afirmar sobre lo contestado, no para tumbar el runner.
        val r = runCatching { v.conv.conversar() }
        assertNull(r.exceptionOrNull(), promesa(232) + " · la voz terminó con ${r.exceptionOrNull()}")
        val ids = listOf("call_parada", "call_vencida", "call_todo", "call_mirar")
        assertEquals(ids, v.ejecutadas, promesa(232) + " · cada una corre aunque la anterior se parara o fallara")
        assertEquals(ids, v.salidas().map { it.texto("item", "call_id") }, promesa(232) + " · y cada una se contesta: ${v.tipos()}")
        val (parada, vencida, todo, mirar) = v.salidas().map { it.texto("item", "output").orEmpty() }
        assertEquals("la herramienta se paró: paraste la tarea", parada, promesa(232) + " · el freno")
        assertTrue(vencida.startsWith("la herramienta se paró: "), promesa(232) + " · un withTimeout que vence: «$vencida»")
        assertEquals("la herramienta falló: An operation is not implemented: sin manos todavía", todo, promesa(232) + " · un Error")
        assertEquals("hecho: que_veo", mirar, promesa(232))
        assertEquals("response.create", v.tipos().last(), promesa(232) + " · sin nada pendiente se pide la respuesta: ${v.tipos()}")
        assertEquals(1, v.enLog("usuario dijo: para eso, espera, hazlo y mira"), promesa(232) + " · el turno se cerró: ${v.log}")
        assertEquals(listOf("Te escucho."), v.dicho, promesa(232) + " · la voz no terminó ni dijo nada más")

        // LA QUE CANCELA SU PROPIA CORRUTINA NO ES LA VOZ QUE TERMINA. En la pantalla se llevaba al obrero: la de detrás no
        // corría, el turno no cerraba y los avisos esperaban a reconectar. En las de control se iba sin salida, y con un
        // function_call huérfano el servidor rechaza cada response.create de la sesión: el delegado quedaba mudo.
        for ((donde, enPantalla) in listOf("en la pantalla" to true, "de control" to false)) {
            val s = Voz(actuaEnPantalla = { enPantalla }, ejecutor = {
                if (it.id == "call_a") {
                    currentCoroutineContext().cancel()
                    awaitCancellation()
                }
                "hecho: ${it.id}"
            })
            s.guion(
                llega(sesionAbierta),
                hace { s.reloj.ms = 1_000 }, llega(usuario("pulsa dos veces")),
                llega(pide("call_a", "pulsar")), llega(pide("call_b", "pulsar")),
                llega(sinHechos),
                hace { s.conv.avisar("la tarea terminó: pulsé dos veces") },
                llega(sinHechos),
                hace { s.reloj.ms = 5_000 }, llega(sinHechos),
            )
            val sola = runCatching { s.conv.conversar() }
            assertNull(sola.exceptionOrNull(), promesa(232) + " · $donde: la voz terminó con ${sola.exceptionOrNull()}")
            assertEquals(listOf("call_a", "call_b"), s.ejecutadas, promesa(232) + " · $donde: la de detrás corre")
            val contestadas = s.salidas().associate { it.texto("item", "call_id") to it.texto("item", "output").orEmpty() }
            assertEquals(setOf<String?>("call_a", "call_b"), contestadas.keys, promesa(232) + " · $donde: las dos se contestan, también la que se canceló sola: ${s.tipos()}")
            assertTrue(contestadas["call_a"].orEmpty().startsWith("la herramienta se paró: "), promesa(232) + " · $donde: «${contestadas["call_a"]}»")
            assertEquals("hecho: call_b", contestadas["call_b"], promesa(232) + " · $donde")
            assertEquals(listOf<String?>("[aviso del sistema] la tarea terminó: pulsé dos veces"), s.textos(), promesa(232) + " · $donde: el aviso sale sin esperar a reconectar")
            assertEquals("response.create", s.tipos().last(), promesa(232) + " · $donde: ${s.tipos()}")
            assertEquals(1, s.enLog("usuario dijo: pulsa dos veces"), promesa(232) + " · $donde: el turno cierra: ${s.log}")
            assertEquals(1, s.canal.urls.size, promesa(232) + " · $donde: sin reconectar")
        }

        // SOLO TERMINAR LA CONVERSACIÓN LA CANCELA: la que corría no se contesta como si se hubiera parado sola, y la de
        // detrás no corre. Cancelada la corrutina, contestar sería actuar por una voz que ya no está.
        for (como in listOf("detener", "cancelar la corrutina")) {
            val canceladas = mutableListOf<String>()
            val t = Voz(ejecutor = {
                try { awaitCancellation() } catch (e: CancellationException) { canceladas += it.id; throw e }
            })
            t.guion(llega(sesionAbierta), llega(usuario("pulsa dos veces")), llega(pide("call_colgada", "pulsar")), llega(pide("call_detras", "pulsar")))
            if (como == "detener") {
                t.conv.conversar()
            } else {
                t.guion(Paso.Cuelga)
                coroutineScope {
                    val trabajo = launch { t.conv.conversar() }
                    var vueltas = 0
                    while ((t.canal.guion.isNotEmpty() || t.ejecutadas.isEmpty()) && vueltas++ < 200) yield()
                    repeat(10) { yield() }
                    trabajo.cancelAndJoin()
                }
            }
            assertEquals(listOf("call_colgada"), t.ejecutadas, promesa(232) + " · $como: la de detrás no corre")
            assertEquals(listOf("call_colgada"), canceladas, promesa(232) + " · $como: la cancelación llega a la herramienta")
            assertTrue(t.salidas().isEmpty(), promesa(232) + " · $como: y no se contesta: ${t.salidas()}")
            assertFalse(t.conv.viva, promesa(232))
        }
    }

    @Test
    fun promesa233() = corre {
        val canceladas = mutableListOf<String>()
        val v = Voz(ejecutor = {
            if (it.id != "call_viejo") "hecho: ${it.nombre}"
            else try { awaitCancellation() } catch (e: CancellationException) { canceladas += it.id; throw e }
        })
        v.guion(
            llega(sesionAbierta),
            llega(usuario("pulsa grabar")), llega(pide("call_viejo", "pulsar")),
            corte(),
            llega(sesionAbierta),
            hace { assertEquals(listOf("call_viejo"), canceladas, promesa(233) + " · confirmada la conexión nueva, la colgada de la anterior ya se canceló") },
            hace { v.reloj.ms = 10_000 }, llega(usuario("pulsa grabar otra vez")), llega(pide("call_nuevo", "pulsar")),
            llega(sinHechos),
            hace { v.reloj.ms = 12_000 }, llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(listOf("call_viejo", "call_nuevo"), v.ejecutadas, promesa(233) + " · la de la conexión nueva no espera detrás de la colgada")
        assertEquals(listOf<String?>("call_nuevo"), v.salidas().map { it.texto("item", "call_id") }, promesa(233) + " · la vieja no se contesta en la sesión nueva")
        assertEquals(1, v.cuenta("response.create"), promesa(233) + " · ${v.tipos()}")
        assertEquals(1, v.enLog("usuario dijo: pulsa grabar otra vez"), promesa(233) + " · y el turno de la nueva se cierra: ${v.log}")
    }

    @Test
    fun promesa234() = corre {
        val puertas = mapOf("call_largo" to CompletableDeferred<String>(), "call_otro" to CompletableDeferred())
        val v = Voz(actuaEnPantalla = { it == "pulsar" }, ejecutor = { puertas[it.id]?.await() ?: "hecho: ${it.nombre}" })
        fun ids() = v.salidas().map { it.texto("item", "call_id") }
        v.guion(
            llega(sesionAbierta),
            llega(usuario("pulsa grabar dos veces… no, para")),
            llega(pide("call_largo", "pulsar")),
            llega(pide("call_otro", "pulsar")),
            llega(pide("call_parar", "parar")),
            llega(pide("call_como", "como_va")),
            llega(pide("call_mudo", "self_mute")),
            llega(sinHechos),
            hace {
                assertEquals(listOf("call_largo", "call_parar", "call_como", "call_mudo"), v.ejecutadas, promesa(234) + " · las de control corren en el acto y la otra de pantalla espera su turno")
                assertEquals(listOf<String?>("call_parar", "call_como", "call_mudo"), ids(), promesa(234) + " · y se contestan sin esperar")
                assertEquals(0, v.cuenta("response.create"), promesa(234) + " · con la de pantalla pendiente no se pide respuesta")
            },
            hace { puertas.getValue("call_largo").complete("pulsé grabar") },
            llega(sinHechos),
            hace { assertEquals("call_otro", v.ejecutadas.last(), promesa(234) + " · la segunda de pantalla corre al acabar la primera") },
            hace { puertas.getValue("call_otro").complete("pulsé grabar otra vez") },
            llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(listOf<String?>("call_parar", "call_como", "call_mudo", "call_largo", "call_otro"), ids(), promesa(234))
        assertEquals(1, v.cuenta("response.create"), promesa(234) + " · ${v.tipos()}")
        assertEquals("response.create", v.tipos().last(), promesa(234))

        // POR DEFECTO TODAS ACTÚAN EN LA PANTALLA: construida sin decir cuáles, ni «parar» se cruza con la que está actuando.
        val soltar = CompletableDeferred<String>()
        val sinCatalogo = Voz(aPelo = true, ejecutor = { if (it.id == "call_largo") soltar.await() else "hecho: ${it.nombre}" })
        sinCatalogo.guion(
            llega(sesionAbierta),
            llega(pide("call_largo", "pulsar")),
            llega(pide("call_parar", "parar")),
            llega(sinHechos),
            hace {
                assertEquals(listOf("call_largo"), sinCatalogo.ejecutadas, promesa(234) + " · sin decir cuáles actúan en la pantalla, parar espera su turno")
                assertTrue(sinCatalogo.salidas().isEmpty(), promesa(234))
            },
            hace { soltar.complete("pulsé") },
            llega(sinHechos),
            llega(sinHechos),
        )
        sinCatalogo.conv.conversar()
        assertEquals(listOf("call_largo", "call_parar"), sinCatalogo.ejecutadas, promesa(234))
        assertEquals(listOf<String?>("call_largo", "call_parar"), sinCatalogo.salidas().map { it.texto("item", "call_id") }, promesa(234) + " · en el orden en que llegaron")
    }

    @Test
    fun promesa235() = corre {
        val puerta = CompletableDeferred<String>()
        val v = Voz(ejecutor = { if (it.id == "call_mirar") puerta.await() else "hecho: ${it.nombre}" })
        v.guion(
            llega(sesionAbierta),
            llega(usuario("mira y pulsa grabar")),
            llega(pide("call_mirar", "que_veo")),
            llega(pide("call_pulsar", "pulsar")),
            hace { v.conv.retirar(listOf("call_pulsar")) },
            hace { puerta.complete("veo la cámara") },
            llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(listOf("call_mirar"), v.ejecutadas, promesa(235) + " · la retirada no se ejecuta")
        assertEquals(
            listOf<Pair<String?, String?>>("call_mirar" to "veo la cámara", "call_pulsar" to "retirada: no se ejecutó"),
            v.salidas().map { it.texto("item", "call_id") to it.texto("item", "output") },
            promesa(235) + " · pero se contesta: GPT-Live no la retiró y sin su salida la espera para siempre",
        )
        assertEquals(1, v.cuenta("response.create"), promesa(235) + " · ${v.tipos()}")
        assertEquals("response.create", v.tipos().last(), promesa(235) + " · la respuesta se pide detrás de la salida de la retirada")

        // Retirada antes de llegar, y sola: igual se contesta y, sin nada pendiente, se pide la respuesta.
        val antes = Voz()
        antes.guion(
            llega(sesionAbierta),
            hace { antes.conv.retirar(listOf("call_tarde")) },
            llega(pide("call_tarde", "pulsar")),
            llega(sinHechos),
        )
        antes.conv.conversar()
        assertTrue(antes.ejecutadas.isEmpty(), promesa(235))
        assertEquals(listOf<String?>("session.start", "response.item.create", "response.create"), antes.tipos(), promesa(235))
        assertEquals("retirada: no se ejecutó", antes.salidas().single().texto("item", "output"), promesa(235))
    }

    @Test
    fun promesa237() = corre {
        val secreto = "mi clave es 1234"
        val v = Voz(ejecutor = {
            when (it.nombre) {
                "escribir" -> throw IllegalStateException("no pude escribir «$secreto»")
                "parada" -> throw CancellationException("se paró escribiendo $secreto")
                else -> TODO(secreto)
            }
        })
        v.guion(
            llega(sesionAbierta),
            llega(pide("call_escribir", "escribir")), llega(pide("call_parada", "parada")), llega(pide("call_todo", "sin_hacer")),
            llega(sinHechos),
            Paso.Revienta("murió leyendo «$secreto»"),
        )
        v.conv.conversar()
        assertEquals(
            listOf<String?>("la herramienta falló: no pude escribir «$secreto»", "la herramienta se paró: se paró escribiendo $secreto", "la herramienta falló: An operation is not implemented: $secreto"),
            v.salidas().map { it.texto("item", "output") }, promesa(237) + " · al modelo le llega el motivo",
        )
        assertTrue(v.log.none { "1234" in it || "mi clave" in it }, promesa(237) + " · al log no: ${v.log.filter { "1234" in it }}")
        for (tipo in listOf("IllegalStateException", "CancellationException", "NotImplementedError")) {
            assertEquals(1, v.log.count { tipo in it && ("reventó" in it || "se paró" in it) }, promesa(237) + " · el tipo sí: $tipo en ${v.log}")
        }
        assertEquals(1, v.enLog("se cortó la escucha: IllegalStateException"), promesa(237) + " · el error del canal, con su tipo: ${v.log}")

        // EL ERROR DEL CANAL SE SANEA: primera línea, sin lo que va entre comillas ni lo que tiene forma de clave, y corto.
        val canalRoto = Voz()
        canalRoto.canal.lanzanAlAbrir[1] = IllegalStateException("TLS falló para Bearer sk-prueba-99 y tok_0123456789abcdefghijklmn con «$secreto» " + "x y ".repeat(200) + "\nsegunda línea")
        canalRoto.conv.conversar()
        val todo = canalRoto.log + canalRoto.dicho
        assertTrue(todo.none { "sk-prueba" in it || "0123456789abcdefghij" in it || "1234" in it || "segunda línea" in it }, promesa(237) + " · $todo")
        assertTrue(todo.all { it.length < 300 }, promesa(237) + " · recortado: ${todo.map { it.length }}")
        assertEquals(1, canalRoto.dicho.size, promesa(237))
        assertTrue(canalRoto.dicho.single().startsWith("No pude abrir la voz en vivo: IllegalStateException: TLS falló para "), promesa(237) + " · «${canalRoto.dicho.single()}»")

        // UNA CLAVE CORTA Y SUELTA, sin «Bearer» delante y sin largo de token: la tapa su propia regla.
        val claveSuelta = Voz()
        claveSuelta.canal.lanzanAlAbrir[1] = IllegalStateException("la clave sk-abc123 no vale")
        claveSuelta.conv.conversar()
        assertTrue((claveSuelta.log + claveSuelta.dicho).none { "abc123" in it }, promesa(237) + " · ${claveSuelta.log + claveSuelta.dicho}")
        assertEquals(listOf("No pude abrir la voz en vivo: IllegalStateException: la clave … no vale."), claveSuelta.dicho, promesa(237))

        // LO QUE DICE EL SERVIDOR TAMBIÉN SE SANEA: repite los valores que se le mandaron y la cola de la clave, y el motivo de
        // un cierre son hasta 123 B suyos. Al log llegan sin lo citado ni lo que tiene forma de clave, también en la decisión.
        val eco = Voz()
        eco.guion(
            llega(sesionAbierta),
            llega(fallo("invalid_value", "Invalid value: 'voz_que_no_existe'. Supported values are: 'marin'.")),
            llega(fallo("", "Incorrect API key provided: sk-proj-****0000.")),
            cierra(4000, "insufficient_quota.credit_balance_exhausted sk-proj-****0000 'voz_que_no_existe'"),
        )
        eco.conv.conversar()
        val delServidor = eco.log.filter { "voz_que_no_existe" in it || "sk-proj" in it || "****0000" in it }
        assertTrue(delServidor.isEmpty(), promesa(237) + " · lo que repite el servidor no llega al log: $delServidor")
        assertEquals(1, eco.enLog("el servidor dice: Invalid value: «…». Supported values are: «…»."), promesa(237) + " · ${eco.log}")
        assertEquals(1, eco.enLog("el servidor dice: Incorrect API key provided: …"), promesa(237) + " · ${eco.log}")
        assertEquals(1, eco.enLog("el servidor cerró la conexión: 4000"), promesa(237) + " · ${eco.log}")
        assertEquals(1, eco.enLog("fin de la escucha por cierre: no se reintenta, la cuenta no tiene crédito"), promesa(237) + " · ${eco.log}")
    }

    @Test
    fun promesa238() = corre {
        val puerta = CompletableDeferred<String>()
        val v = Voz(ejecutor = { puerta.await() })
        v.guion(
            llega(sesionAbierta),
            llega(usuario("abre la cámara")), llega(pide("call_abrir", "abrir_app")),
            hace {
                v.conv.avisar("la tarea terminó: busqué la app")
                v.conv.escribir("y después graba")
            },
            llega(sinHechos),
            hace {
                assertEquals(listOf<String?>("session.start"), v.tipos(), promesa(238) + " · con la llamada sin contestar no sale ni el aviso ni lo escrito")
                assertEquals(2, v.conv.peticiones, promesa(238) + " · lo escrito abre su petición al escribirse")
            },
            hace { puerta.complete("cámara abierta") },
            llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(
            listOf<String?>("session.start", "response.item.create", "response.item.create", "response.item.create", "response.create"),
            v.tipos(), promesa(238) + " · la salida, la cola entera y un solo pedido de respuesta",
        )
        assertEquals("call_abrir", v.enviados()[1].texto("item", "call_id"), promesa(238))
        assertEquals(listOf<String?>("[aviso del sistema] la tarea terminó: busqué la app", "y después graba"), v.textos(), promesa(238) + " · en orden, y lo escrito sin prefijo")
        assertEquals(2, v.conv.peticiones, promesa(238))

        // LA COLA ES DE LA CONEXIÓN. Lo escrito esperaba a una llamada de una sesión que ya no existe: la nueva no lo recuerda
        // y se descarta. Un aviso sigue siendo verdad, y pasa a la siguiente.
        val muere = Voz(ejecutor = { awaitCancellation() })
        muere.guion(
            llega(sesionAbierta), llega(pide("call_colgada", "pulsar")),
            hace {
                muere.conv.escribir("esto era para la sesión que murió")
                muere.conv.avisar("la tarea terminó: grabé")
            },
            llega(sinHechos),
            corte(),
            llega(sesionAbierta),
            llega(sinHechos),
        )
        muere.conv.conversar()
        assertEquals(listOf<String?>("session.start", "session.start", "response.item.create", "response.create"), muere.tipos(), promesa(238))
        assertEquals(listOf<String?>("[aviso del sistema] la tarea terminó: grabé"), muere.textos(), promesa(238))
        assertEquals(1, muere.enLog("lo escrito en cola se descarta"), promesa(238) + " · ${muere.log}")
    }

    /* ---------- Fase B1b: la cola del altavoz y lo que de la voz sale del teléfono ---------- */

    /** Un segundo a 24 kHz cuyas muestras dicen qué segundo es: así se ve cuál se descartó. */
    private fun segundo(n: Int) = pcm(*IntArray(24_000) { n + 1 })

    @Test
    fun promesa244() {
        val cola = ColaDeReproduccion()
        assertEquals(1_440_000, cola.capacidad, promesa(244) + " · 30 s a 24 kHz mono PCM16")
        assertFalse(cola.sonando(), promesa(244) + " · recién nacida no suena")
        cola.meter(ByteArray(0))
        assertFalse(cola.sonando(), promesa(244) + " · un trozo vacío no suena")

        for (s in 0..30) cola.meter(segundo(s))
        assertEquals(cola.capacidad, cola.pendientes, promesa(244) + " · nunca más de 30 s")
        assertEquals(48_000L, cola.bytesDescartados, promesa(244) + " · el segundo que no cupo se cuenta")
        assertContentEquals(segundo(1), cola.sacar(48_000), promesa(244) + " · se descartó el segundo 0, el más viejo: lo primero que suena es el 1")
        repeat(28) { cola.sacar(48_000) }
        assertContentEquals(segundo(30), cola.sacar(48_000), promesa(244) + " · lo último que llegó sigue ahí")
        assertFalse(cola.sonando(), promesa(244) + " · entregada entera al altavoz, ya no suena")

        // Un trozo que no cabe entero: lo más nuevo es su final.
        val grande = ByteArray(35 * 48_000).also { b -> for (s in 0 until 35) segundo(s).copyInto(b, s * 48_000) }
        val otra = ColaDeReproduccion()
        otra.meter(segundo(99))
        otra.meter(grande)
        assertEquals(otra.capacidad, otra.pendientes, promesa(244))
        assertEquals(6 * 48_000L, otra.bytesDescartados, promesa(244) + " · el segundo viejo y los 5 primeros del grande")
        assertContentEquals(segundo(5), otra.sacar(48_000), promesa(244) + " · de un trozo que no cabe se queda su final")

        // SUENA POR BYTES, NUNCA POR VOLUMEN: el silencio que espera en cola también es Ü hablando.
        val silencio = ColaDeReproduccion()
        silencio.meter(ByteArray(4800))
        assertTrue(silencio.sonando(), promesa(244) + " · 100 ms de ceros en cola suenan: la llave es el estado, no el volumen")
        assertContentEquals(ByteArray(4800), CompuertaDeEco().filtrar(vozDeLaSala(), sonando = silencio.sonando(), ahora = 0), promesa(244) + " · y la compuerta lo ve sonando")
        assertEquals(4798, silencio.sacar(4799).size, promesa(244) + " · sacar da muestras enteras: media muestra desalinea todo lo que sigue")
        assertTrue(silencio.sonando(), promesa(244) + " · mientras quede una muestra, suena")
        assertEquals(2, silencio.sacar(100).size, promesa(244))
        assertFalse(silencio.sonando(), promesa(244))

        val orden = ColaDeReproduccion()
        orden.meter(pcm(1, 2, 3))
        orden.meter(pcm(4, 5))
        assertEquals(0, orden.sacar(0).size, promesa(244))
        assertContentEquals(pcm(1, 2), orden.sacar(4), promesa(244) + " · hasta n bytes, en orden")
        assertContentEquals(pcm(3, 4, 5), orden.sacar(100), promesa(244) + " · a través de trozos y sin rellenar")
        assertEquals(0, orden.sacar(100).size, promesa(244))

        // CALLAR VACÍA EN EL ACTO: lo que ya estaba en cola no espera a nadie para irse.
        val habla = ColaDeReproduccion()
        habla.meter(seno(7000))
        habla.meter(seno(7000))
        habla.callar()
        assertFalse(habla.sonando(), promesa(244) + " · callada no suena")
        assertEquals(0, habla.pendientes, promesa(244))
        assertEquals(0, habla.sacar(4800).size, promesa(244) + " · al altavoz no le queda nada de lo que decía")
        habla.meter(pcm(9))
        assertContentEquals(pcm(9), habla.sacar(4800), promesa(244) + " · lo que llega después suena solo")
    }

    @Test
    fun promesa245() = corre {
        val v = Voz(ejecutor = { "SALIDA-SECRETA de ${it.nombre}" })
        v.guion(
            llega(sesionAbierta),
            llega(usuario("mi clave es 1234")), llega(usuario(" y la de casa ÑANDÚ 👋")),
            llega(pideCon("call_escribir", "escribir", """{"texto":"ARGUMENTO-SECRETO"}""")),
            llega("""{"type":"response.event","delegation_id":"item_1","event":{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"DELEGADO-SECRETO"}]}}}"""),
            llega("""{"type":"session.algo_nuevo","text":"DESCONOCIDO-SECRETO"}"""),
            llega(dichoPorU("Tu clave SECRETA")), llega(dichoPorU(" quedó guardada.")),
            hace { v.reloj.ms = 5_000 }, llega(sinHechos),
        )
        v.conv.conversar()

        val local = v.log.map { it.substringBefore(": ") to it.substringAfter(": ") }
        val remoto = local.mapNotNull { (tag, m) -> TelemetriaDeVoz.paraRemoto(tag, m) }
        // EL LOG LOCAL LO VE TODO: sin el filtro, esto es lo que saldría.
        assertEquals(1, v.enLog("usuario dijo: mi clave es 1234 y la de casa ÑANDÚ 👋"), promesa(245) + " · el log local conserva la frase: ${v.log}")
        assertEquals(1, v.enLog("DESCONOCIDO-SECRETO"), promesa(245) + " · y el evento desconocido volcado entero")
        for (secreto in listOf("mi clave", "1234", "ÑANDÚ", "👋", "SECRET", "guardada")) {
            assertTrue(remoto.none { secreto in it }, promesa(245) + " · «$secreto» no sale del teléfono: ${remoto.filter { secreto in it }}")
        }
        assertEquals(1, remoto.count { it == "usuario dijo: 37 caracteres" }, promesa(245) + " · el largo de la frase, en caracteres (el emoji es uno): $remoto")
        assertEquals(1, remoto.count { it == "Ü dijo: 32 caracteres" }, promesa(245) + " · $remoto")
        assertTrue(remoto.any { it.startsWith("← ") && "session.algo_nuevo" in it }, promesa(245) + " · del evento desconocido, su tipo: $remoto")
        assertEquals(1, remoto.count { it.startsWith("fin de la escucha por") }, promesa(245) + " · el cierre de la escucha pasa: $remoto")
        assertTrue("sesión cerrada" in remoto, promesa(245) + " · y el de la sesión")
        for ((tag, m) in local) {
            if ("dijo:" in m || '{' in m || "SECRET" in m) continue
            assertEquals(m, TelemetriaDeVoz.paraRemoto(tag, m), promesa(245) + " · lo que no trae contenido pasa igual")
        }

        val viva = ConversacionViva.TAG
        assertEquals("usuario dijo: 16 caracteres", TelemetriaDeVoz.paraRemoto(viva, "usuario dijo: mi clave es 1234"), promesa(245))
        assertEquals("Ü dijo: 16 caracteres", TelemetriaDeVoz.paraRemoto(viva, "Ü dijo: Ya está 👋\nlisto."), promesa(245) + " · el emoji y el salto de línea cuentan uno cada uno")
        assertEquals("usuario dijo: 0 caracteres", TelemetriaDeVoz.paraRemoto(viva, "usuario dijo: "), promesa(245))
        assertTrue(TelemetriaDeVoz.esDeLaVoz(viva) && TelemetriaDeVoz.esDeLaVoz("voz-canal") && TelemetriaDeVoz.esDeLaVoz("voz-audio"), promesa(245) + " · todo tag «voz-» es de la voz")
        assertFalse(TelemetriaDeVoz.esDeLaVoz("app"), promesa(245))
        assertEquals("usuario dijo: hola", TelemetriaDeVoz.paraRemoto("app", "usuario dijo: hola"), promesa(245) + " · fuera de la voz el filtro no toca nada")

        // Las formas en que un contenido podría colarse en una línea de la voz.
        val llamada = Llamada("call_1", "escribir", mapOf("texto" to "SECRETO"))
        val cuelan = listOf(
            "voz-canal" to """← {"type":"response.event","event":{"type":"response.output_text.delta","delta":"SECRETO"}}""",
            viva to "llamada: $llamada",
            viva to "tanda: ${Hecho.Pide(listOf(llamada))}",
            viva to "resultado: ${Resultado("call_1", "SECRETO")}",
            viva to "hecho: ${Hecho.DiceU("SECRETO")}",
            viva to "hecho: ${Hecho.DiceElUsuario("SECRETO")}",
            "voz-dev" to "dice: el usuario dijo: SECRETO",
        )
        for ((tag, m) in cuelan) {
            assertTrue(TelemetriaDeVoz.paraRemoto(tag, m)?.contains("SECRETO") != true, promesa(245) + " · «$m» → «${TelemetriaDeVoz.paraRemoto(tag, m)}»")
        }
    }

    /* ---------- Ojos y catálogo: dónde estoy, qué veo, qué puedo hacer (2B2a) ---------- */

    /** El `uiContext` tal como lo arma hoy el servicio de accesibilidad (`GraphAccessibilityService.uiContext()`). */
    private fun uiContextComoElDelTurno(
        paquete: String = "com.whatsapp",
        tipo: String = "aplicación",
        teclado: Boolean = true,
        tocables: Int = 12,
        campos: Int = 2,
        enfocado: String = "Mensaje",
        etiquetas: List<String> = listOf("Cámara", "Enviar", "Enviar audio", "Adjuntar", "Buscar"),
    ): String = buildString {
        append("paquete: $paquete\n")
        append("tipo: $tipo${if (teclado) " · teclado abierto" else ""}\n")
        append("clickeables: $tocables · campos de texto: $campos")
        if (enfocado.isNotBlank()) append(" (enfocado: \"$enfocado\")")
        append("\netiquetas visibles: ")
        append(etiquetas.joinToString(" · ").ifBlank { "(ninguna)" })
    }

    /**
     * EL MISMO ESTADO QUE VIAJA EN EL TURNO DE GRAPH: el `ScreenState` de la accesibilidad pasado por `toTurnState`, sin
     * captura. Si la voz mirara por otro lado acabaría contando una pantalla distinta de la que ve el cerebro.
     */
    private fun pantallaDelTurno(
        screen: String = "com.whatsapp · WhatsApp",
        uiContext: String = uiContextComoElDelTurno(),
        ancho: Int = 1080,
        alto: Int = 2400,
    ): TurnScreenState = ScreenState(screen, uiContext, ancho, alto, screenshotPng = byteArrayOf(1, 2, 3))
        .toTurnState(apps = null, surface = AndroidSurface.from(screen), withScreenshot = false)

    @Test
    fun promesa247() {
        val estado = pantallaDelTurno()
        assertNull(estado.screenshot, promesa(247) + " · mirar no es capturar: la PNG no viaja")
        assertFalse(ProtocoloGptLive().mira, promesa(247) + " · la voz no se declara capaz de mirar")

        val donde = OjosDeLaVoz.dondeEstoy(estado)
        for (trozo in listOf("com.whatsapp · WhatsApp", "aplicación", "teclado abierto", "1080×2400")) {
            assertTrue(trozo in donde, promesa(247) + " · falta «$trozo» en «$donde»")
        }

        // El tipo de pantalla es el que trae el estado, no uno inventado; sin teclado no se habla de teclado.
        val home = OjosDeLaVoz.dondeEstoy(
            pantallaDelTurno(
                screen = "com.miui.home",
                uiContext = uiContextComoElDelTurno(
                    paquete = "com.miui.home", tipo = "launcher de Android (home o cajón de apps)",
                    teclado = false, campos = 0, enfocado = "",
                ),
            ),
        )
        assertTrue("launcher de Android (home o cajón de apps)" in home, promesa(247) + " · «$home»")
        assertFalse("teclado" in home, promesa(247) + " · no hay teclado abierto y se nombra: «$home»")

        // Sin servicio de accesibilidad no hay estado que leer, y se dice en vez de callar o inventar.
        assertEquals(OjosDeLaVoz.SIN_PANTALLA, OjosDeLaVoz.dondeEstoy(null), promesa(247))

        // Una pantalla protegida: lo que dice el estado, tal cual, y la app al frente se sabe igual.
        val protegida = OjosDeLaVoz.dondeEstoy(pantallaDelTurno(uiContext = "sin contenido accesible (pantalla vacía o protegida)"))
        assertTrue("sin contenido accesible (pantalla vacía o protegida)" in protegida, promesa(247) + " · «$protegida»")
        assertTrue("com.whatsapp · WhatsApp" in protegida, promesa(247) + " · «$protegida»")
    }

    @Test
    fun promesa248() {
        val estado = pantallaDelTurno()
        val todo = OjosDeLaVoz.queVeo(estado)
        for (trozo in listOf("12", "Mensaje", "Cámara", "Enviar audio", "Buscar")) {
            assertTrue(trozo in todo, promesa(248) + " · falta «$trozo» en «$todo»")
        }
        assertEquals(5, OjosDeLaVoz.etiquetas(estado), promesa(248))

        // Un filtro contesta si eso está, sin mirar tildes ni mayúsculas.
        for (filtro in listOf("enviar", "ENVIAR", "Enviar")) {
            val r = OjosDeLaVoz.queVeo(estado, filtro)
            assertTrue("«$filtro»: sí" in r, promesa(248) + " · «$filtro» → «$r»")
            assertTrue("Enviar audio" in r, promesa(248) + " · «$filtro» → «$r»")
        }
        val conTilde = OjosDeLaVoz.queVeo(estado, "camara")
        assertTrue("«camara»: sí" in conTilde && "Cámara" in conTilde, promesa(248) + " · «camara» no encontró «Cámara»: «$conTilde»")

        val no = OjosDeLaVoz.queVeo(estado, "guardar")
        assertTrue("«guardar»: no" in no, promesa(248) + " · «$no»")
        assertFalse("Enviar" in no, promesa(248) + " · lo que no se preguntó no se vuelca: «$no»")

        // Un filtro larguísimo no es una búsqueda: se cita recortado.
        val largo = OjosDeLaVoz.queVeo(estado, "z".repeat(300))
        assertTrue("z".repeat(OjosDeLaVoz.TOPE_DEL_FILTRO) in largo, promesa(248) + " · «$largo»")
        assertFalse("z".repeat(OjosDeLaVoz.TOPE_DEL_FILTRO + 1) in largo, promesa(248) + " · el filtro se cita entero: «$largo»")

        // Lo que la pantalla no deja leer se dice tal cual, y un formato que no se reconoce no se inventa.
        val protegida = "sin contenido accesible (pantalla vacía o protegida)"
        assertEquals(protegida, OjosDeLaVoz.queVeo(pantallaDelTurno(uiContext = protegida)), promesa(248))
        assertEquals("""{"otro":"formato"}""", OjosDeLaVoz.queVeo(pantallaDelTurno(uiContext = """{"otro":"formato"}""")), promesa(248))
        assertEquals(OjosDeLaVoz.SIN_PANTALLA, OjosDeLaVoz.queVeo(null), promesa(248))
        assertEquals(0, OjosDeLaVoz.etiquetas(null), promesa(248))
    }

    @Test
    fun promesa249() = corre {
        val mano = Contrato003FrenoYPuerta.Mano()
        val aprendidas = listOf(LearnedTool("calc", "la calculadora", listOf("5", "+")))
        val mcp = Mcp(mano.gestos, mano.sistema, aprendidas, mano.reproductor)
        val catalogo = CatalogoDeVoz.capacidades(mcp.tools)

        for (t in mcp.tools) assertTrue(t.name in catalogo, promesa(249) + " · «${t.name}» no está en lo que lee el delegado")
        for (via in mcp.tools.map { it.via }.distinct()) assertTrue(via in catalogo, promesa(249) + " · sin agrupar por «$via»")
        assertTrue("calc" in catalogo, promesa(249) + " · una acción nueva (aprendida) no aparece sola")
        assertEquals(emptyList(), mano.entradas, promesa(249) + " · armar el catálogo tocó el teléfono: ${mano.entradas}")

        // La descripción de `check_simit_fines` son ~1500 caracteres, y encima 40 acciones más: el mensaje sigue cabiendo.
        val gordo = Mcp(
            mano.gestos, mano.sistema,
            aprendidas + (1..40).map { LearnedTool("larga$it", "x".repeat(400), listOf("a")) },
            mano.reproductor,
        )
        val texto = CatalogoDeVoz.capacidades(gordo.tools)
        assertTrue("check_simit_fines" in texto, promesa(249))
        assertTrue(texto.length <= CatalogoDeVoz.TOPE_DEL_CATALOGO, promesa(249) + " · ${texto.length} caracteres")
        val mensaje = ProtocoloGptLive().resultados(listOf(Resultado("call_1", texto))).single()
        assertTrue(bytesUtf8(mensaje) <= TOPE_DE_UN_RESULTADO, promesa(249) + " · ${bytesUtf8(mensaje)} bytes")
    }

    @Test
    fun promesa250() = corre {
        val mano = Contrato003FrenoYPuerta.Mano()
        val mcp = Mcp(mano.gestos, mano.sistema, emptyList(), mano.reproductor)
        val ojos = HerramientasDeVoz(pantalla = { pantallaDelTurno() }, acciones = { mcp.tools }, mirarEn = despachadorDeIo())

        for (nombre in listOf("pulsar", "escribir", "launch_app", "go_home", "abrir_app", "hazme_un_cafe")) {
            assertEquals(
                CatalogoDeVoz.TODAVIA_NO,
                ojos.ejecutar(Llamada("call_x", nombre, mapOf("que" to "Enviar"))),
                promesa(250) + " · «$nombre»",
            )
        }
        for (u in CatalogoDeVoz.UTENSILIOS) {
            val r = ojos.ejecutar(Llamada("call_${u.nombre}", u.nombre, emptyMap()))
            assertNotEquals(CatalogoDeVoz.TODAVIA_NO, r, promesa(250) + " · «${u.nombre}» tenía que contestar lo que ve")
            assertTrue(r.isNotBlank(), promesa(250) + " · «${u.nombre}» no contestó nada")
            assertFalse(CatalogoDeVoz.actuaEnPantalla(u.nombre), promesa(250) + " · «${u.nombre}» se declara actuando en la pantalla")
        }
        assertEquals(emptyList(), mano.entradas, promesa(250) + " · algo llegó al teléfono: ${mano.entradas}")
        assertEquals(0, mano.lecturas, promesa(250) + " · el estado lo da quien la construye, no unas manos propias")
    }

    @Test
    fun promesa251() = corre {
        val bytesDeLaSesion = 32_768
        // LAS INSTRUCCIONES DE VERDAD, las que viajan desde el teléfono. Con dos de juguete la medida era de otra cosa:
        // decía 1 198 B de una apertura que de verdad ocupa el doble, y el margen que se creía tener no era el que hay.
        val apertura = p.apertura(PersonaDeLaVoz.INSTRUCCIONES_VOZ, PersonaDeLaVoz.INSTRUCCIONES_DELEGADO, CatalogoDeVoz.UTENSILIOS)
        val m = json(apertura)
        assertEquals(setOf("model", "instructions", "audio", "delegation"), m["session"]?.jsonObject?.keys, promesa(251) + " · la sesión de la voz no lleva herramientas")
        val tools = assertIs<JsonArray>(m.en("session", "delegation", "responses", "tools"), promesa(251))
        assertEquals(
            listOf(CatalogoDeVoz.DONDE_ESTOY, CatalogoDeVoz.QUE_VEO, CatalogoDeVoz.QUE_PUEDO_HACER),
            tools.map { it.texto("name") },
            promesa(251),
        )
        val bytes = bytesUtf8(apertura)
        assertTrue(bytes < bytesDeLaSesion, promesa(251) + " · la apertura ocupa $bytes B de los $bytesDeLaSesion de la sesión")
        println("251: la apertura con el catálogo ocupa $bytes bytes y declara ${tools.size} herramientas")

        // Declararlas no gasta items: los 128 son del historial, y la conversación empieza en cero.
        val v = Voz(utensilios = CatalogoDeVoz.UTENSILIOS)
        v.guion(
            llega(sesionAbierta),
            hace { assertEquals(0, v.conv.itemsEnSesion, promesa(251) + " · declarar el catálogo gastó items de la sesión") },
            llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(1, v.tipos().count { it == "session.start" }, promesa(251) + " · ${v.tipos()}")
        assertEquals(3, assertIs<JsonArray>(v.enviados().first().en("session", "delegation", "responses", "tools"), promesa(251)).size, promesa(251))
    }

    @Test
    fun promesa252() = corre {
        val soltar = CompletableDeferred<String>()
        val v = Voz(
            ejecutor = { if (it.id == "call_lento") soltar.await() else "hecho: ${it.nombre}" },
            actuaEnPantalla = CatalogoDeVoz::actuaEnPantalla,
            utensilios = CatalogoDeVoz.UTENSILIOS,
        )
        v.guion(
            llega(sesionAbierta),
            llega(usuario("¿qué ves?")),
            llega(pide("call_lento", CatalogoDeVoz.QUE_VEO)),
            llega(pide("call_donde", CatalogoDeVoz.DONDE_ESTOY)),
            hace { v.conv.oirMicrofono(vozDeLaSala()) },
            llega(sinHechos),
            hace {
                assertEquals(listOf("call_lento", "call_donde"), v.ejecutadas, promesa(252) + " · la segunda lectura esperó detrás de la retenida")
                assertEquals(listOf<String?>("call_donde"), v.salidas().map { it.texto("item", "call_id") }, promesa(252) + " · y se contestó sin esperarla")
                assertEquals(1, v.audios().size, promesa(252) + " · el micrófono se quedó esperando a la lectura")
                assertEquals(0, v.cuenta("response.create"), promesa(252) + " · con una lectura sin contestar no se pide respuesta")
            },
            hace { soltar.complete("veo la cámara") },
            llega(sinHechos),
            hace { v.reloj.ms += 2000 },
            llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(listOf<String?>("call_donde", "call_lento"), v.salidas().map { it.texto("item", "call_id") }, promesa(252))
        assertEquals(1, v.cuenta("response.create"), promesa(252) + " · ${v.tipos()}")
        assertEquals("response.create", v.tipos().last(), promesa(252))
        assertEquals(1, v.enLog("usuario dijo: ¿qué ves?"), promesa(252) + " · el turno no cerró: ${v.log}")
    }

    @Test
    fun promesa253() = corre {
        val etiquetas = listOf("Zorbax", "Qwyk", "3001234567")
        val secretos = etiquetas + listOf("WhatsApp", "Cámara")
        val mano = Contrato003FrenoYPuerta.Mano()
        val mcp = Mcp(mano.gestos, mano.sistema, emptyList(), mano.reproductor)
        val lineas = mutableListOf<Pair<String, String>>()
        val ojos = HerramientasDeVoz(
            pantalla = { pantallaDelTurno(uiContext = uiContextComoElDelTurno(etiquetas = etiquetas, enfocado = "Zorbax")) },
            acciones = { mcp.tools },
            mirarEn = despachadorDeIo(),
            log = { tag, mensaje -> lineas += tag to mensaje },
        )
        ojos.ejecutar(Llamada("call_1", CatalogoDeVoz.DONDE_ESTOY, emptyMap()))
        ojos.ejecutar(Llamada("call_2", CatalogoDeVoz.QUE_VEO, mapOf(CatalogoDeVoz.FILTRO to "Qwyk")))
        ojos.ejecutar(Llamada("call_3", CatalogoDeVoz.QUE_PUEDO_HACER, emptyMap()))
        ojos.ejecutar(Llamada("call_4", "pulsar", mapOf("que" to "Zorbax")))
        assertEquals(4, lineas.size, promesa(253) + " · cada llamada deja su medida y una sola: $lineas")

        for ((tag, mensaje) in lineas) {
            assertTrue(TelemetriaDeVoz.esDeLaVoz(tag), promesa(253) + " · «$tag» no pasa por el filtro de la voz")
            for (secreto in secretos) assertFalse(secreto in mensaje, promesa(253) + " · «$secreto» en el log: «$mensaje»")
        }

        // La medida sobrevive el viaje entero: el filtro de la voz y, detrás, la puerta de la telemetría (spec 005).
        val remoto = lineas.mapNotNull { (tag, mensaje) -> TelemetriaDeVoz.paraRemoto(tag, mensaje)?.let { PuertaDeTelemetria.mensaje(it) } }
        assertEquals(4, remoto.size, promesa(253) + " · $remoto")
        assertTrue(remoto.any { "3 etiquetas" in it }, promesa(253) + " · la medida se perdió por el camino: $remoto")
        assertTrue(remoto.any { CatalogoDeVoz.QUE_VEO in it }, promesa(253) + " · qué se miró se pierde: $remoto")
        for (linea in remoto) {
            for (secreto in secretos) assertFalse(secreto in linea, promesa(253) + " · «$secreto» sale del teléfono en «$linea»")
        }
    }

    /* ---------- Los arreglos del control de la 2B2a ---------- */

    /**
     * LO QUE ESPERA LA LECTURA TRABADA A QUE LA SUELTEN. Quien la suelta es un paso del guion, y los pasos del guion
     * corren en el HILO DE LA CONVERSACIÓN: si la lectura volviera a ese hilo, el paso no llegaría nunca y la espera
     * vencería sola. Por eso es un tope y no una espera infinita: el rojo tiene que ser rojo, no un contrato colgado.
     */
    private val topeDeLaTraba = 5_000L

    @Test
    fun promesa254() = corre {
        val mano = Contrato003FrenoYPuerta.Mano()
        val mcp = Mcp(mano.gestos, mano.sistema, emptyList(), mano.reproductor)
        val traba = Traba()
        val soltada = mutableListOf<Boolean>()
        val ojos = HerramientasDeVoz(
            // BLOQUEA EL HILO, no suspende la corrutina: es lo único que separa un despachador propio de uno compartido.
            // Con un `CompletableDeferred` (lo que hace la 252) el hilo se suelta y la conversación sigue igual.
            pantalla = { soltada += traba.esperaBloqueando(topeDeLaTraba); pantallaDelTurno() },
            acciones = { mcp.tools },
            mirarEn = despachadorDeIo(),
        )
        val v = Voz(ejecutor = ojos::ejecutar, actuaEnPantalla = CatalogoDeVoz::actuaEnPantalla, utensilios = CatalogoDeVoz.UTENSILIOS)
        v.guion(
            llega(sesionAbierta),
            llega(usuario("¿qué ves?")),
            llega(pide("call_mira", CatalogoDeVoz.QUE_VEO)),
            // No mira la pantalla, así que no se traba: es la prueba de que la conversación sigue atendiendo.
            llega(pide("call_catalogo", CatalogoDeVoz.QUE_PUEDO_HACER)),
            hace { v.conv.oirMicrofono(vozDeLaSala()) },
            llega(sinHechos),
            hace {
                // TODO ESTO OCURRE CON LA LECTURA TRABADA, y este paso corre en el hilo de la conversación.
                assertEquals(1, v.audios().size, promesa(254) + " · el micrófono se quedó esperando a la lectura")
                assertEquals(
                    listOf<String?>("call_catalogo"),
                    v.salidas().map { it.texto("item", "call_id") },
                    promesa(254) + " · la otra llamada hizo cola detrás de la lectura trabada",
                )
                assertEquals(0, v.cuenta("response.create"), promesa(254) + " · con una lectura sin contestar no se pide respuesta")
                traba.abrir()
            },
            llega(sinHechos),
            hace { v.reloj.ms += 2000 },
            llega(sinHechos),
        )
        v.conv.conversar()
        assertEquals(
            listOf(true),
            soltada,
            promesa(254) + " · la lectura estuvo $topeDeLaTraba ms trabada sin que nadie la abriera: corrió en el hilo de la conversación, que es justo quien tenía que abrirla",
        )
        assertEquals(listOf<String?>("call_catalogo", "call_mira"), v.salidas().map { it.texto("item", "call_id") }, promesa(254))
        assertEquals(1, v.cuenta("response.create"), promesa(254) + " · ${v.tipos()}")
        assertEquals(1, v.enLog("usuario dijo: ¿qué ves?"), promesa(254) + " · el turno no cerró: ${v.log}")

        // EL TOPE ES EL DE LA CLASE, no uno inyectado por la prueba: una lectura que no vuelve se contesta igual.
        val nunca = Traba()
        val lineas = mutableListOf<String>()
        val lentos = HerramientasDeVoz(
            pantalla = { nunca.esperaBloqueando(HerramientasDeVoz.TOPE_DE_LA_MIRADA_MS + 1_000); pantallaDelTurno() },
            acciones = { nunca.esperaBloqueando(HerramientasDeVoz.TOPE_DE_LA_MIRADA_MS + 1_000); mcp.tools },
            mirarEn = despachadorDeIo(),
            log = { tag, mensaje -> lineas += "$tag: $mensaje" },
        )
        for (nombre in listOf(CatalogoDeVoz.DONDE_ESTOY, CatalogoDeVoz.QUE_VEO)) {
            assertEquals(
                OjosDeLaVoz.NO_PUDE_MIRAR,
                lentos.ejecutar(Llamada("call_lento", nombre, emptyMap())),
                promesa(254) + " · «$nombre» dejó muda a la voz en vez de decir que no pudo mirar",
            )
        }
        nunca.abrir()
        assertEquals(2, lineas.count { "${HerramientasDeVoz.TOPE_DE_LA_MIRADA_MS} ms" in it }, promesa(254) + " · sin medida de lo que se esperó: $lineas")
        // Y la medida sale del teléfono como medida: la puerta de la telemetría la deja pasar entera (spec 005).
        for (linea in lineas) {
            val remoto = TelemetriaDeVoz.paraRemoto("voz-ojos", linea.substringAfter(": "))?.let { PuertaDeTelemetria.mensaje(it) }
            assertNotNull(remoto, promesa(254))
            assertTrue("${HerramientasDeVoz.TOPE_DE_LA_MIRADA_MS} ms" in remoto, promesa(254) + " · la medida no sobrevive el viaje: «$remoto»")
        }
    }

    @Test
    fun promesa255() = corre {
        // UNA SOLA RESPUESTA NO SE GASTA LA SESIÓN: el catálogo cabe varias veces en el presupuesto entero.
        assertTrue(
            CatalogoDeVoz.TOPE_DEL_CATALOGO * 4 <= ConversacionViva.TOPE_DE_BYTES,
            promesa(255) + " · el catálogo puede ocupar ${CatalogoDeVoz.TOPE_DEL_CATALOGO} de los ${ConversacionViva.TOPE_DE_BYTES} de la sesión",
        )
        val mano = Contrato003FrenoYPuerta.Mano()
        val gordo = Mcp(
            mano.gestos, mano.sistema,
            (1..40).map { LearnedTool("larga$it", "x".repeat(400), listOf("a")) },
            mano.reproductor,
        )
        assertTrue(
            bytesUtf8(CatalogoDeVoz.capacidades(gordo.tools)) <= CatalogoDeVoz.TOPE_DEL_CATALOGO,
            promesa(255) + " · ${bytesUtf8(CatalogoDeVoz.capacidades(gordo.tools))} bytes de catálogo",
        )

        // LA CUENTA DE LA SESIÓN: lo que se manda y lo que pide el delegado, contado en bytes.
        val v = Voz(ejecutor = { "x".repeat(31_000) })
        val aviso = "de los ${ConversacionViva.TOPE_DE_BYTES}"
        v.guion(
            llega(sesionAbierta),
            hace { assertEquals(0, v.conv.bytesEnSesion, promesa(255) + " · la conversación no empieza en cero bytes") },
            llega(pide("call_1", "pulsar")),
            llega(sinHechos),
            hace {
                assertTrue(
                    v.conv.bytesEnSesion >= ConversacionViva.AVISO_DE_BYTES,
                    promesa(255) + " · un resultado de 31 000 caracteres dejó la sesión en ${v.conv.bytesEnSesion} bytes",
                )
                assertEquals(1, v.enLog(aviso), promesa(255) + " · se cruzó el aviso de bytes sin decirlo: ${v.log}")
            },
            llega(pide("call_2", "pulsar")),
            llega(sinHechos),
            hace {
                assertEquals(1, v.enLog(aviso), promesa(255) + " · el aviso de bytes se repite: ${v.log}")
                assertTrue(v.salidas().size >= 2, promesa(255) + " · pasado el aviso se dejó de contestar: no se corta nada")
            },
            corte(),
            llega(sesionAbierta),
            hace {
                assertTrue(
                    v.conv.bytesEnSesion < ConversacionViva.AVISO_DE_BYTES,
                    promesa(255) + " · la conexión nueva heredó los ${v.conv.bytesEnSesion} bytes de la anterior: su sesión es otra",
                )
            },
        )
        v.conv.conversar()
    }

    @Test
    fun promesa257() {
        // EN ORIGEN: ni saltos de línea, ni el separador con que se unen, ni más de lo que cabe.
        assertEquals("Mensaje nuevo", etiquetaDePantalla("Mensaje\nnuevo"), promesa(257))
        assertEquals("Mensaje nuevo", etiquetaDePantalla("Mensaje\r\n\tnuevo"), promesa(257))
        assertEquals("Enviar - audio", etiquetaDePantalla("Enviar · audio"), promesa(257) + " · el separador sigue partiendo la etiqueta")
        assertEquals("a·b", etiquetaDePantalla("a·b"), promesa(257) + " · un punto medio sin espacios no parte nada y no se toca")
        assertEquals(TOPE_DE_UNA_ETIQUETA, etiquetaDePantalla("z".repeat(80)).length, promesa(257))
        assertEquals("", etiquetaDePantalla("  \n  "), promesa(257))

        // Y QUIEN LAS LEE ES TOLERANTE: aunque llegue sin sanear, ni se infla la cuenta ni se pierde lo que está.
        val sucio = pantallaDelTurno(uiContext = uiContextComoElDelTurno(etiquetas = listOf("Enviar\naudio", "Buscar")))
        assertEquals(2, OjosDeLaVoz.etiquetas(sucio), promesa(257) + " · una etiqueta con salto de línea partió el resumen")
        val visto = OjosDeLaVoz.queVeo(sucio, "Buscar")
        assertTrue("«Buscar»: sí" in visto, promesa(257) + " · dice que no ve algo que está: «$visto»")

        // EL CAMPO ENFOCADO SALE ENTERO aunque su texto traiga la comilla y el paréntesis con que se cierra.
        val raro = pantallaDelTurno(uiContext = uiContextComoElDelTurno(enfocado = "dijo \") y siguió"))
        assertTrue("dijo \") y siguió" in OjosDeLaVoz.queVeo(raro), promesa(257) + " · el enfocado se truncó: «${OjosDeLaVoz.queVeo(raro)}»")
    }

    @Test
    fun promesa258() = corre {
        val sinServicio = HerramientasDeVoz(pantalla = { null }, acciones = { null }, mirarEn = despachadorDeIo())
        val respuestas = CatalogoDeVoz.UTENSILIOS.associate { u ->
            u.nombre to sinServicio.ejecutar(Llamada("call_${u.nombre}", u.nombre, emptyMap()))
        }
        for ((nombre, r) in respuestas) {
            assertTrue(OjosDeLaVoz.SIN_SERVICIO in r, promesa(258) + " · «$nombre» calla la causa: «$r»")
        }
        assertEquals(OjosDeLaVoz.SIN_PANTALLA, respuestas.getValue(CatalogoDeVoz.DONDE_ESTOY), promesa(258))
        assertEquals(OjosDeLaVoz.SIN_PANTALLA, respuestas.getValue(CatalogoDeVoz.QUE_VEO), promesa(258))
        assertEquals(CatalogoDeVoz.SIN_CATALOGO, respuestas.getValue(CatalogoDeVoz.QUE_PUEDO_HACER), promesa(258))

        // CON SERVICIO Y SIN NINGUNA ACCIÓN NO ES LO MISMO, y no se puede contestar como si lo fuera.
        val vacio = HerramientasDeVoz(pantalla = { pantallaDelTurno() }, acciones = { emptyList() }, mirarEn = despachadorDeIo())
        val r = vacio.ejecutar(Llamada("call_x", CatalogoDeVoz.QUE_PUEDO_HACER, emptyMap()))
        assertFalse(OjosDeLaVoz.SIN_SERVICIO in r, promesa(258) + " · un catálogo vacío no es un servicio apagado: «$r»")
    }
}
