package graph.core.precision

import graph.core.application.ExecutionEngine
import graph.core.domain.Brain
import graph.core.domain.ExecutionMode
import graph.core.domain.Gestures
import graph.core.domain.GraphLog
import graph.core.domain.LearnedTool
import graph.core.domain.Mcp
import graph.core.domain.McpTool
import graph.core.domain.Phone
import graph.core.domain.SystemApi
import graph.core.domain.UiPlayer
import graph.core.domain.UserChannel
import graph.core.domain.Voice
import graph.core.domain.Workflow
import graph.core.domain.WorkflowStep

/** Esqueleto (spec 003, fase 3B): solo firmas para que el contrato compile. */
class ArmadoDeEjecucion(
    val freno: Freno,
    private val log: GraphLog = GraphLog { _, _ -> },
) {
    class Manos(val telefono: Phone, val gestos: Gestures, val sistema: SystemApi, val reproductor: UiPlayer?)

    class Workflows(
        val lista: List<Workflow>,
        val elementos: suspend () -> List<String>,
        val consciente: suspend (Workflow, WorkflowStep, String) -> Boolean,
    )

    class Sesion<B : Brain>(val motor: ExecutionEngine, val mcp: Mcp, val cerebro: B)

    fun <B : Brain> arma(
        manos: Manos,
        cerebro: (Mcp) -> B,
        voz: Voice,
        usuario: UserChannel? = null,
        maxTurnos: Int = 40,
        modo: ExecutionMode? = null,
        pausa: () -> Long = { 350 },
        aprendidas: List<LearnedTool> = emptyList(),
        workflows: Workflows? = null,
    ): Sesion<B> = TODO("3B")

    fun herramientas(manos: Manos, aprendidas: List<LearnedTool>): List<McpTool> = TODO("3B")

    val enCurso: Boolean get() = TODO("3B")

    suspend fun <T> correr(nombre: String, bloque: suspend () -> T): T = TODO("3B")

    fun parar(porque: String): Unit = TODO("3B")

    fun sigue(): Unit = TODO("3B")

    suspend fun cortaSiNoSuelta(graciaMs: Long): Unit = TODO("3B")

    suspend fun pasoConsciente(objetivo: String, motor: ExecutionEngine): Boolean = TODO("3B")
}
