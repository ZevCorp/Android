package graph.core.contrato

import graph.core.application.ExecutionEngine
import graph.core.domain.AgentAction
import graph.core.domain.Brain
import graph.core.domain.BrainTurn
import graph.core.domain.Gestures
import graph.core.domain.GraphLog
import graph.core.domain.LearnedTool
import graph.core.domain.Mcp
import graph.core.domain.Phone
import graph.core.domain.ScreenState
import graph.core.domain.SystemApi
import graph.core.domain.UiPlayer
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import graph.core.precision.Freno
import graph.core.precision.Paraste
import graph.core.precision.Puerta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * CONTRATO 003 — LO HACE A LA PRIMERA Y SE PUEDE PARAR (docs/specs/003-lo-hace-a-la-primera-y-se-puede-parar.md).
 *
 * Cada `promesaNNN` es una fila de la tabla de la spec, con el enunciado literal en [PROMESAS]. Se
 * escribieron ANTES que el código que juzgan: nacieron rojas. Ninguna toca Android, red ni disco: el
 * teléfono, los gestos, el sistema y el reproductor son falsos que graban cada entrada; el cerebro es
 * un guion que cuenta sus turnos; y el reloj del freno es un [TestTimeSource] que avanza la espera.
 *
 * La lección de U que las sostiene: su promesa 59 daba verde con un freno falso mientras en producción
 * nadie armaba el freno. Aquí se juzga el freno de verdad, detrás de la puerta de verdad y, en la 303,
 * dentro del motor de verdad.
 */
class Contrato003FrenoYPuerta {

    companion object {
        val PROMESAS = mapOf(
            301 to "Sin tarea abierta la puerta no deja pasar ninguna entrada al teléfono y lo dice en el log.",
            302 to "Empezar una tarea desarma un alto viejo, y pedir el alto sin tarea abierta no arma el freno.",
            303 to "Con el freno echado ninguna entrada llega al teléfono y la corrida termina como cancelación, sin ejecutar el resto ni pedir otro turno.",
            304 to "El alto se avisa una sola vez por tarea aunque se pida diez veces, y al soltar se dice «Listo, tienes el control de vuelta.» una vez.",
            305 to "Una espera se corta en cuanto se pide el alto, no al agotar el plazo.",
            306 to "Terminar suelta el freno siempre, aunque la tarea reviente; después la puerta vuelve a exigir tarea abierta; una tarea anidada no la cierra.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** La frase de soltar, escrita aquí y no leída de producción: si alguien la cambia allí, esto se pone rojo. */
        const val LISTO = "Listo, tienes el control de vuelta."
    }

    /* ---------- El mapa a mano: superficies que graban, cerebro guionado, voz y log que anotan ---------- */

    /** Anota cada línea como «tag: mensaje», que es como la spec las cita. */
    class Bitacora : GraphLog {
        val lineas = mutableListOf<String>()
        override fun log(tag: String, message: String) { lineas += "$tag: $message" }
    }

    class Voz : Voice {
        val narrado = mutableListOf<String>()
        val dicho = mutableListOf<String>()
        override fun narrate(text: String) { narrado += text }
        override fun speak(text: String) { dicho += text }
    }

    /**
     * El teléfono de verdad, falso: graba el nombre de cada entrada que le llega, con la vista por la que
     * llegó (`telefono.openApp` y `sistema.openApp` son dos entradas distintas). [alEntrar] corre después
     * de grabar: ahí la persona pide el alto mientras la entrada ocurre.
     */
    class Mano(val alEntrar: (String) -> Unit = {}) {
        val entradas = mutableListOf<String>()
        var lecturas = 0
        private fun entra(que: String): Boolean { entradas += que; alEntrar(que); return true }

        val telefono = object : Phone {
            override suspend fun state(withScreenshot: Boolean): ScreenState { lecturas++; return ScreenState("com.x · X", "", 1080, 2400) }
            override suspend fun tap(x: Int, y: Int) = entra("tap")
            override suspend fun type(x: Int, y: Int, text: String) = entra("type")
            override suspend fun openApp(query: String) = entra("telefono.openApp")
            override suspend fun scroll(down: Boolean) = entra("scroll")
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) = entra("swipe")
            override suspend fun pressKey(key: String) = entra("pressKey")
        }
        val gestos = object : Gestures {
            override suspend fun home() = entra("home")
            override suspend fun appDrawer() = entra("appDrawer")
            override suspend fun notifications() = entra("notifications")
            override suspend fun panHome(right: Boolean) = entra("panHome")
            override suspend fun scrollMenu(down: Boolean) = entra("scrollMenu")
        }
        val sistema = object : SystemApi {
            override suspend fun openApp(name: String) = entra("sistema.openApp")
            override suspend fun setAlarm(hour: Int, minute: Int, message: String) = entra("setAlarm")
            override suspend fun setTimer(seconds: Int, message: String) = entra("setTimer")
            override suspend fun showAlarms() = entra("showAlarms")
            override suspend fun createEvent(title: String, startIso: String, location: String) = entra("createEvent")
            override suspend fun dial(number: String) = entra("dial")
            override suspend fun call(number: String) = entra("call")
            override suspend fun sendSms(number: String, message: String) = entra("sendSms")
            override suspend fun sendEmail(to: String, subject: String, body: String) = entra("sendEmail")
            override suspend fun webSearch(query: String) = entra("webSearch")
            override suspend fun openUrl(url: String) = entra("openUrl")
            override suspend fun maps(query: String) = entra("maps")
            override suspend fun directions(destination: String) = entra("directions")
            override suspend fun openCamera() = entra("openCamera")
            override suspend fun openSettings(section: String) = entra("openSettings")
            override suspend fun shareText(text: String) = entra("shareText")
            override suspend fun setClipboard(text: String) = entra("setClipboard")
            override suspend fun setVolume(stream: String, percent: Int) = entra("setVolume")
            override suspend fun adjustVolume(stream: String, direction: String) = entra("adjustVolume")
        }
        val reproductor = object : UiPlayer {
            override suspend fun tapLabel(label: String) = entra("tapLabel")
        }
    }

    class Entrada(val nombre: String, val toca: suspend () -> Boolean)

    /** Todas las entradas al teléfono que expone la puerta, una por método de las cuatro interfaces (salvo `state`, que lee). */
    private fun entradas(p: Puerta) = listOf(
        Entrada("tap") { p.telefono.tap(10, 20) },
        Entrada("type") { p.telefono.type(10, 20, "hola") },
        Entrada("telefono.openApp") { p.telefono.openApp("Calculadora") },
        Entrada("scroll") { p.telefono.scroll(true) },
        Entrada("swipe") { p.telefono.swipe(1, 2, 3, 4, 300) },
        Entrada("pressKey") { p.telefono.pressKey("back") },
        Entrada("home") { p.gestos.home() },
        Entrada("appDrawer") { p.gestos.appDrawer() },
        Entrada("notifications") { p.gestos.notifications() },
        Entrada("panHome") { p.gestos.panHome(true) },
        Entrada("scrollMenu") { p.gestos.scrollMenu(false) },
        Entrada("sistema.openApp") { p.sistema.openApp("Ajustes") },
        Entrada("setAlarm") { p.sistema.setAlarm(7, 30, "") },
        Entrada("setTimer") { p.sistema.setTimer(60, "") },
        Entrada("showAlarms") { p.sistema.showAlarms() },
        Entrada("createEvent") { p.sistema.createEvent("cita", "", "") },
        Entrada("dial") { p.sistema.dial("123") },
        Entrada("call") { p.sistema.call("123") },
        Entrada("sendSms") { p.sistema.sendSms("123", "hola") },
        Entrada("sendEmail") { p.sistema.sendEmail("a@b.c", "asunto", "cuerpo") },
        Entrada("webSearch") { p.sistema.webSearch("clima") },
        Entrada("openUrl") { p.sistema.openUrl("https://example.com") },
        Entrada("maps") { p.sistema.maps("café") },
        Entrada("directions") { p.sistema.directions("casa") },
        Entrada("openCamera") { p.sistema.openCamera() },
        Entrada("openSettings") { p.sistema.openSettings("wifi") },
        Entrada("shareText") { p.sistema.shareText("hola") },
        Entrada("setClipboard") { p.sistema.setClipboard("hola") },
        Entrada("setVolume") { p.sistema.setVolume("media", 50) },
        Entrada("adjustVolume") { p.sistema.adjustVolume("media", "raise") },
        Entrada("tapLabel") { p.reproductor.tapLabel("Guardar") },
    )

    private fun puerta(freno: Freno, mano: Mano, log: GraphLog = GraphLog { _, _ -> }) =
        Puerta(freno, mano.telefono, mano.gestos, mano.sistema, mano.reproductor, log)

    /** Da los turnos en el orden del guion y cuenta cuántos le pidieron. [alPensar] corre con el nº de turno. */
    class CerebroGuionado(vararg guion: BrainTurn, val alPensar: (Int) -> Unit = {}) : Brain {
        var turnos = 0
        private val cola = ArrayDeque(guion.toList())
        override fun begin(goal: String) {}
        override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
            turnos++
            alPensar(turnos)
            return cola.removeFirstOrNull() ?: BrainTurn(done = true, text = "guion agotado")
        }
        override fun inform(message: String) {}
    }

    private fun motor(cerebro: Brain, puerta: Puerta, freno: Freno, voz: Voice, log: GraphLog, usuario: UserChannel? = null) = ExecutionEngine(
        brain = { cerebro },
        phone = puerta.telefono,
        mcp = Mcp(puerta.gestos, puerta.sistema, player = puerta.reproductor, stepDelay = { 0 }),
        user = usuario,
        voice = voz,
        log = log,
        stepDelay = { 0 },
        freno = freno,
    )

    private class Reventon(mensaje: String) : Exception(mensaje)

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa301() = corre {
        val bitacora = Bitacora()
        val mano = Mano()
        val freno = Freno(log = bitacora)
        val p = puerta(freno, mano, bitacora)
        val todas = entradas(p)

        val pasaron = todas.filter { it.toca() }.map { it.nombre }
        assertEquals(emptyList(), pasaron, promesa(301) + " · devolvieron true sin tarea abierta")
        assertEquals(emptyList(), mano.entradas, promesa(301) + " · llegaron al teléfono sin tarea abierta")
        val rechazos = bitacora.lineas.filter { it.startsWith("puerta: sin tarea abierta, no paso «") && it.endsWith("»") }
        assertEquals(todas.size, rechazos.size, promesa(301) + " · no todas lo dijeron en el log: ${bitacora.lineas}")
        assertEquals(todas.size, rechazos.toSet().size, promesa(301) + " · el log no dice qué no pasó: $rechazos")

        // Leer no es entrar: la pantalla se puede mirar sin tarea.
        p.telefono.state(withScreenshot = false)
        assertEquals(1, mano.lecturas, promesa(301) + " · leer la pantalla no pasó")

        // El MCP montado sobre la puerta tampoco toca: ni un gesto, ni el sistema, ni una herramienta aprendida.
        val mcp = Mcp(p.gestos, p.sistema, learned = listOf(LearnedTool("calc", "calculadora", listOf("5"))), player = p.reproductor, stepDelay = { 0 })
        assertEquals("la herramienta no se pudo ejecutar", mcp.call("go_home", emptyMap()), promesa(301))
        assertEquals("la herramienta no se pudo ejecutar", mcp.call("set_alarm", mapOf("hour" to "7")), promesa(301))
        assertTrue(mcp.call("calc", mapOf("taps" to "5")).startsWith("la herramienta no se pudo ejecutar"), promesa(301))
        assertEquals(emptyList(), mano.entradas, promesa(301) + " · el MCP tocó el teléfono sin tarea abierta")

        // Con tarea abierta, las mismas entradas llegan, cada una al objeto que le toca.
        freno.empezar("probar la puerta")
        assertTrue(todas.all { it.toca() }, promesa(301) + " · con tarea abierta alguna entrada no pasó")
        assertEquals(todas.map { it.nombre }, mano.entradas, promesa(301) + " · con tarea abierta")
        freno.termine()
    }

    @Test
    fun promesa302() = corre {
        val avisos = mutableListOf<String>()
        val freno = Freno(avisa = { avisos += it })
        val mano = Mano()
        val p = puerta(freno, mano)

        freno.empezar("tarea 1")
        freno.pide("botón")
        assertTrue(freno.pedido, promesa(302) + " · con tarea abierta, pedir el alto no armó el freno")
        freno.empezar("tarea 2")                                 // sin termine(): la tarea 1 se quedó sin cerrar
        assertFalse(freno.pedido, promesa(302) + " · el alto de la tarea 1 sigue armado en la tarea 2")
        assertTrue(freno.abierta, promesa(302))
        assertEquals("tarea 2", freno.tarea, promesa(302))
        assertTrue(p.telefono.tap(1, 1), promesa(302) + " · la tarea 2 nació parada por el alto de la tarea 1")
        assertEquals(listOf("tap"), mano.entradas, promesa(302))
        freno.termine()

        val bitacora = Bitacora()
        val avisosOcioso = mutableListOf<String>()
        val ocioso = Freno(log = bitacora, avisa = { avisosOcioso += it })
        repeat(10) { ocioso.pide("botón sin nada en marcha") }
        assertFalse(ocioso.pedido, promesa(302) + " · un alto sin tarea abierta dejó el freno armado")
        assertTrue(avisosOcioso.isEmpty(), promesa(302) + " · avisó de un alto sin nada que parar: $avisosOcioso")
        assertTrue(bitacora.lineas.isEmpty(), promesa(302) + " · registró un alto sin nada que parar: ${bitacora.lineas}")
        ocioso.empezar("lo siguiente")
        assertFalse(ocioso.pedido, promesa(302) + " · el trabajo siguiente nació abortado")
        ocioso.termine()
    }

    @Test
    fun promesa303() = corre {
        // La entrada rechazada: Paraste, tipo exacto y mensaje. Es una cancelación, no un fallo.
        run {
            val mano = Mano()
            val freno = Freno()
            val p = puerta(freno, mano)
            freno.empezar("algo que toca")
            freno.pide("botón")
            for (entrada in entradas(p)) {
                val e = assertFailsWith<Paraste>(promesa(303) + " · ${entrada.nombre} no lanzó Paraste") { entrada.toca() }
                assertEquals(Paraste::class, e::class, promesa(303) + " · ${entrada.nombre}")
                assertEquals("paraste tú", e.message, promesa(303) + " · ${entrada.nombre}")
                assertIs<CancellationException>(e, promesa(303))
            }
            assertEquals(emptyList(), mano.entradas, promesa(303) + " · llegaron al teléfono con el freno echado")
            freno.termine()
        }

        // (a) El alto se pide mientras ocurre el primer tap de un turno de cuatro acciones.
        run {
            val freno = Freno()
            val mano = Mano(alEntrar = { if (it == "tap") freno.pide("botón") })
            val p = puerta(freno, mano)
            val cerebro = CerebroGuionado(
                BrainTurn(
                    actions = listOf(AgentAction.Tap(1, 1), AgentAction.Tap(2, 2), AgentAction.Type(3, 3, "hola"), AgentAction.Mcp("go_home", emptyMap())),
                    intents = listOf("toco el uno", "toco el dos", "escribo hola", "voy al inicio"),
                ),
                BrainTurn(actions = listOf(AgentAction.Tap(4, 4))),
            )
            val voz = Voz()
            val bitacora = Bitacora()
            val dijo = freno.enTarea("abre la calculadora") { motor(cerebro, p, freno, voz, bitacora).run("abre la calculadora") }

            assertEquals(listOf("tap"), mano.entradas, promesa(303) + " · llegaron al teléfono tras el alto")
            assertEquals(1, cerebro.turnos, promesa(303) + " · pidió otro turno con el freno echado")
            assertTrue(dijo.startsWith("paraste:"), promesa(303) + " · la corrida no terminó como cancelación: «$dijo»")
            assertTrue("escribo hola" !in voz.narrado && "voy al inicio" !in voz.narrado, promesa(303) + " · ejecutó el resto: ${voz.narrado}")
            // «▪» marca una acción ejecutada; la línea del turno lista las decididas y esa sí las nombra.
            assertTrue(bitacora.lineas.none { "▪" in it && ("type(" in it || "go_home" in it) }, promesa(303) + " · ejecutó el resto: ${bitacora.lineas}")
            assertEquals(1, voz.narrado.count { "par" in it.lowercase() }, promesa(303) + " · no narró una sola vez que paró: ${voz.narrado}")
            assertTrue(voz.narrado.none { "¡Listo!" in it }, promesa(303) + " · celebró una corrida parada: ${voz.narrado}")
            assertTrue(voz.dicho.isEmpty(), promesa(303) + " · dijo en voz alta un resumen de una corrida parada: ${voz.dicho}")
        }

        // (b) El alto se pide mientras el cerebro piensa: ninguna de sus acciones llega.
        run {
            val freno = Freno()
            val mano = Mano()
            val p = puerta(freno, mano)
            val cerebro = CerebroGuionado(
                BrainTurn(actions = listOf(AgentAction.Tap(1, 1), AgentAction.Mcp("set_alarm", mapOf("hour" to "7")))),
                alPensar = { freno.pide("botón") },
            )
            val dijo = freno.enTarea("pon una alarma") { motor(cerebro, p, freno, Voz(), Bitacora()).run("pon una alarma") }
            assertEquals(emptyList(), mano.entradas, promesa(303) + " · el turno pensado con el alto echado tocó el teléfono")
            assertEquals(1, cerebro.turnos, promesa(303))
            assertTrue(dijo.startsWith("paraste:"), promesa(303) + " · «$dijo»")
        }

        // (c) El alto se pide en la última acción del turno: no se pide otro turno (otro turno cuesta).
        run {
            val freno = Freno()
            val mano = Mano(alEntrar = { freno.pide("botón") })
            val p = puerta(freno, mano)
            val cerebro = CerebroGuionado(
                BrainTurn(actions = listOf(AgentAction.Tap(1, 1))),
                BrainTurn(done = true, text = "listo"),
            )
            val dijo = freno.enTarea("toca una vez") { motor(cerebro, p, freno, Voz(), Bitacora()).run("toca una vez") }
            assertEquals(listOf("tap"), mano.entradas, promesa(303))
            assertEquals(1, cerebro.turnos, promesa(303) + " · pidió otro turno a Graph con el freno echado")
            assertTrue(dijo.startsWith("paraste:"), promesa(303) + " · «$dijo»")
        }

        // (d) El alto se pide mientras Graph piensa y el turno vuelve con una pregunta: no se dice ni se le
        // pregunta a nadie. Preguntar tras el alto deja la corrida esperando una respuesta que ya no importa.
        run {
            val freno = Freno()
            val p = puerta(freno, Mano())
            val preguntas = mutableListOf<String>()
            val usuario = object : UserChannel {
                override suspend fun ask(question: String): String { preguntas += question; return "a las 7" }
            }
            val cerebro = CerebroGuionado(
                BrainTurn(question = "¿A qué hora?", speech = "Voy a poner la alarma", narration = "pensando la hora"),
                alPensar = { freno.pide("botón") },
            )
            val voz = Voz()
            val dijo = freno.enTarea("pon una alarma") { motor(cerebro, p, freno, voz, Bitacora(), usuario).run("pon una alarma") }
            assertEquals(emptyList(), preguntas, promesa(303) + " · preguntó al usuario con el freno echado")
            assertTrue(voz.dicho.isEmpty(), promesa(303) + " · habló con el freno echado: ${voz.dicho}")
            assertTrue("pensando la hora" !in voz.narrado, promesa(303) + " · narró el turno pensado tras el alto: ${voz.narrado}")
            assertTrue(dijo.startsWith("paraste:"), promesa(303) + " · «$dijo»")
            assertEquals(1, cerebro.turnos, promesa(303))
        }

        // (e) Lo mismo con un turno que dice haber terminado: una corrida parada no celebra ni resume.
        run {
            val freno = Freno()
            val p = puerta(freno, Mano())
            val cerebro = CerebroGuionado(BrainTurn(done = true, text = "Alarma puesta"), alPensar = { freno.pide("botón") })
            val voz = Voz()
            val dijo = freno.enTarea("pon una alarma") { motor(cerebro, p, freno, voz, Bitacora()).run("pon una alarma") }
            assertTrue(dijo.startsWith("paraste:"), promesa(303) + " · un turno done tras el alto no terminó como cancelación: «$dijo»")
            assertTrue(voz.dicho.isEmpty(), promesa(303) + " · dijo el resumen de una corrida parada: ${voz.dicho}")
            assertTrue(voz.narrado.none { "¡Listo!" in it }, promesa(303) + " · celebró una corrida parada: ${voz.narrado}")
        }

        // (f) Una espera que pide el modelo se corta con el alto: el motor espera con el freno, no de un tirón.
        run {
            val freno = Freno()
            val p = puerta(freno, Mano())
            val cerebro = CerebroGuionado(BrainTurn(actions = listOf(AgentAction.Wait(3000))), BrainTurn(done = true, text = "esperé"))
            val inicio = TimeSource.Monotonic.markNow()
            val dijo = freno.enTarea("espera larga") {
                coroutineScope {
                    launch { delay(120); freno.pide("botón") }
                    motor(cerebro, p, freno, Voz(), Bitacora()).run("espera larga")
                }
            }
            val tardo = inicio.elapsedNow()
            assertTrue(tardo < 1.seconds, promesa(303) + " · un Wait(3000) con el alto a los 120 ms tardó $tardo")
            assertTrue(dijo.startsWith("paraste:"), promesa(303) + " · «$dijo»")
            assertEquals(1, cerebro.turnos, promesa(303))
        }

        // (g) Cancelar el trabajo NO es un alto: la cancelación sale tal cual y el motor no dice que paró.
        // Un motor que tragara cualquier cancelación convertiría un reencaminado o un cierre en «paraste».
        run {
            val freno = Freno()
            val p = puerta(freno, Mano())
            val pensando = CompletableDeferred<Unit>()
            val colgado = object : Brain {
                override fun begin(goal: String) {}
                override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
                    pensando.complete(Unit)
                    awaitCancellation()
                }
                override fun inform(message: String) {}
            }
            val voz = Voz()
            var salida: Result<String>? = null
            coroutineScope {
                val trabajo = launch {
                    salida = runCatching { freno.enTarea("colgada") { motor(colgado, p, freno, voz, Bitacora()).run("colgada") } }
                }
                pensando.await()
                trabajo.cancel()
                trabajo.join()
            }
            val salio = salida?.exceptionOrNull()
            assertIs<CancellationException>(salio, promesa(303) + " · cancelar el trabajo no propagó la cancelación: $salida")
            assertFalse(salio is Paraste, promesa(303) + " · una cancelación cualquiera se tomó por un alto")
            assertTrue(voz.narrado.none { "Paré" in it }, promesa(303) + " · narró que paró sin que nadie pidiera el alto: ${voz.narrado}")
            assertFalse(freno.abierta, promesa(303))
        }
    }

    @Test
    fun promesa304() {
        val bitacora = Bitacora()
        val avisos = mutableListOf<String>()
        val freno = Freno(log = bitacora, avisa = { avisos += it })

        freno.empezar("algo largo")
        repeat(10) { freno.pide("insistencia $it") }
        assertEquals(1, avisos.size, promesa(304) + " · diez altos, avisos: $avisos")
        assertEquals(0, avisos.count { it == LISTO }, promesa(304) + " · devolvió el control antes de soltar")
        assertEquals(
            listOf("freno: alto pedido (insistencia 0); paro «algo largo»"),
            bitacora.lineas.filter { it.startsWith("freno:") },
            promesa(304),
        )

        freno.termine()
        assertEquals(listOf(LISTO), avisos.drop(1), promesa(304) + " · al soltar: $avisos")
        freno.termine()
        assertEquals(2, avisos.size, promesa(304) + " · soltar dos veces lo dijo dos veces: $avisos")

        // El aviso es por tarea: la siguiente vuelve a avisar, también una sola vez.
        freno.empezar("otra cosa")
        freno.pide("botón"); freno.pide("botón")
        freno.termine()
        assertEquals(4, avisos.size, promesa(304) + " · $avisos")
        assertEquals(LISTO, avisos.last(), promesa(304))

        // Una tarea sin alto no devuelve un control que nadie pidió.
        freno.empezar("tranquila")
        freno.termine()
        assertEquals(4, avisos.size, promesa(304) + " · dijo que devolvía el control sin alto: $avisos")
    }

    @Test
    fun promesa305() = corre {
        // Con reloj de prueba: el alto llega a los 120 ms de una espera de 3 s.
        run {
            val reloj = TestTimeSource()
            val inicio = reloj.markNow()
            val trozos = mutableListOf<Long>()
            var alto: () -> Unit = {}
            val freno = Freno(reloj = reloj, espera = {
                trozos += it
                reloj += it.milliseconds
                if (inicio.elapsedNow() >= 120.milliseconds) alto()
            })
            alto = { freno.pide("botón") }
            freno.empezar("una pausa larga")
            assertTrue(freno.duerme(3000), promesa(305) + " · dormir no dijo que hay que parar")
            assertTrue(inicio.elapsedNow() < 200.milliseconds, promesa(305) + " · tardó ${inicio.elapsedNow()} de 3000 ms")
            assertTrue(trozos.all { it <= 40 }, promesa(305) + " · durmió a trozos más largos que 40 ms: $trozos")
            freno.termine()
        }
        // Sin alto duerme el plazo entero, a trozos, y dice que no hay que parar.
        run {
            val reloj = TestTimeSource()
            val inicio = reloj.markNow()
            val trozos = mutableListOf<Long>()
            val freno = Freno(reloj = reloj, espera = { trozos += it; reloj += it.milliseconds })
            freno.empezar("una pausa corta")
            assertFalse(freno.duerme(100), promesa(305))
            assertEquals(listOf(40L, 40L, 20L), trozos, promesa(305))
            assertEquals(100.milliseconds, inicio.elapsedNow(), promesa(305))
            freno.termine()
        }
        // Con el reloj y la espera reales, los que usará la app.
        run {
            val freno = Freno()
            freno.empezar("una pausa real")
            val inicio = TimeSource.Monotonic.markNow()
            val corto = coroutineScope {
                launch { delay(120); freno.pide("botón") }
                freno.duerme(3000)
            }
            val tardo = inicio.elapsedNow()
            freno.termine()
            assertTrue(corto, promesa(305))
            assertTrue(tardo < 1.seconds, promesa(305) + " · con reloj real tardó $tardo de 3 s")
        }
    }

    @Test
    fun promesa306() = corre {
        val bitacora = Bitacora()
        val avisos = mutableListOf<String>()
        val freno = Freno(log = bitacora, avisa = { avisos += it })
        val mano = Mano()
        val p = puerta(freno, mano, bitacora)

        // Revienta a mitad: la excepción sale tal cual y el freno queda suelto.
        val e = assertFailsWith<Reventon>(promesa(306)) {
            freno.enTarea("algo que revienta") { p.telefono.tap(1, 1); throw Reventon("revienta") }
        }
        assertEquals("revienta", e.message, promesa(306))
        assertFalse(freno.abierta, promesa(306) + " · la tarea reventada quedó abierta")
        assertFalse(freno.pedido, promesa(306))
        assertEquals("", freno.tarea, promesa(306))

        // Revienta con el alto echado: suelta igual, y lo dice.
        assertFailsWith<Reventon>(promesa(306)) {
            freno.enTarea("otra que revienta") { freno.pide("botón"); throw Reventon("revienta con el alto echado") }
        }
        assertFalse(freno.pedido, promesa(306) + " · el alto quedó echado tras reventar: el teléfono quedaría muerto")
        assertFalse(freno.abierta, promesa(306))
        assertEquals(listOf(LISTO), avisos.filter { it == LISTO }, promesa(306) + " · $avisos")

        // Parada por la puerta dentro de la tarea: la Paraste sale tal cual y también suelta.
        val paraste = assertFailsWith<Paraste>(promesa(306)) {
            freno.enTarea("una que paras") { freno.pide("botón"); p.telefono.tap(2, 2) }
        }
        assertEquals("paraste tú", paraste.message, promesa(306))
        assertFalse(freno.pedido, promesa(306))
        assertFalse(freno.abierta, promesa(306))

        // Terminada, un alto no arma nada, y la puerta vuelve a exigir tarea abierta (sin Paraste: pide tarea).
        freno.pide("después de acabar")
        assertFalse(freno.pedido, promesa(306) + " · un alto tras terminar armó el freno")
        bitacora.lineas.clear()
        assertFalse(p.telefono.tap(3, 3), promesa(306) + " · tras terminar la puerta dejó pasar sin tarea")
        assertEquals(listOf("tap"), mano.entradas, promesa(306))
        assertTrue(bitacora.lineas.any { it.startsWith("puerta: sin tarea abierta, no paso «") }, promesa(306) + " · ${bitacora.lineas}")

        // Anidada (el paso consciente de un workflow dentro de la corrida): la de dentro no cierra la de fuera.
        run {
            val f = Freno()
            val m = Mano()
            val puertaAnidada = puerta(f, m)
            var abiertaTrasInterior = false
            var pasaTrasInterior = false
            var armaTrasInterior = false
            f.enTarea("exterior") {
                f.enTarea("paso consciente") { puertaAnidada.telefono.tap(1, 1) }
                abiertaTrasInterior = f.abierta
                pasaTrasInterior = puertaAnidada.telefono.tap(2, 2)
                f.pide("botón")
                armaTrasInterior = f.pedido
            }
            assertTrue(abiertaTrasInterior, promesa(306) + " · la tarea anidada cerró la de fuera")
            assertTrue(pasaTrasInterior, promesa(306) + " · tras la tarea anidada la puerta no dejó pasar a la de fuera")
            assertTrue(armaTrasInterior, promesa(306) + " · tras la tarea anidada el alto ya no arma")
            assertEquals(listOf("tap", "tap"), m.entradas, promesa(306))
            assertFalse(f.abierta, promesa(306) + " · la de fuera no soltó al terminar")

            // Y la de dentro tampoco desarma un alto que ya pidió la de fuera.
            assertFailsWith<Paraste>(promesa(306)) {
                f.enTarea("exterior") {
                    f.pide("botón")
                    f.enTarea("paso consciente") { puertaAnidada.telefono.tap(3, 3) }
                }
            }
            assertEquals(listOf("tap", "tap"), m.entradas, promesa(306) + " · la tarea anidada desarmó el alto de fuera")
            assertFalse(f.abierta, promesa(306))
        }
    }
}
