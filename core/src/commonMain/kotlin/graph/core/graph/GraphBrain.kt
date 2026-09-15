package graph.core.graph

import graph.core.domain.BrainTurn
import graph.core.domain.GraphLog
import graph.core.domain.ScreenState
import graph.core.domain.ThreadedBrain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
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
 *  - las apps instaladas se consultan una vez por corrida, en su primer turno, y viajan en todos
 *    (promesa 15): pedirlas en cada turno repetía una consulta cara que no cambia en segundos;
 *  - un `error` en el cuerpo termina el turno con ese texto, también con HTTP 200.
 *
 * Diferencia deliberada con Windows: los HTTP transitorios (0, 408, 429, 502, 503, 504) se
 * reintentan hasta 3 veces con espera 800/1600/3200 ms (un 429 con `Retry-After` espera eso, hasta
 * 10 s). La red móvil se cae al cambiar de celda o de wifi a datos y casi siempre sale bien al
 * segundo intento; en escritorio no vale la espera. Con dos límites:
 *  - el turno entero, intentos y esperas, no pasa de 6 min (promesa 13): un reintento que no cabe no
 *    se hace, y cada intento se corta en lo que le queda al turno;
 *  - una lectura agotada (`-1`: conectó y Graph no respondió a tiempo) no se reintenta, porque Graph
 *    pudo haber recibido y cobrado el turno. Solo se reintenta lo que no llegó a conectar (`0`).
 * Cancelar la corrida en medio de un POST no es red caída: la cancelación sale tal cual (promesa 14).
 */
class GraphBrain(
    private val transport: TurnTransport,
    private val credentials: () -> String,
    private val baseUrl: () -> String,
    private val userId: () -> String?,
    private val email: () -> String?,
    private val deviceId: () -> String?,
    /** Las apps instaladas. `suspend` para que la app la saque del hilo principal; se llama una vez por corrida. */
    private val listApps: suspend () -> List<String>,
    private val log: GraphLog = NO_LOG,
    /** Espera entre reintentos; inyectable para que el contrato no duerma. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** El reloj del turno; inyectable para que el contrato mida sin esperar. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ThreadedBrain {

    private var session: String? = null
    private var goal = ""
    private var firstTurn = true
    private var pendingInform: String? = null
    private var wantShot = false
    private var turns = 0
    /** Las apps de esta corrida: se consultan en el primer `next` y se reinician en `begin` (promesa 15). */
    private var apps: List<String>? = null

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
        apps = null // corrida nueva, consulta nueva: entra la app que se instaló entre una y otra
    }

    override fun inform(message: String) { pendingInform = message }

    override suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn {
        val key = credentials().trim()
        check(key.isNotEmpty()) { GraphCredentials.FALTA }
        val installed = apps ?: listApps().also { apps = it }

        val request = TurnRequest(
            session = session,
            // El objetivo va una sola vez: en el primer turno de la corrida, que siempre abre hilo.
            // Repetirlo abriría una conversación nueva en cada turno.
            goal = if (firstTurn) goal else null,
            userId = userId()?.ifBlank { null },
            state = state.toTurnState(
                apps = installed.ifEmpty { null },
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
        val started = timeSource.markNow()
        val posted = post(url, body, headers)
        val reply = posted.reply
        val response = parse(posted)
        firstTurn = false
        session = response.session.ifEmpty { session }
        wantShot = response.needsScreenshot
        val turn = response.toBrainTurn()
        log.log("graph", "turno $turns · session=$hilo · HTTP ${reply.status} · ${started.elapsedNow().inWholeMilliseconds}ms · ${turn.actions.size} acciones")
        return turn
    }

    /** Lo que dejó el POST: la última respuesta, cuántos intentos llevó y si lo cortó el tope del turno. */
    private class Posted(val reply: TransportReply, val attempts: Int, val capped: Boolean)

    /**
     * El POST con los reintentos de los transitorios, dentro del tope del turno. Una excepción del
     * transporte cuenta como HTTP 0 y lleva su mensaje; una cancelación no: sale tal cual.
     */
    private suspend fun post(url: String, body: String, headers: Map<String, String>): Posted {
        val deadline = timeSource.markNow() + TURN_CAP
        var reply = intento(url, body, headers, deadline)
        var attempts = 1
        while (reply.status in TRANSIENT && attempts <= MAX_RETRIES) {
            val wait = waitFor(reply, retry = attempts - 1)
            if (wait.milliseconds >= remaining(deadline)) {
                log.log("graph", "HTTP ${reply.status} · sin reintento: no cabe en el tope de ${TURN_CAP.inWholeMinutes} min del turno")
                return Posted(reply, attempts, capped = true)
            }
            log.log("graph", "HTTP ${reply.status} transitorio · reintento $attempts/$MAX_RETRIES en ${wait}ms")
            sleep(wait)
            attempts++
            reply = intento(url, body, headers, deadline)
        }
        return Posted(reply, attempts, capped = false)
    }

    /** Un intento, cortado en lo que le queda al turno: si se agota, es una lectura agotada y no se reintenta. */
    private suspend fun intento(url: String, body: String, headers: Map<String, String>, deadline: TimeMark): TransportReply =
        withTimeoutOrNull(remaining(deadline)) {
            try {
                transport.post(url, body, headers)
            } catch (e: CancellationException) {
                throw e // cancelar la corrida (o agotar el tope) no es un fallo de red
            } catch (e: Exception) {
                TransportReply(TransportReply.NOT_CONNECTED, e.message ?: e::class.simpleName ?: "sin red")
            }
        } ?: TransportReply(TransportReply.TIMED_OUT, "se agotó el tope de ${TURN_CAP.inWholeMinutes} min del turno")

    private fun remaining(deadline: TimeMark): Duration = -deadline.elapsedNow()

    /** Un 429 con `Retry-After` espera eso, hasta [RETRY_AFTER_CAP_S]; el resto, backoff creciente. */
    private fun waitFor(reply: TransportReply, retry: Int): Long =
        reply.retryAfterSeconds?.takeIf { reply.status == 429 && it >= 0 }?.let { minOf(it, RETRY_AFTER_CAP_S) * 1000L }
            ?: (BACKOFF_MS shl retry)

    private fun parse(posted: Posted): TurnResponse {
        val reply = posted.reply
        if (reply.status == 401 || reply.status == 403)
            throw IllegalStateException("la key de graph no vale (HTTP ${reply.status})")
        if (reply.status == TransportReply.TIMED_OUT)
            throw IllegalStateException("graph no respondió a tiempo (${reply.body.ifBlank { "sin causa" }}); no se reintentó para no cobrar dos veces")
        if (reply.status in TRANSIENT) {
            val cuando = if (posted.capped) "dentro del tope de ${TURN_CAP.inWholeMinutes} min del turno, tras ${posted.attempts} intentos"
                else "tras ${posted.attempts} intentos"
            throw IllegalStateException("graph no respondió (HTTP ${reply.status}) $cuando" + (causa(reply.body)?.let { ": $it" } ?: ""))
        }
        val decoded = runCatching { TurnJson.decodeFromString(TurnResponse.serializer(), reply.body) }
        val parsed = decoded.getOrNull()
        if (parsed == null) {
            if (reply.status !in 200..299) throw IllegalStateException("graph HTTP ${reply.status}: ${reply.body.take(200)}")
            if (reply.body.isBlank()) throw IllegalStateException("respuesta vacía de graph (HTTP ${reply.status})")
            throw IllegalStateException("graph respondió algo que no se pudo leer en ${ruta(decoded.exceptionOrNull())} (HTTP ${reply.status}): ${reply.body.take(200)}")
        }
        parsed.error?.takeIf { it.isNotBlank() }?.let { throw IllegalStateException(it) }
        if (reply.status !in 200..299) throw IllegalStateException("graph HTTP ${reply.status}")
        return parsed
    }

    /** La causa de un transitorio: el `error` del cuerpo si Graph lo mandó; si no, el cuerpo corto (con status 0, el mensaje de la excepción). */
    private fun causa(body: String): String? =
        runCatching { TurnJson.decodeFromString(TurnResponse.serializer(), body).error }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: body.trim().take(200).ifBlank { null }

    /** Dónde se rompió la lectura, según kotlinx («… at path: $.actions[0].args»); sin ruta, el cuerpo entero (`$`). */
    private fun ruta(e: Throwable?): String =
        e?.message?.let { Regex("at path: (\\S+)").find(it)?.groupValues?.get(1) } ?: "\$"

    private companion object {
        const val TURN_PATH = "/api/v1/agent/turn"
        /** Ver `GraphClient.cs:18`: pasarela, arranque en frío del serverless o la espera del cliente. */
        val TRANSIENT = setOf(0, 408, 429, 502, 503, 504)
        const val MAX_RETRIES = 3
        const val BACKOFF_MS = 800L
        /** El turno entero, intentos y esperas: más que esto, el usuario ya se fue. */
        val TURN_CAP = 6.minutes
        const val RETRY_AFTER_CAP_S = 10
    }
}
