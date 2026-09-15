package graph.core.contrato

import graph.core.contrato.Contrato003FrenoYPuerta.Bitacora
import graph.core.contrato.Contrato003FrenoYPuerta.CerebroGuionado
import graph.core.contrato.Contrato003FrenoYPuerta.Mano
import graph.core.contrato.Contrato003FrenoYPuerta.Voz
import graph.core.contrato.Contrato003TopeYCuenta.Companion.nodo
import graph.core.contrato.Contrato003TopeYCuenta.Telefono
import graph.core.domain.AgentAction
import graph.core.domain.Brain
import graph.core.domain.BrainTurn
import graph.core.domain.LearnedTool
import graph.core.domain.Phone
import graph.core.domain.ScreenState
import graph.core.domain.UiPlayer
import graph.core.domain.UserChannel
import graph.core.domain.Workflow
import graph.core.domain.WorkflowStep
import graph.core.precision.ArmadoDeEjecucion
import graph.core.precision.CorridaEnCurso
import graph.core.precision.CuentaDePeticion
import graph.core.precision.Freno
import graph.core.precision.Paraste
import graph.core.precision.Puerta
import graph.core.precision.QuienHabla
import graph.core.precision.TopeDeIntentos
import graph.core.precision.abrePeticion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/**
 * CONTRATO 003, REVISIÓN DE 3A-3C — LO QUE SALE DEL TELÉFONO Y UNA CORRIDA A LA VEZ
 * (docs/specs/003-lo-hace-a-la-primera-y-se-puede-parar.md, promesas 317 y 318).
 *
 * El log no se queda en el teléfono: `LogBus` reenvía cada línea a la telemetría remota. Medido por los revisores:
 * «tope: no paso «tap(10,10)»: … «Juan Pérez» ya falló dos veces», el selector con `text=` en `peticion:`, el texto
 * de `type` y los argumentos MCP en el motor, y el prompt de la persona como nombre de la tarea del freno.
 *
 * Y una segunda corrida de fuera (la burbuja y la app a la vez) corría anidada en la primera: cuando la primera
 * cerraba la tarea, la segunda seguía pagando turnos a Graph sin poder pararse ni tocar.
 */
class Contrato003LoQueSaleYUnaCorrida {

    companion object {
        val PROMESAS = mapOf(
            317 to "Ninguna línea de log de la ejecución lleva lo que el usuario escribió, pidió o lo que la pantalla muestra: solo tipos, largos, celdas y nombres de herramienta.",
            318 to "Una corrida de fuera no se abre encima de otra: la segunda dice «ya hay una tarea en curso» sin pedir turnos ni tocar el teléfono, y un motor sin tarea abierta termina como parada sin pedir otro turno.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** La frase escrita aquí y no leída de producción: si alguien la cambia allí, esto se pone rojo. */
        const val YA_HAY_UNA = "ya hay una tarea en curso"

        // Lo que la persona pide y escribe, y lo que la pantalla muestra.
        const val PEDIDO = "escríbele a Juan Pérez que nos vemos en el Parque Lleras"
        const val CONTACTO = "Juan Pérez"
        const val NUMERO = "3104459821"
        const val MENSAJE = "nos vemos en el Parque Lleras a las 8"
        const val CORREO = "lucia.restrepo@correo.co"
        const val ASUNTO = "Cumpleaños de Lucía"
        const val CUERPO = "la clave del portón es 4471"
        const val BUSQUEDA = "dermatólogo en Envigado"
        const val URL = "https://banco.example/cuenta/88123"
        const val LUGAR = "Carrera 43A # 1-50"
        const val APP = "Bancolombia"
        const val TITULO = "com.whatsapp · Chat con Juan Pérez"

        /** Los datos tal como la persona los dio o la pantalla los muestra (del título, lo que no es el paquete). */
        val DATOS = listOf(PEDIDO, CONTACTO, NUMERO, MENSAJE, CORREO, ASUNTO, CUERPO, BUSQUEDA, URL, LUGAR, APP, TITULO.substringAfter(" · "))

        private val SIN_TILDE = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n')
        fun plano(s: String) = s.lowercase().map { SIN_TILDE[it] ?: it }.joinToString("")

        /**
         * Cada trozo de 10 caracteres de cada dato, sin tildes ni mayúsculas: un `take(24)` o un recorte cualquiera
         * también es una fuga, no solo el dato entero.
         */
        val TROZOS = DATOS.flatMap { plano(it).windowed(10) }.toSet()

        /** Y lo más corto que delata por sí solo: un apellido, la clave, el número de cuenta. */
        val MARCAS = listOf("perez", "lucia", "4471", "88123", "lleras", "envigado")
    }

    private fun manos(mano: Mano) = ArmadoDeEjecucion.Manos(mano.telefono, mano.gestos, mano.sistema, mano.reproductor)

    /** Las 31 entradas de la puerta, cada una con lo que la persona le daría de verdad. */
    private fun entradasConDatos(p: Puerta) = listOf<suspend () -> Boolean>(
        { p.telefono.tap(10, 20) },
        { p.telefono.type(10, 20, MENSAJE) },
        { p.telefono.openApp(APP) },
        { p.telefono.scroll(true) },
        { p.telefono.swipe(1, 2, 3, 4, 300) },
        { p.telefono.pressKey("back") },
        { p.gestos.home() },
        { p.gestos.appDrawer() },
        { p.gestos.notifications() },
        { p.gestos.panHome(true) },
        { p.gestos.scrollMenu(false) },
        { p.sistema.openApp(APP) },
        { p.sistema.setAlarm(7, 30, MENSAJE) },
        { p.sistema.setTimer(60, MENSAJE) },
        { p.sistema.showAlarms() },
        { p.sistema.createEvent(ASUNTO, "2026-09-20T18:00", LUGAR) },
        { p.sistema.dial(NUMERO) },
        { p.sistema.call(NUMERO) },
        { p.sistema.sendSms(NUMERO, MENSAJE) },
        { p.sistema.sendEmail(CORREO, ASUNTO, CUERPO) },
        { p.sistema.webSearch(BUSQUEDA) },
        { p.sistema.openUrl(URL) },
        { p.sistema.maps(LUGAR) },
        { p.sistema.directions(LUGAR) },
        { p.sistema.openCamera() },
        { p.sistema.openSettings("wifi") },
        { p.sistema.shareText(CUERPO) },
        { p.sistema.setClipboard(CUERPO) },
        { p.sistema.setVolume("media", 50) },
        { p.sistema.adjustVolume("media", "raise") },
        { p.reproductor.tapLabel(CONTACTO) },
    )

    /* ---------- Las promesas ---------- */

    @Test
    fun promesa317() = corre {
        val log = Bitacora()

        // La puerta, sin tarea y con el alto echado: cada entrada lo dice en el log, sin lo que llevaba.
        run {
            val freno = Freno(log = log)
            val mano = Mano()
            val p = Puerta(freno, mano.telefono, mano.gestos, mano.sistema, mano.reproductor, log)
            val todas = entradasConDatos(p)
            todas.forEach { it() }
            assertEquals(todas.size, log.lineas.count { it.startsWith("puerta: sin tarea abierta, no paso «") }, promesa(317) + " · ${log.lineas}")
            freno.empezar("corrida")
            freno.pide("píldora")
            todas.forEach { runCatching { it() } }
            freno.termine()
            assertEquals(todas.size, log.lineas.count { it.startsWith("puerta: paraste tú, no paso «") }, promesa(317) + " · ${log.lineas}")
        }

        // El tope y la cuenta sobre un contacto que no responde: tocar su fila, escribirle y tocarlo por nombre.
        run {
            val fila = nodo("a11y:id=contact_row;text=$CONTACTO;cls=TextView;path=0.3.2", CONTACTO, 0, 800, 1080, 950, tipo = "TextView")
            val tel = Telefono(fila, textos = mutableListOf(TITULO, MENSAJE), alEscribir = { false }, alTocarEtiqueta = { false })
            val freno = Freno(log = log).also { it.empezar("corrida") }
            val tope = TopeDeIntentos(144)
            val cuenta = CuentaDePeticion(TestTimeSource(), log)
            val mano = Mano()
            val p = Puerta(
                freno, tel.telefono, mano.gestos, mano.sistema, tel.reproductor, log,
                nodoEn = { x, y -> tel.nodoEn(x, y) }, huella = { tel.huella() }, tope = tope, cuenta = cuenta,
            )
            abrePeticion(QuienHabla.PERSONA, tope, cuenta)
            repeat(3) { p.telefono.tap(540, 900) }
            repeat(3) { p.telefono.type(540, 900, MENSAJE) }
            repeat(3) { p.reproductor.tapLabel(CONTACTO) }
            abrePeticion(QuienHabla.PERSONA, tope, cuenta)
            repeat(2) { p.reproductor.tapLabel(CONTACTO) }                  // lo más intentado es un nombre
            cuenta.cerrar()
            assertEquals(3, log.lineas.count { it.startsWith("tope: no paso «") }, promesa(317) + " · ${log.lineas}")
            assertEquals(2, log.lineas.count { it.startsWith("peticion: ") }, promesa(317) + " · ${log.lineas}")

            // La huella y el nodo que revientan con lo que la pantalla muestra en el mensaje.
            val rota = Puerta(
                freno, tel.telefono, mano.gestos, mano.sistema, tel.reproductor, log,
                nodoEn = { _, _ -> error("sin raíz en la ventana «$TITULO»") }, huella = { error("no leí «$TITULO»") },
                tope = TopeDeIntentos(144),
            )
            rota.telefono.tap(10, 10)
            assertTrue(log.lineas.any { it.startsWith("tope: no pude leer el nodo") } && log.lineas.any { it.startsWith("tope: no pude tomar la huella") },
                promesa(317) + " · ${log.lineas}")
            freno.termine()
        }

        // La corrida entera: el pedido, lo que el motor escribe y manda por MCP, la pregunta y el resumen de Graph,
        // la pantalla de un chat, una herramienta aprendida y un workflow que fallan, un paso consciente que revienta,
        // una corrida que se intenta abrir encima y un alto.
        run {
            val freno = Freno(log = log)
            val armado = ArmadoDeEjecucion(freno, log)
            val mano = Mano()
            val chat = object : Phone by mano.telefono {
                override suspend fun state(withScreenshot: Boolean) = ScreenState(TITULO, "$CONTACTO: ¿$MENSAJE?", 1080, 2400)
            }
            val sinNombre = object : UiPlayer {
                override suspend fun tapLabel(label: String) = false
            }
            val manos = ArmadoDeEjecucion.Manos(chat, mano.gestos, mano.sistema, sinNombre)
            val flujo = Workflow(
                "mensaje", "escribe a un contacto",
                listOf(WorkflowStep("busca a $CONTACTO", target = CONTACTO, subconscious = true), WorkflowStep("escríbele $MENSAJE")),
            )
            val graphRoto = object : Brain {
                override fun begin(goal: String) {}
                override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn = error("graph no encontró a $CONTACTO")
                override fun inform(message: String) {}
            }
            val workflows = ArmadoDeEjecucion.Workflows(
                lista = listOf(flujo),
                elementos = { listOf(CONTACTO) },
                consciente = { _, paso, contexto ->
                    armado.pasoConsciente("${paso.action} ($contexto)", armado.arma(manos, { graphRoto }, Voz(), maxTurnos = 2, pausa = { 0 }).motor)
                },
            )
            val usuario = object : UserChannel {
                override suspend fun ask(question: String) = CUERPO
            }
            val cerebro = CerebroGuionado(
                BrainTurn(
                    actions = listOf(
                        AgentAction.Type(3, 3, MENSAJE),
                        AgentAction.OpenApp(APP),
                        AgentAction.Mcp("send_sms", mapOf("number" to NUMERO, "message" to MENSAJE)),
                        AgentAction.Mcp("contactos", mapOf("taps" to CONTACTO)),
                        AgentAction.Mcp("workflow_mensaje", mapOf("context" to CUERPO)),
                        AgentAction.Key("back"),
                    ),
                    intents = List(6) { "voy con $CONTACTO" },
                    speech = "Le escribo a $CONTACTO",
                    narration = "buscando a $CONTACTO",
                ),
                BrainTurn(question = "¿Le mando también $CUERPO?"),
                BrainTurn(done = true, text = "Listo: le escribí a $CONTACTO que $MENSAJE"),
            )
            val sesion = armado.arma(
                manos, { cerebro }, Voz(), usuario = usuario, pausa = { 0 },
                aprendidas = listOf(LearnedTool("contactos", "abre un contacto", listOf(CONTACTO))), workflows = workflows,
            )
            armado.correr(PEDIDO) {
                runCatching { armado.correr(PEDIDO) { "encima" } }
                sesion.motor.run(PEDIDO)
            }

            val parado = CerebroGuionado(BrainTurn(actions = listOf(AgentAction.Tap(1, 1))), alPensar = { armado.parar("píldora") })
            runCatching { armado.correr(PEDIDO) { armado.arma(manos, { parado }, Voz(), pausa = { 0 }).motor.run(PEDIDO) } }

            // Que cada vía de verdad escribió su línea: sin ellas, un log vacío daría verde.
            for (esperada in listOf(
                "run: ▶", "run: turno 1", "run:   ▪ computer-use type(", "run: 🗣", "run: ❓", "run: ■", "run: ✋",
                "mcp: 🧩", "workflow: ■", "freno: tarea abierta", "freno: alto pedido", "freno: suelto",
            )) assertTrue(log.lineas.any { it.startsWith(esperada) }, promesa(317) + " · no hay línea «$esperada»: ${log.lineas}")
            assertTrue(log.lineas.any { it.startsWith("workflow:") && "consciente falló" in it }, promesa(317) + " · ${log.lineas}")
        }

        val fugas = log.lineas.filter { linea -> plano(linea).let { l -> MARCAS.any { it in l } || TROZOS.any { it in l } } }
        assertEquals(emptyList(), fugas, promesa(317) + " · líneas con lo que la persona escribió, pidió o la pantalla muestra")
    }

    @Test
    fun promesa318() = corre {
        // Dentro de una corrida de fuera, otra corrida de fuera no se abre: ni un turno, ni un toque, y la primera
        // sigue siendo la que se para.
        run {
            val bitacora = Bitacora()
            val freno = Freno(log = bitacora)
            val armado = ArmadoDeEjecucion(freno, bitacora)
            val mano = Mano()
            val segunda = CerebroGuionado(BrainTurn(actions = listOf(AgentAction.Tap(9, 9))), BrainTurn(done = true, text = "B"))
            var salidaSegunda: Result<String>? = null
            var abiertaTras = false
            var armaTras = false
            val primera = runCatching {
                armado.correr("abre ajustes") {
                    salidaSegunda = runCatching {
                        armado.correr("pon una alarma") { armado.arma(manos(mano), { segunda }, Voz(), maxTurnos = 8, pausa = { 0 }).motor.run("pon una alarma") }
                    }
                    abiertaTras = freno.abierta
                    armado.parar("píldora")
                    armaTras = freno.pedido
                }
            }
            val rechazo = salidaSegunda?.exceptionOrNull()
            assertIs<CorridaEnCurso>(rechazo, promesa(318) + " · la segunda corrida se abrió encima: $salidaSegunda")
            assertEquals(YA_HAY_UNA, rechazo.message, promesa(318))
            assertEquals(0, segunda.turnos, promesa(318) + " · la segunda corrida le pidió turnos a Graph")
            assertEquals(emptyList(), mano.entradas, promesa(318) + " · la segunda corrida tocó el teléfono")
            assertTrue(abiertaTras, promesa(318) + " · rechazar la segunda cerró la primera")
            assertTrue(armaTras, promesa(318) + " · tras rechazar la segunda, el alto ya no para la primera")
            assertIs<Paraste>(primera.exceptionOrNull(), promesa(318) + " · $primera")
            assertTrue(bitacora.lineas.any { it.startsWith("freno: $YA_HAY_UNA") }, promesa(318) + " · el rechazo no quedó en el log: ${bitacora.lineas}")
            // Acabada la primera, la siguiente sí se abre, y nace suelta.
            assertTrue(armado.correr("la siguiente") { freno.abierta && !freno.pedido }, promesa(318) + " · tras la primera no se pudo abrir otra")
        }

        // A la vez, desde dos vías: la primera espera a Graph y la segunda llega mientras.
        run {
            val bitacora = Bitacora()
            val freno = Freno(log = bitacora)
            val armado = ArmadoDeEjecucion(freno, bitacora)
            val mano = Mano()
            val pensando = CompletableDeferred<Unit>()
            val suelta = CompletableDeferred<Unit>()
            val lenta = object : Brain {
                override fun begin(goal: String) {}
                override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
                    pensando.complete(Unit)
                    suelta.await()
                    return BrainTurn(done = true, text = "A")
                }
                override fun inform(message: String) {}
            }
            val segunda = CerebroGuionado(BrainTurn(actions = listOf(AgentAction.Tap(9, 9))), BrainTurn(done = true, text = "B"))
            var a: Result<String>? = null
            var b: Result<String>? = null
            coroutineScope {
                val primera = launch {
                    a = runCatching { armado.correr("abre ajustes") { armado.arma(manos(mano), { lenta }, Voz(), pausa = { 0 }).motor.run("abre ajustes") } }
                }
                pensando.await()
                b = runCatching { armado.correr("pon una alarma") { armado.arma(manos(mano), { segunda }, Voz(), maxTurnos = 8, pausa = { 0 }).motor.run("pon una alarma") } }
                suelta.complete(Unit)
                primera.join()
            }
            assertIs<CorridaEnCurso>(b?.exceptionOrNull(), promesa(318) + " · la segunda vía abrió una corrida encima: $b")
            assertEquals(0, segunda.turnos, promesa(318))
            assertEquals(emptyList(), mano.entradas, promesa(318))
            assertEquals("A", a?.getOrNull(), promesa(318) + " · la primera no acabó bien: $a")
        }

        // Un motor sin tarea abierta no pide turno: termina como parada, sin decir que paró por la persona.
        run {
            val freno = Freno()
            val armado = ArmadoDeEjecucion(freno)
            val cerebro = CerebroGuionado(BrainTurn(actions = listOf(AgentAction.Tap(1, 1))), BrainTurn(done = true, text = "hecho"))
            val voz = Voz()
            val dijo = armado.arma(manos(Mano()), { cerebro }, voz, pausa = { 0 }).motor.run("sin tarea")
            assertEquals(0, cerebro.turnos, promesa(318) + " · un motor sin tarea le pidió turnos a Graph")
            assertTrue(dijo.startsWith("paraste:"), promesa(318) + " · «$dijo»")
            assertTrue(voz.narrado.none { "Paré" in it }, promesa(318) + " · dijo que paró porque se lo pidieron: ${voz.narrado}")
        }

        // La tarea se cierra debajo del motor (lo que hacía la primera corrida al acabar): no pide el turno siguiente.
        run {
            val freno = Freno()
            val armado = ArmadoDeEjecucion(freno)
            val mano = Mano(alEntrar = { if (it == "tap") freno.termine() })
            val cerebro = CerebroGuionado(*Array(8) { BrainTurn(actions = listOf(AgentAction.Tap(1, 1))) })
            val dijo = armado.correr("se cierra debajo") { armado.arma(manos(mano), { cerebro }, Voz(), maxTurnos = 8, pausa = { 0 }).motor.run("se cierra debajo") }
            assertEquals(1, cerebro.turnos, promesa(318) + " · sin tarea el motor siguió pidiendo turnos")
            assertEquals(listOf("tap"), mano.entradas, promesa(318))
            assertTrue(dijo.startsWith("paraste:"), promesa(318) + " · «$dijo»")
        }
    }
}
