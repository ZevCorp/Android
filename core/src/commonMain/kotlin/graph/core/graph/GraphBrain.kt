package graph.core.graph

import graph.core.domain.BrainTurn
import graph.core.domain.GraphLog
import graph.core.domain.ScreenState
import graph.core.domain.ThreadedBrain
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

private val NO_LOG = GraphLog { _, _ -> }

/**
 * EL CEREBRO VIVE EN GRAPH. Este cerebro no piensa: manda la pantalla a `POST /api/v1/agent/turn`
 * y ejecuta lo que Graph decide, igual que el cliente Windows (`AgentLoop.cs` + `BackendClient.cs`).
 * No hay otra llamada de red con inteligencia: si Graph no responde, el cliente no sabe pensar por
 * su cuenta — a propósito.
 *
 * Reglas del bucle, copiadas de Windows (docs/specs/001):
 *  - el objetivo viaja en el primer turno; después viaja el `session` opaco que devolvió Graph;
 *  - cada objetivo abre un hilo nuevo: el primer turno de cada corrida viaja sin `session`
 *    (`AgentLoop.cs:96`), aunque haya uno de la corrida anterior o uno reanudado (promesa 12);
 *  - `results` van en el mismo orden que las acciones; la respuesta a una pregunta va en `inform`,
 *    aparte y una sola vez (Graph la enruta al `ask_user` pendiente; en `results` se perdería);
 *  - la captura viaja solo si el turno anterior pidió `needsScreenshot`;
 *  - un `error` en el cuerpo termina el turno con ese texto, también con HTTP 200.
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

    private var session: String? = null
    private var goal = ""
    private var firstTurn = true
    private var pendingInform: String? = null
    private var wantShot = false
    private var turns = 0

    /** El `session` opaco de Graph hace de hilo: es lo que el composition root persiste entre activaciones. */
    override val interactionId: String get() = session ?: ""
    /** Graph resuelve sus llamadas del lado del servidor: aquí nunca queda una colgada. */
    override val hasPendingCalls: Boolean get() = false
    /** Graph no reporta tokens en el contrato del turno; la rotación de ventana no aplica. */
    override val totalTokens: Int get() = 0

    /**
     * No-op a propósito: con Graph no se reanuda un hilo entre activaciones. Medido en el teléfono
     * (spec 001, Nivel 4): con el `session` de la corrida anterior y un `goal` nuevo en el mismo
     * request, Graph siguió el hilo viejo e ignoró el objetivo («abre los ajustes» terminó en «la
     * calculadora está abierta»). Windows nunca reanuda; aquí tampoco (promesa 12). La respuesta a una
     * pregunta dentro de la MISMA corrida no necesita reanudar: viaja en `inform` sobre el hilo vivo.
     */
    override fun resume(id: String) = Unit

    override fun begin(goal: String) {
        this.goal = goal
        session = null // objetivo nuevo, hilo nuevo (promesa 12)
        firstTurn = true
        pendingInform = null
        wantShot = false
        turns = 0
    }

    override fun inform(message: String) { pendingInform = message }

    override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
        val key = credentials().trim()
        check(key.isNotEmpty()) { GraphCredentials.FALTA }

        val request = TurnRequest(
            session = session,
            // El objetivo va una sola vez: en el primer turno de la corrida, que siempre abre hilo.
            // Repetirlo abriría una conversación nueva en cada turno.
            goal = if (firstTurn) goal else null,
            userId = userId()?.ifBlank { null },
            state = state.toTurnState(
                apps = listApps().ifEmpty { null },
                surface = AndroidSurface.from(state.screen),
                withScreenshot = wantShot,
            ),
            results = actionResults,
            inform = pendingInform,
        )
        pendingInform = null // se consume una vez, haya salido bien o no el POST
        val body = TurnJson.encodeToString(TurnRequest.serializer(), request)
        val headers = GraphHeaders.build(key, email(), deviceId())
        val url = baseUrl().trimEnd('/') + TURN_PATH

        turns++
        val hilo = if (request.session == null) "nuevo" else "continúa" // nunca el contenido del session
        val started = TimeSource.Monotonic.markNow()
        val reply = post(url, body, headers)
        val response = parse(reply)
        firstTurn = false
        session = response.session.ifEmpty { session }
        wantShot = response.needsScreenshot
        val turn = response.toBrainTurn()
        log.log("graph", "turno $turns · session=$hilo · HTTP ${reply.status} · ${started.elapsedNow().inWholeMilliseconds}ms · ${turn.actions.size} acciones")
        return turn
    }

    /** El POST con los reintentos de los transitorios. Una excepción del transporte cuenta como HTTP 0. */
    private suspend fun post(url: String, body: String, headers: Map<String, String>): TransportReply {
        var reply = intento(url, body, headers)
        var retry = 0
        while (reply.status in TRANSIENT && retry < MAX_RETRIES) {
            val wait = BACKOFF_MS shl retry
            log.log("graph", "HTTP ${reply.status} transitorio · reintento ${retry + 1}/$MAX_RETRIES en ${wait}ms")
            sleep(wait)
            retry++
            reply = intento(url, body, headers)
        }
        return reply
    }

    private suspend fun intento(url: String, body: String, headers: Map<String, String>): TransportReply =
        try {
            transport.post(url, body, headers)
        } catch (e: Exception) {
            TransportReply(0, e.message ?: "sin red")
        }

    private fun parse(reply: TransportReply): TurnResponse {
        if (reply.status == 401 || reply.status == 403)
            throw IllegalStateException("la key de graph no vale (HTTP ${reply.status})")
        if (reply.status in TRANSIENT)
            throw IllegalStateException("graph no respondió (HTTP ${reply.status}) tras ${MAX_RETRIES + 1} intentos")
        val parsed = runCatching { TurnJson.decodeFromString(TurnResponse.serializer(), reply.body) }.getOrNull()
        if (parsed == null) {
            if (reply.status !in 200..299) throw IllegalStateException("graph HTTP ${reply.status}: ${reply.body.take(200)}")
            throw IllegalStateException("respuesta vacía de graph (HTTP ${reply.status})")
        }
        parsed.error?.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
        if (reply.status !in 200..299) throw IllegalStateException("graph HTTP ${reply.status}")
        return parsed
    }

    private companion object {
        const val TURN_PATH = "/api/v1/agent/turn"
        /** Ver `GraphClient.cs:18`: pasarela, arranque en frío del serverless o la espera del cliente. */
        val TRANSIENT = setOf(0, 408, 429, 502, 503, 504)
        const val MAX_RETRIES = 3
        const val BACKOFF_MS = 800L
    }
}
