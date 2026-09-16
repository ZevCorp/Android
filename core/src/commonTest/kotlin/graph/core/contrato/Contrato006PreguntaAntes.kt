package graph.core.contrato

import graph.core.contrato.Contrato003FrenoYPuerta.Mano
import graph.core.contrato.Contrato003FrenoYPuerta.Voz
import graph.core.domain.AgentAction
import graph.core.domain.Brain
import graph.core.domain.BrainTurn
import graph.core.domain.GraphLog
import graph.core.domain.LearnedTool
import graph.core.domain.Phone
import graph.core.domain.ScreenState
import graph.core.domain.UserChannel
import graph.core.precision.ArmadoDeEjecucion
import graph.core.precision.Freno
import graph.core.precision.Paraste
import graph.core.pregunta.CompuertaDePregunta
import graph.core.telemetria.PuertaDeTelemetria
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * CONTRATO 006 — PREGUNTA ANTES DE EJECUTAR (docs/specs/006-pregunta-antes-de-ejecutar.md).
 *
 * Cada `promesaNNN` es una fila de la tabla de la spec, con el enunciado literal en [PROMESAS]. Se escribieron ANTES que el
 * código que juzgan: nacieron rojas contra un esqueleto que deja pasar todo.
 *
 * Nada toca Android, red ni disco: el teléfono, los gestos y el sistema son los falsos de la 003 ([Mano]), el cerebro es un
 * guion que además graba los `results` que le llegan, y el canal de preguntar es un falso que anota cada pregunta. Todo pasa
 * por el [ArmadoDeEjecucion] de verdad: si la compuerta no está cableada ahí, estas promesas no se ponen verdes.
 */
class Contrato006PreguntaAntes {

    companion object {
        val PROMESAS = mapOf(
            601 to "Una acción sensible que el pedido no autorizó no se ejecuta: el cliente pregunta primero y nada llega al teléfono; si el pedido ya la pidió, con ese destinatario y ese contenido, se ejecuta sin preguntar.",
            602 to "Un destino ambiguo se pregunta ofreciendo las opciones que el cliente vio —las etiquetas de la pantalla o las apps instaladas—, nunca una lista inventada; con un solo candidato, o con uno que es igual a lo pedido, no pregunta.",
            603 to "Un dato que falta se pide, uno por pregunta y el más importante primero; una hora que falta no cae en el default de las 8, y lo que la acción ya trae no se pregunta.",
            604 to "Mientras espera la respuesta no se ejecuta nada ni se pide otro turno: la corrida queda viva y quieta, ningún tope ni plazo la resuelve actuando por su cuenta, y el alto de siempre la para.",
            605 to "La respuesta continúa la MISMA corrida: vuelve al cerebro como resultado de la acción frenada, en el mismo hilo y sin abrir otro, y sin repetir la pregunta ya hecha.",
            606 to "No se pregunta dos veces lo mismo en la misma corrida: contestado que sí, la misma acción pasa sin preguntar; contestado que no, o sin contestar, no se ejecuta ni se vuelve a preguntar.",
            607 to "Sin canal para preguntar, una acción sensible no se ejecuta: el cerebro se entera por el resultado y la corrida sigue.",
            608 to "De una pregunta del cliente solo sale la medida: su clase y los largos. Ni el texto de la pregunta, ni la respuesta, ni el destinatario ni las opciones salen al log, y lo que sale pasa entero la puerta de la telemetría.",
        )

        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /* Lo que la persona pide, y lo que el cerebro decide hacer con ello. Las palabras están elegidas para que ningún
           trozo de cuatro sea parte de una palabra del propio log («pregunta», «caracteres», «corrida», «abierta»…): un
           juez de fugas que da rojo con el log limpio no juzga nada (la lección de la 317). */
        const val MIRA = "mira el chat de Zorbax"
        const val MANDA = "mándale a Ana que llego tarde"
        const val NUMERO = "3104459821"
        const val OTRO_NUMERO = "3209988776"
        const val LLEGO_TARDE = "Llego tarde"
        const val KINVARA = "Kinvara al mediodía"
        const val CORREO = "qwyk@buzon.co"

        /** Cómo la pantalla lista lo que se ve (`GraphAccessibilityService.uiContext`). */
        fun pantallaCon(vararg etiquetas: String) =
            "paquete: com.x\ntipo: aplicación\nclickeables: 9 · campos de texto: 1\netiquetas visibles: " + etiquetas.joinToString(" · ")

        /* Lo que ayuda a juzgar una fuga: los trozos de un dato y las marcas más cortas que un trozo. */

        val PALABRA = Regex("""[\p{L}\p{N}]+""")
        private val SIN_TILDE = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n')
        fun plano(s: String) = s.lowercase().map { SIN_TILDE[it] ?: it }.joinToString("")

        /** Lo que delata aunque sea más corto que un trozo de cuatro, como palabra entera. */
        val MARCAS = listOf("ana", "zorbax", "qwyk", "nequi", "kinvara", "3104459821")
            .map { Regex("""(?<![\p{L}\p{N}])$it(?![\p{L}\p{N}])""") }

        /**
         * Una corrida de fuera con el armado de verdad: el guion de turnos, el canal falso y, si hace falta, lo que el
         * cliente ve (la pantalla y el catálogo de apps). El reloj del freno es de prueba: las esperas del motor no duermen.
         */
        suspend fun corrida(
            pedido: String,
            vararg turnos: BrainTurn,
            canal: Canal = Canal("no"),
            conCanal: Boolean = true,
            apps: List<String> = emptyList(),
            pantalla: String = "",
        ): Escena {
            val diario = Diario()
            val mano = Mano()
            val cerebro = Cerebro(*turnos)
            val conPantalla = object : Phone by mano.telefono {
                override suspend fun state(withScreenshot: Boolean) = ScreenState("com.x · X", pantalla, 1080, 2400)
            }
            val reloj = TestTimeSource()
            val armado = ArmadoDeEjecucion(Freno(log = diario, reloj = reloj, espera = { reloj += it.milliseconds }), diario)
            val sesion = armado.arma(
                ArmadoDeEjecucion.Manos(conPantalla, mano.gestos, mano.sistema, mano.reproductor),
                { cerebro }, Voz(), usuario = if (conCanal) canal else null, pausa = { 0 },
                aprendidas = listOf(LearnedTool("contactos", "abre un contacto", listOf("Zorbax Qwyk"))),
                apps = { apps },
            )
            val salida = runCatching { armado.correr(pedido) { sesion.motor.run(pedido) } }
            return Escena(mano, canal, cerebro, diario, salida)
        }
    }

    /* ---------- El mapa a mano ---------- */

    /** El log de la corrida: «tag: mensaje» como la spec lo cita, y el par suelto para pasarlo por la puerta de la 005. */
    class Diario : GraphLog {
        val lineas = mutableListOf<String>()
        val pares = mutableListOf<Pair<String, String>>()
        override fun log(tag: String, message: String) {
            lineas += "$tag: $message"
            pares += tag to message
        }
    }

    /** El canal que YA existe (`UserChannel`), falso: anota cada pregunta y contesta lo que le pusieron, en orden. */
    class Canal(vararg respuestas: String) : UserChannel {
        private val respuestas = respuestas.toList()
        val preguntas = mutableListOf<String>()
        override suspend fun ask(question: String): String {
            preguntas += question
            return respuestas.getOrElse(preguntas.size - 1) { respuestas.lastOrNull() ?: "" }
        }
    }

    /** El cerebro guionado que además graba los `results` que le llegan en cada turno (promesa 605). */
    class Cerebro(vararg guion: BrainTurn) : Brain {
        val recibidos = mutableListOf<List<String>>()
        val informado = mutableListOf<String>()
        var comenzado = 0
        private val cola = ArrayDeque(guion.toList())
        override fun begin(goal: String) { comenzado++ }
        override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
            recibidos += actionResults
            return cola.removeFirstOrNull() ?: BrainTurn(done = true, text = "guion agotado")
        }
        override fun inform(message: String) { informado += message }
    }

    class Escena(val mano: Mano, val canal: Canal, val cerebro: Cerebro, val diario: Diario, val salida: Result<String>) {
        val entradas get() = mano.entradas
        val preguntas get() = canal.preguntas
        /** Los `results` del turno [turno], tal como los recibió el turno siguiente. */
        fun resultados(turno: Int): List<String> = cerebro.recibidos.getOrElse(turno) { emptyList() }
        fun preguntasDeClase(clase: String) = diario.lineas.count { it.startsWith("pregunta: ❓ $clase") }
    }

    private fun sms(numero: String = NUMERO, texto: String = LLEGO_TARDE) =
        AgentAction.Mcp("send_sms", mapOf("number" to numero, "message" to texto))

    private fun fin() = BrainTurn(done = true, text = "fin")

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa601() = corre {
        val p = 601
        // Lo sensible que el pedido no pidió: ni un mensaje, ni un correo, ni una llamada, ni compartir, ni tocar «Enviar».
        val frenada = corrida(
            MIRA,
            BrainTurn(
                actions = listOf(
                    sms(),
                    AgentAction.Mcp("send_email", mapOf("to" to CORREO, "body" to KINVARA)),
                    AgentAction.Mcp("call", mapOf("number" to NUMERO)),
                    AgentAction.Mcp("share_text", mapOf("text" to KINVARA)),
                    AgentAction.Mcp("contactos", mapOf("taps" to "Enviar")),
                ),
            ),
            fin(),
            canal = Canal("no", "no", "no", "no", "no"),
        )
        assertEquals(emptyList(), frenada.entradas, promesa(p) + " · algo llegó al teléfono sin que el pedido lo autorizara")
        assertEquals(5, frenada.preguntas.size, promesa(p) + " · no preguntó por cada una: ${frenada.preguntas}")
        assertEquals(5, frenada.preguntasDeClase("permiso"), promesa(p) + " · ${frenada.diario.lineas}")
        assertEquals(5, frenada.resultados(1).count { it.startsWith(CompuertaDePregunta.PREGUNTE) },
            promesa(p) + " · el cerebro no se enteró: ${frenada.resultados(1)}")

        // Lo que el pedido SÍ pidió, con ese destinatario y ese contenido: se hace, y no se pregunta nada.
        val pedida = corrida(MANDA, BrainTurn(actions = listOf(sms())), fin())
        assertEquals(listOf("sendSms"), pedida.entradas, promesa(p) + " · no hizo lo que le pidieron: ${pedida.diario.lineas}")
        assertEquals(emptyList(), pedida.preguntas, promesa(p) + " · preguntó por lo que ya le habían pedido")

        // El mismo pedido no autoriza otro destinatario, ni otro contenido, ni otra clase de acción.
        for ((que, accion) in listOf(
            "otro destinatario" to AgentAction.Mcp("send_email", mapOf("to" to CORREO, "body" to LLEGO_TARDE)),
            "otro contenido" to sms(texto = KINVARA),
            "otra clase" to AgentAction.Mcp("call", mapOf("number" to NUMERO)),
        )) {
            val otra = corrida(MANDA, BrainTurn(actions = listOf(accion)), fin())
            assertEquals(emptyList(), otra.entradas, promesa(p) + " · $que pasó como si el pedido lo autorizara")
            assertEquals(1, otra.preguntas.size, promesa(p) + " · $que no se preguntó: ${otra.preguntas}")
        }

        // Lo que no es sensible pasa como siempre: marcar sin llamar (no llama), mirar, navegar.
        val normal = corrida(
            MIRA,
            BrainTurn(
                actions = listOf(
                    AgentAction.Mcp("dial", mapOf("number" to NUMERO)),
                    AgentAction.Tap(10, 20),
                    AgentAction.Scroll(true),
                    AgentAction.Mcp("go_home", emptyMap()),
                ),
            ),
            fin(),
        )
        assertEquals(listOf("dial", "tap", "scroll", "home"), normal.entradas, promesa(p) + " · frenó lo que no es sensible")
        assertEquals(emptyList(), normal.preguntas, promesa(p) + " · preguntó por lo que no es sensible")
    }

    @Test
    fun promesa603() = corre {
        val p = 603
        // La hora que falta no cae en el default de las 8: se pregunta y la alarma no se pone.
        val sinHora = corrida("pon una alarma", BrainTurn(actions = listOf(AgentAction.Mcp("set_alarm", emptyMap()))), fin(), canal = Canal("a las nueve"))
        assertEquals(emptyList(), sinHora.entradas, promesa(p) + " · la alarma de las 8 llegó al teléfono")
        assertEquals(1, sinHora.preguntas.size, promesa(p) + " · ${sinHora.preguntas}")
        assertTrue("hora" in sinHora.preguntas.single().lowercase(), promesa(p) + " · no preguntó por la hora: ${sinHora.preguntas}")
        assertEquals(1, sinHora.preguntasDeClase("dato"), promesa(p) + " · ${sinHora.diario.lineas}")

        // Lo que la acción ya trae no se pregunta.
        val conHora = corrida("pon una alarma a las 7", BrainTurn(actions = listOf(AgentAction.Mcp("set_alarm", mapOf("hour" to "7")))), fin())
        assertEquals(listOf("setAlarm"), conHora.entradas, promesa(p) + " · no puso la alarma que ya traía la hora")
        assertEquals(emptyList(), conHora.preguntas, promesa(p) + " · preguntó por un dato que ya venía")

        // Faltan dos: UNA sola pregunta, y la más importante primero (cuándo antes que cómo se llama).
        val evento = corrida("agéndame algo", BrainTurn(actions = listOf(AgentAction.Mcp("create_event", emptyMap()))), fin(), canal = Canal("mañana"))
        assertEquals(1, evento.preguntas.size, promesa(p) + " · preguntó por más de un dato a la vez: ${evento.preguntas}")
        assertTrue("cuándo" in evento.preguntas.single().lowercase(), promesa(p) + " · la primera no fue la más importante: ${evento.preguntas}")
        val conCuando = corrida(
            "agéndame algo", BrainTurn(actions = listOf(AgentAction.Mcp("create_event", mapOf("start" to "2026-09-17T09:00")))), fin(), canal = Canal("cita"),
        )
        assertTrue("título" in conCuando.preguntas.single().lowercase(), promesa(p) + " · con el cuándo puesto toca el título: ${conCuando.preguntas}")

        // Cada herramienta pide lo suyo, y mientras tanto no toca el teléfono.
        for ((accion, palabra) in listOf(
            AgentAction.Mcp("set_timer", emptyMap()) to "tiempo",
            sms(numero = "") to "quién",
            AgentAction.Mcp("web_search", emptyMap()) to "busco",
            AgentAction.Mcp("directions", emptyMap()) to "dónde",
            AgentAction.Mcp("open_url", emptyMap()) to "página",
            AgentAction.Mcp("launch_app", emptyMap()) to "app",
        )) {
            val escena = corrida(MANDA, BrainTurn(actions = listOf(accion)), fin(), canal = Canal("ya"))
            assertEquals(emptyList(), escena.entradas, promesa(p) + " · «${accion.tool}» se ejecutó sin el dato que le falta")
            assertEquals(1, escena.preguntas.size, promesa(p) + " · «${accion.tool}»: ${escena.preguntas}")
            assertTrue(palabra in escena.preguntas.single().lowercase(), promesa(p) + " · «${accion.tool}» no pidió «$palabra»: ${escena.preguntas}")
        }
    }

    @Test
    fun promesa604() = corre {
        val p = 604
        coroutineScope {
            val espera = CompletableDeferred<String>()
            val canal = object : UserChannel {
                val preguntas = mutableListOf<String>()
                override suspend fun ask(question: String): String {
                    preguntas += question
                    return espera.await()
                }
            }
            val diario = Diario()
            val mano = Mano()
            val freno = Freno(log = diario)
            val armado = ArmadoDeEjecucion(freno, diario, lanza = { corte -> launch { corte() } }, graciaMs = 50)
            val cerebro = Cerebro(
                BrainTurn(actions = listOf(sms(), AgentAction.Tap(1, 1))),
                fin(),
            )
            val sesion = armado.arma(
                ArmadoDeEjecucion.Manos(mano.telefono, mano.gestos, mano.sistema, mano.reproductor),
                { cerebro }, Voz(), usuario = canal, maxTurnos = 1, pausa = { 0 },
            )
            var salida: Result<String>? = null
            val trabajo = launch { salida = runCatching { armado.correr(MIRA) { sesion.motor.run(MIRA) } } }
            // Con tope: una compuerta que no pregunta deja la prueba colgada, y colgada no dice qué falló.
            assertEquals(true, withTimeoutOrNull(2_000) { while (canal.preguntas.isEmpty()) yield(); true },
                promesa(p) + " · no preguntó antes de ejecutar la acción sensible")

            // Viva y quieta: ni el tope de turnos ni el paso del tiempo la resuelven actuando por su cuenta.
            delay(300)
            assertTrue(trabajo.isActive, promesa(p) + " · la corrida se resolvió sola mientras esperaba")
            assertEquals(emptyList(), mano.entradas, promesa(p) + " · tocó el teléfono mientras esperaba")
            assertEquals(1, cerebro.recibidos.size, promesa(p) + " · pidió otro turno mientras esperaba")
            assertEquals(1, canal.preguntas.size, promesa(p) + " · preguntó más de una vez: ${canal.preguntas}")

            // El alto de siempre la para, aunque esté esperando una respuesta que nunca llega.
            armado.parar("píldora")
            assertEquals(true, withTimeoutOrNull(3_000) { trabajo.join(); true }, promesa(p) + " · el alto no soltó la corrida que esperaba")
            assertIs<Paraste>(salida?.exceptionOrNull(), promesa(p) + " · el alto no paró la corrida que esperaba: $salida")
            assertEquals(emptyList(), mano.entradas, promesa(p) + " · tocó el teléfono al pararla")

            // Y la respuesta que llega tarde, con la corrida ya parada, no ejecuta nada.
            espera.complete("sí, mándaselo")
            repeat(5) { yield() }
            assertEquals(emptyList(), mano.entradas, promesa(p) + " · la respuesta tardía ejecutó la acción frenada")
        }
    }

    @Test
    fun promesa605() = corre {
        val p = 605
        val escena = corrida(
            MIRA,
            BrainTurn(actions = listOf(sms())),
            BrainTurn(actions = listOf(sms())),
            fin(),
            canal = Canal("sí, mándaselo"),
        )
        // La respuesta vuelve al cerebro por el results de la acción frenada, en la misma corrida.
        val resultado = assertSingle(escena.resultados(1), promesa(p) + " · el turno 1 no dejó su resultado")
        assertTrue(resultado.startsWith(CompuertaDePregunta.PREGUNTE), promesa(p) + " · el resultado no dice que preguntó: $resultado")
        assertTrue("sí, mándaselo" in resultado, promesa(p) + " · la respuesta de la persona no llegó al cerebro: $resultado")
        assertTrue(escena.preguntas.single() in resultado, promesa(p) + " · el cerebro no sabe qué se preguntó: $resultado")

        // El mismo hilo: ni otra corrida, ni el carril de la pregunta de Graph, ni la pregunta repetida.
        assertEquals(1, escena.cerebro.comenzado, promesa(p) + " · abrió otro hilo para la respuesta")
        assertEquals(emptyList(), escena.cerebro.informado, promesa(p) + " · la respuesta se fue por inform, que es el carril de Graph")
        assertEquals(1, escena.preguntas.size, promesa(p) + " · repitió la pregunta ya hecha: ${escena.preguntas}")
        assertEquals(listOf("sendSms"), escena.entradas, promesa(p) + " · el turno 2 no siguió la corrida: ${escena.diario.lineas}")
    }

    @Test
    fun promesa606() = corre {
        val p = 606
        // Contestado que sí: la misma acción pasa en el turno siguiente, sin volver a preguntar.
        val si = corrida(MIRA, BrainTurn(actions = listOf(sms())), BrainTurn(actions = listOf(sms())), fin(), canal = Canal("sí, mándaselo"))
        assertEquals(listOf("sendSms"), si.entradas, promesa(p) + " · lo autorizado no se hizo")
        assertEquals(1, si.preguntas.size, promesa(p) + " · preguntó dos veces lo mismo: ${si.preguntas}")

        // Contestado que no, o sin contestar: no se ejecuta y no se vuelve a preguntar.
        for (respuesta in listOf("no", "")) {
            val negada = corrida(MIRA, BrainTurn(actions = listOf(sms())), BrainTurn(actions = listOf(sms())), fin(), canal = Canal(respuesta))
            assertEquals(emptyList(), negada.entradas, promesa(p) + " · «$respuesta» dejó pasar la acción")
            assertEquals(1, negada.preguntas.size, promesa(p) + " · «$respuesta» volvió a preguntar: ${negada.preguntas}")
            val segundo = assertSingle(negada.resultados(2), promesa(p) + " · el turno 2 no dejó resultado")
            assertTrue(segundo.startsWith(CompuertaDePregunta.DIJISTE_QUE_NO), promesa(p) + " · «$respuesta» no se recuerda: $segundo")
        }

        // Otro asunto sí se pregunta: lo ya contestado vale para ESA acción, no para cualquiera.
        val otro = corrida(MIRA, BrainTurn(actions = listOf(sms())), BrainTurn(actions = listOf(sms(numero = OTRO_NUMERO))), fin(), canal = Canal("no", "no"))
        assertEquals(2, otro.preguntas.size, promesa(p) + " · otro destinatario no se preguntó: ${otro.preguntas}")
        assertEquals(emptyList(), otro.entradas, promesa(p) + " · pasó sin permiso")
    }

    @Test
    fun promesa607() = corre {
        val p = 607
        val escena = corrida(MIRA, BrainTurn(actions = listOf(sms(), AgentAction.Tap(1, 1))), fin(), conCanal = false)
        assertEquals(listOf("tap"), escena.entradas, promesa(p) + " · mandó el mensaje sin nadie a quién preguntarle")
        val resultado = escena.resultados(1).first()
        assertTrue(resultado.startsWith(CompuertaDePregunta.SIN_CANAL), promesa(p) + " · el cerebro no se enteró: $resultado")
        assertTrue(escena.salida.isSuccess, promesa(p) + " · la corrida no siguió: ${escena.salida}")
        assertEquals(2, escena.cerebro.recibidos.size, promesa(p) + " · no pidió el turno siguiente")
    }

    @Test
    fun promesa608() = corre {
        val p = 608
        val escena = corrida(
            MIRA,
            BrainTurn(
                actions = listOf(
                    sms(texto = KINVARA),
                    AgentAction.Mcp("launch_app", mapOf("app" to "Banco")),
                    AgentAction.Mcp("set_alarm", emptyMap()),
                ),
            ),
            fin(),
            canal = Canal("no", "el de Zorbax Qwyk", "a las nueve"),
            apps = listOf("Banco Zorbax", "Banco Qwyk", "Nequi"),
            pantalla = pantallaCon("Zorbax Qwyk", "Archivar"),
        )
        assertEquals(3, escena.preguntas.size, promesa(p) + " · el juez no llegó a las tres clases: ${escena.preguntas}")

        // Ni un trozo de lo que la persona pidió, dijo o le contestaron, ni de lo que la pantalla muestra.
        val datos = listOf(MIRA, KINVARA, NUMERO, "Zorbax Qwyk", "Banco Zorbax", "Banco Qwyk", "Nequi", "el de Zorbax Qwyk", "a las nueve", "Archivar")
        val trozos = datos.flatMap { dato -> PALABRA.findAll(plano(dato)).flatMap { it.value.windowed(4) }.toList() }.toSet()
        for (linea in escena.diario.lineas) {
            val limpia = plano(linea)
            for (trozo in trozos) assertTrue(trozo !in limpia, promesa(p) + " · «$trozo» salió en «$linea»")
            for (marca in MARCAS) assertTrue(!marca.containsMatchIn(limpia), promesa(p) + " · una marca salió en «$linea»")
        }

        // Y lo que sí sale pasa ENTERO la puerta de la telemetría: la clase y los largos son medidas.
        val suyas = escena.diario.pares.filter { it.first == CompuertaDePregunta.TAG }
        assertEquals(6, suyas.size, promesa(p) + " · la compuerta no dejó su medida de cada pregunta y respuesta: $suyas")
        assertEquals(CompuertaDePregunta.TAG, PuertaDeTelemetria.tag(CompuertaDePregunta.TAG), promesa(p) + " · el tag sale como «otro»")
        for ((_, mensaje) in suyas) {
            assertEquals(mensaje, PuertaDeTelemetria.mensaje(mensaje), promesa(p) + " · la puerta de la 005 se come la medida de «$mensaje»")
        }
        assertEquals(1, escena.preguntasDeClase("permiso"), promesa(p) + " · ${escena.diario.lineas}")
        assertEquals(1, escena.preguntasDeClase("cual"), promesa(p) + " · ${escena.diario.lineas}")
        assertEquals(1, escena.preguntasDeClase("dato"), promesa(p) + " · ${escena.diario.lineas}")
    }

    /* ---------- Lo que ayuda a juzgar ---------- */

    private fun <T> assertSingle(lista: List<T>, mensaje: String): T {
        assertEquals(1, lista.size, "$mensaje · $lista")
        return lista.single()
    }
}
