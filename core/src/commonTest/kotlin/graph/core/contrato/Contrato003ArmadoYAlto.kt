package graph.core.contrato

import graph.core.contrato.Contrato001CerebroEnGraph.TransporteGuionado
import graph.core.contrato.Contrato003FrenoYPuerta.Bitacora
import graph.core.contrato.Contrato003FrenoYPuerta.CerebroGuionado
import graph.core.contrato.Contrato003FrenoYPuerta.Mano
import graph.core.contrato.Contrato003FrenoYPuerta.Voz
import graph.core.domain.AgentAction
import graph.core.domain.BrainTurn
import graph.core.domain.Workflow
import graph.core.domain.WorkflowStep
import graph.core.graph.GraphBrain
import graph.core.graph.TransportReply
import graph.core.graph.TurnAction
import graph.core.graph.TurnJson
import graph.core.graph.TurnResponse
import graph.core.graph.TurnTransport
import graph.core.precision.ArmadoDeEjecucion
import graph.core.precision.Freno
import graph.core.precision.Paraste
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * CONTRATO 003, FASE 3B — EL ARMADO Y EL ALTO (docs/specs/003-lo-hace-a-la-primera-y-se-puede-parar.md).
 *
 * Se juzga la corrida tal como la arma la app: el [ArmadoDeEjecucion] real con el freno real, la puerta
 * real y, en la 309, el [GraphBrain] real sobre un transporte que cuenta requests. Lo único falso son las
 * manos (graban cada entrada) y la red.
 */
class Contrato003ArmadoYAlto {

    companion object {
        val PROMESAS = mapOf(
            309 to "Un alto a mitad de turno no ejecuta las acciones que faltan ni le pide otro turno a Graph.",
            316 to "Parar dentro de un paso consciente de un workflow para la corrida entera: el workflow no sigue con el paso siguiente.",
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"

        /** Tres pasos: consciente, subconsciente y consciente. El de en medio se toca por etiqueta, sin modelo. */
        val FLUJO = Workflow(
            "ajustes", "abre ajustes, entra a wifi y luego a bluetooth",
            listOf(
                WorkflowStep("abre ajustes"),
                WorkflowStep("toca Wi-Fi", target = "Wi-Fi", subconscious = true),
                WorkflowStep("entra a bluetooth"),
            ),
        )
    }

    private fun manos(mano: Mano) = ArmadoDeEjecucion.Manos(mano.telefono, mano.gestos, mano.sistema, mano.reproductor)

    /** Graph de verdad: el cerebro remoto real, con la red guionada. */
    private fun graph(transporte: TurnTransport) = GraphBrain(
        transport = transporte,
        credentials = { "miracle_k" },
        baseUrl = { "https://graph.test/" },
        userId = { "u-1" },
        email = { null },
        deviceId = { null },
        listApps = { emptyList() },
    )

    private fun turno(vararg acciones: TurnAction) =
        TransportReply(200, TurnJson.encodeToString(TurnResponse(session = "s-1", actions = acciones.toList())))

    private val fin = TransportReply(200, """{"session":"s-fin","done":true,"text":"listo"}""")

    @Test
    fun promesa309() = corre {
        val casos = listOf(
            // Lo que falta toca el teléfono: la puerta ya lo impediría; el motor no debe ni intentarlo.
            "[tap, tap, type]" to arrayOf(TurnAction(kind = "tap", x = 1, y = 1), TurnAction(kind = "tap", x = 2, y = 2), TurnAction(kind = "type", x = 3, y = 3, text = "hola")),
            // Lo que falta NO toca el teléfono: aquí solo el freno del motor impide seguir y pedir otro turno.
            "[tap, wait, wait]" to arrayOf(TurnAction(kind = "tap", x = 1, y = 1), TurnAction(kind = "wait", ms = 30), TurnAction(kind = "wait", ms = 30)),
        )
        for ((caso, acciones) in casos) {
            val bitacora = Bitacora()
            val freno = Freno(log = bitacora)
            val armado = ArmadoDeEjecucion(freno, bitacora)
            val mano = Mano(alEntrar = { if (it == "tap") armado.parar("píldora") })
            val transporte = TransporteGuionado(turno(*acciones), fin)
            val sesion = armado.arma(manos(mano), cerebro = { graph(transporte) }, voz = Voz(), pausa = { 0 })

            val salida = runCatching { armado.correr("abre ajustes") { sesion.motor.run("abre ajustes") } }

            assertIs<Paraste>(salida.exceptionOrNull(), promesa(309) + " · $caso · la corrida no terminó como cancelación: $salida")
            assertEquals(listOf("tap"), mano.entradas, promesa(309) + " · $caso · llegaron al teléfono acciones tras el alto")
            val ejecutadas = bitacora.lineas.filter { it.startsWith("run:") && "▪" in it }
            assertEquals(1, ejecutadas.size, promesa(309) + " · $caso · ejecutó acciones tras el alto: $ejecutadas")
            assertEquals(1, transporte.requests.size, promesa(309) + " · $caso · le pidió otro turno a Graph tras el alto")
            assertFalse(freno.abierta, promesa(309) + " · $caso · la corrida no soltó el freno")
        }
    }

    /** Lo que deja una corrida que llama al workflow [FLUJO] como herramienta MCP del motor armado. */
    private class Escena(
        val salida: Result<String>,
        val mano: Mano,
        val freno: Freno,
        val bitacora: Bitacora,
        val avisos: List<String>,
        val lecturas: Int,
        val conscientes: List<String>,
        val turnosDeFuera: Int,
    )

    private suspend fun corridaConWorkflow(pararEnElPaso1: Boolean): Escena {
        val bitacora = Bitacora()
        val avisos = mutableListOf<String>()
        val freno = Freno(log = bitacora, avisa = { avisos += it })
        val armado = ArmadoDeEjecucion(freno, bitacora)
        val mano = Mano(alEntrar = { if (pararEnElPaso1 && it == "tap") armado.parar("píldora") })
        val manos = manos(mano)
        var lecturas = 0
        val conscientes = mutableListOf<String>()
        val workflows = ArmadoDeEjecucion.Workflows(
            lista = listOf(FLUJO),
            elementos = { lecturas++; listOf("Wi-Fi") },
            consciente = { _, paso, _ ->
                conscientes += paso.action
                val cerebro = CerebroGuionado(
                    BrainTurn(actions = listOf(AgentAction.Tap(5, 5))),
                    BrainTurn(done = true, text = "hecho: ${paso.action}"),
                )
                armado.pasoConsciente(paso.action, armado.arma(manos, { cerebro }, Voz(), maxTurnos = 8, pausa = { 0 }).motor)
            },
        )
        val deFuera = CerebroGuionado(
            BrainTurn(actions = listOf(AgentAction.Mcp("workflow_ajustes", mapOf("context" to "")))),
            BrainTurn(done = true, text = "listo"),
        )
        val sesion = armado.arma(manos, { deFuera }, Voz(), pausa = { 0 }, workflows = workflows)
        val objetivo = "abre ajustes y entra a wifi y luego a bluetooth"
        val salida = runCatching { armado.correr(objetivo) { sesion.motor.run(objetivo) } }
        return Escena(salida, mano, freno, bitacora, avisos, lecturas, conscientes, deFuera.turnos)
    }

    @Test
    fun promesa316() = corre {
        // Sin alto: los tres pasos llegan. El paso consciente corre en una tarea anidada y no cierra la corrida.
        run {
            val e = corridaConWorkflow(pararEnElPaso1 = false)
            assertEquals("listo", e.salida.getOrNull(), promesa(316) + " · sin alto la corrida no terminó bien: ${e.salida}")
            assertEquals(listOf("tap", "tapLabel", "tap"), e.mano.entradas,
                promesa(316) + " · el paso consciente anidado cerró la corrida de fuera: ${e.bitacora.lineas}")
            assertTrue(e.bitacora.lineas.none { it.startsWith("puerta: sin tarea abierta") }, promesa(316) + " · ${e.bitacora.lineas}")
            assertFalse(e.freno.abierta, promesa(316))
        }

        // Con el alto durante el tap del paso 1: la corrida entera para ahí.
        run {
            val e = corridaConWorkflow(pararEnElPaso1 = true)
            assertIs<Paraste>(e.salida.exceptionOrNull(), promesa(316) + " · la corrida no terminó como cancelación: ${e.salida}")
            assertEquals(listOf("tap"), e.mano.entradas, promesa(316) + " · llegó algo tras el alto")
            assertEquals(listOf("abre ajustes"), e.conscientes, promesa(316) + " · el workflow siguió con otro paso consciente")
            assertEquals(1, e.lecturas, promesa(316) + " · el workflow volvió a mirar la pantalla para el paso siguiente")
            val pasos = e.bitacora.lineas.filter { it.startsWith("workflow:") }
            assertTrue(pasos.none { "1/3" in it || "consciente falló" in it || "■" in it },
                promesa(316) + " · el workflow trató la parada como un paso hecho o fallido y siguió: $pasos")
            assertEquals(1, e.turnosDeFuera, promesa(316) + " · Graph dio otro turno a la corrida parada")
            assertFalse(e.freno.abierta, promesa(316) + " · la corrida parada no soltó el freno")
            assertEquals(1, e.avisos.count { it == Contrato003FrenoYPuerta.LISTO }, promesa(316) + " · ${e.avisos}")
        }
    }
}
