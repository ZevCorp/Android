package com.zevcorp.graph

import android.content.res.Resources
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
import graph.core.precision.CuentaDePeticion
import graph.core.precision.Freno
import graph.core.precision.TopeDeIntentos
import kotlinx.coroutines.launch

/**
 * LA EJECUCIÓN DE LA APP (spec 003, fase 3B). Tres cosas y ninguna más:
 *  1. Es el ÚNICO sitio que entrega las manos crudas —el servicio de accesibilidad y `AndroidSystemApi`— y las
 *     entrega al [ArmadoDeEjecucion], que las cierra detrás de la puerta. Toda corrida se arma con [arma].
 *  2. Tiene UN [Freno], UN tope de intentos y UNA cuenta de petición por proceso, y los da al armado: todas las puertas
 *     que arma los comparten. Toda corrida entra por [correr]: sin tarea abierta la puerta no toca nada, y cada corrida
 *     es una petición (spec 003, promesa 320).
 *  3. [parar] es el único alto: la píldora, la notificación, el botón y, a futuro, la voz lo piden aquí. Cuándo
 *     cortar una corrida que no suelta lo decide el armado (core, juzgado por comportamiento); la app solo le da
 *     su scope para lanzar ese corte.
 */
object Ejecucion {
    private val freno = Freno(log = LogBus, avisa = { texto -> bubble()?.speak(texto) })
    /** Dos intentos y no tres por petición. La celda de 48 dp a la densidad de la pantalla, sin esperar a la app. */
    private val tope = TopeDeIntentos(TopeDeIntentos.celdaPx(Resources.getSystem().displayMetrics.density))
    /** La medida de cada petición: su línea `peticion:` sale al acabar cada corrida. */
    private val cuenta = CuentaDePeticion(log = LogBus)
    private val armado = ArmadoDeEjecucion(freno, LogBus, lanza = { corte -> GraphApp.instance.scope.launch { corte() } }, tope = tope, cuenta = cuenta)

    private fun bubble() = (GraphApp.instance.ui as? GraphAccessibilityService)?.bubble

    /** ¿Hay una corrida en marcha? */
    val enCurso: Boolean get() = armado.enCurso

    /** Pide el alto de la corrida en curso; si no suelta, el armado corta su trabajo pasada la gracia (después del alto, nunca en su lugar). */
    fun parar(porque: String) {
        LogBus.log("app", "⏹ alto pedido por $porque")
        armado.parar(porque)
    }

    /**
     * Corre [bloque] como la corrida de fuera de [pedido]: abre la tarea; con otra abierta lanza `CorridaEnCurso` sin
     * correrlo; con alto termina en `Paraste` (una cancelación). El pedido no se nombra en el log, solo su largo.
     */
    suspend fun <T> correr(pedido: String, bloque: suspend () -> T): T = armado.correr(pedido, bloque)

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
        apps: suspend () -> List<String> = { emptyList() },
    ): ArmadoDeEjecucion.Sesion<B> =
        armado.arma(manos(service), cerebro, voz, usuario, maxTurnos, modo, pausa, aprendidas, workflows, apps)

    /** El catálogo MCP de una corrida, sin ejecutar nada. */
    fun herramientas(service: GraphAccessibilityService, aprendidas: List<LearnedTool>): List<McpTool> =
        armado.herramientas(manos(service), aprendidas)

    private fun manos(service: GraphAccessibilityService) =
        ArmadoDeEjecucion.Manos(service, service, AndroidSystemApi(service), service)
}
