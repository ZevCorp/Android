package graph.core.graph

import graph.core.domain.BrainTurn
import graph.core.domain.GraphLog
import graph.core.domain.ScreenState
import graph.core.domain.ThreadedBrain
import kotlinx.coroutines.delay

private val NO_LOG = GraphLog { _, _ -> }

/**
 * EL CEREBRO VIVE EN GRAPH. Este cerebro no piensa: manda la pantalla a `POST /api/v1/agent/turn`
 * y ejecuta lo que Graph decide, igual que el cliente Windows (`AgentLoop.cs` + `BackendClient.cs`).
 * No hay otra llamada de red con inteligencia: si Graph no responde, el cliente no sabe pensar por
 * su cuenta — a propósito.
 *
 * Diferencia deliberada con Windows: los HTTP transitorios (0, 408, 429, 502, 503, 504) se
 * reintentan hasta 3 veces con espera 800/1600/3200 ms. La red móvil se cae al cambiar de celda o
 * de wifi a datos y casi siempre sale bien al segundo intento; en escritorio no vale la espera.
 */
class GraphBrain(
    private val transport: TurnTransport,
    private val credentials: () -> String,
    private val baseUrl: () -> String,
    private val userId: () -> String?,
    private val email: () -> String?,
    private val deviceId: () -> String?,
    private val listApps: () -> List<String>,
    private val log: GraphLog = NO_LOG,
    /** Espera entre reintentos; inyectable para que el contrato no duerma. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : ThreadedBrain {

    override val interactionId: String get() = TODO("pendiente")
    override val hasPendingCalls: Boolean get() = false
    override val totalTokens: Int get() = 0

    override fun resume(id: String): Unit = TODO("pendiente")
    override fun begin(goal: String): Unit = TODO("pendiente")
    override fun inform(message: String): Unit = TODO("pendiente")
    override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn = TODO("pendiente")
}
