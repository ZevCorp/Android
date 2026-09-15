package com.zevcorp.graph

import com.zevcorp.graph.platform.AndroidSystemApi
import com.zevcorp.graph.platform.GraphAccessibilityService
import com.zevcorp.graph.platform.LogBus
import graph.core.application.ExecutionEngine
import graph.core.domain.Brain
import graph.core.domain.ExecutionMode
import graph.core.domain.LearnedTool
import graph.core.domain.Mcp
import graph.core.domain.McpTool
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import graph.core.precision.ArmadoDeEjecucion
import graph.core.precision.Freno
import kotlinx.coroutines.launch

/**
 * LA EJECUCIÓN DE LA APP (spec 003, fase 3B). Tres cosas y ninguna más:
 *  1. Es el ÚNICO sitio que entrega las manos crudas —el servicio de accesibilidad y `AndroidSystemApi`— y las
 *     entrega al [ArmadoDeEjecucion], que las cierra detrás de la puerta. Toda corrida se arma con [arma].
 *  2. Tiene UN [Freno] por proceso. Toda corrida entra por [correr]: sin tarea abierta la puerta no toca nada.
 *  3. [parar] es el único alto: la píldora, la notificación, el botón y, a futuro, la voz lo piden aquí.
 */
object Ejecucion {
    /**
     * Lo que se deja a la corrida soltar sola tras el alto antes de cortar su trabajo. Suelta en milisegundos
     * salvo que esté esperando un turno de Graph en vuelo; ese es el único caso que llega a cortarse.
     */
    private const val GRACIA_MS = 1_500L

    private val freno = Freno(log = LogBus, avisa = { texto -> bubble()?.speak(texto) })
    private val armado = ArmadoDeEjecucion(freno, LogBus)

    private fun bubble() = (GraphApp.instance.ui as? GraphAccessibilityService)?.bubble

    /** ¿Hay una corrida en marcha? */
    val enCurso: Boolean get() = armado.enCurso

    /** Pide el alto de la corrida en curso y, si no suelta en [GRACIA_MS], corta su trabajo (después del alto, nunca en su lugar). */
    fun parar(porque: String) {
        LogBus.log("app", "⏹ alto pedido por $porque")
        armado.parar(porque)
        if (armado.enCurso) GraphApp.instance.scope.launch { armado.cortaSiNoSuelta(GRACIA_MS) }
    }

    /** Corre [bloque] como la corrida [nombre]: abre la tarea, y con alto termina en `Paraste` (una cancelación). */
    suspend fun <T> correr(nombre: String, bloque: suspend () -> T): T = armado.correr(nombre, bloque)

    /** Con el alto pedido lanza `Paraste`: tras un motor, quien tiene algo más que hacer lo mira primero. */
    fun sigue() = armado.sigue()

    /** Un paso consciente de un workflow; si lo paras dentro, la corrida entera termina. */
    suspend fun pasoConsciente(objetivo: String, motor: ExecutionEngine): Boolean = armado.pasoConsciente(objetivo, motor)

    /** Arma una corrida sobre la puerta: el motor, el MCP y, si hay [workflows], su reproductor. */
    fun <B : Brain> arma(
        service: GraphAccessibilityService,
        cerebro: (Mcp) -> B,
        voz: Voice,
        usuario: UserChannel? = null,
        maxTurnos: Int = 40,
        modo: ExecutionMode? = null,
        pausa: () -> Long = { 350 },
        aprendidas: List<LearnedTool> = emptyList(),
        workflows: ArmadoDeEjecucion.Workflows? = null,
    ): ArmadoDeEjecucion.Sesion<B> =
        armado.arma(manos(service), cerebro, voz, usuario, maxTurnos, modo, pausa, aprendidas, workflows)

    /** El catálogo MCP de una corrida, sin ejecutar nada. */
    fun herramientas(service: GraphAccessibilityService, aprendidas: List<LearnedTool>): List<McpTool> =
        armado.herramientas(manos(service), aprendidas)

    private fun manos(service: GraphAccessibilityService) =
        ArmadoDeEjecucion.Manos(service, service, AndroidSystemApi(service), service)
}
