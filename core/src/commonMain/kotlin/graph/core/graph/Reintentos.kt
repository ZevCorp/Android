package graph.core.graph

import graph.core.domain.GraphLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * LOS REINTENTOS DE GRAPH, una sola vez para el turno (spec 001) y para el aprendizaje (spec 004): «se
 * reintenta como en el cerebro» (promesa 403) es este código, no una copia que se desincroniza.
 *
 * Las reglas (spec 001, promesas 6, 13 y 14):
 *  - transitorios: 0, 408, 429, 502, 503, 504 (`GraphClient.cs:18`: pasarela, arranque en frío del
 *    serverless o la espera del cliente); hasta [MAX_REINTENTOS] reintentos con 800 / 1600 / 3200 ms, y un
 *    429 con `Retry-After` espera eso, hasta [RETRY_AFTER_CAP_S];
 *  - todo, intentos y esperas, dentro de un tope: un reintento que no cabe no se hace, y cada intento se
 *    corta en lo que le queda (cortado, cuenta como lectura agotada);
 *  - una lectura agotada (`-1`) no se reintenta: Graph pudo haber recibido, y cobrado, la llamada;
 *  - una excepción del transporte cuenta como `0` con su mensaje; una cancelación sale tal cual.
 */
internal object Reintentos {
    val TRANSITORIOS = setOf(0, 408, 429, 502, 503, 504)
    const val MAX_REINTENTOS = 3
    const val BACKOFF_MS = 800L
    const val RETRY_AFTER_CAP_S = 10

    fun esTransitorio(status: Int): Boolean = status in TRANSITORIOS

    /** Un 429 con `Retry-After` espera eso, hasta [RETRY_AFTER_CAP_S]; el resto, backoff creciente. */
    fun espera(reply: TransportReply, reintento: Int): Long =
        reply.retryAfterSeconds?.takeIf { reply.status == 429 && it >= 0 }?.let { minOf(it, RETRY_AFTER_CAP_S) * 1000L }
            ?: (BACKOFF_MS shl reintento)

    /** El tope como lo lee una persona: «6 min», «90 s», «200 ms». */
    fun corto(tope: Duration): String {
        val ms = tope.inWholeMilliseconds
        return when {
            ms > 0 && ms % 60_000 == 0L -> "${tope.inWholeMinutes} min"
            ms % 1000 == 0L -> "${tope.inWholeSeconds} s"
            else -> "$ms ms"
        }
    }
}

/** Lo que dejó una llamada: la última respuesta, cuántos intentos llevó y si la cortó el tope. */
internal class Envio(val reply: TransportReply, val intentos: Int, val topado: Boolean)

/**
 * Llama con los reintentos de [Reintentos] dentro de [tope]. [intento] recibe lo que le queda al tope: es
 * el tope de lectura que el transporte puede usar. [etiqueta] es la del log y [deQue] («del turno», «de
 * la llamada») completa los mensajes.
 */
internal suspend fun enviarConReintentos(
    tope: Duration,
    timeSource: TimeSource,
    sleep: suspend (Long) -> Unit,
    log: GraphLog,
    etiqueta: String,
    deQue: String,
    maxReintentos: Int = Reintentos.MAX_REINTENTOS,
    intento: suspend (queda: Duration) -> TransportReply,
): Envio {
    val deadline = timeSource.markNow() + tope
    fun queda(): Duration = -deadline.elapsedNow()

    // Un intento, cortado en lo que le queda al tope: si se agota, es una lectura agotada y no se reintenta.
    suspend fun uno(): TransportReply {
        val restante = queda()
        return withTimeoutOrNull(restante) {
            try {
                intento(restante)
            } catch (e: CancellationException) {
                throw e // cancelar la corrida (o agotar el tope) no es un fallo de red
            } catch (e: Exception) {
                TransportReply(TransportReply.NOT_CONNECTED, e.message ?: e::class.simpleName ?: "sin red")
            }
        } ?: TransportReply(TransportReply.TIMED_OUT, "se agotó el tope de ${Reintentos.corto(tope)} $deQue")
    }

    var reply = uno()
    var intentos = 1
    while (Reintentos.esTransitorio(reply.status) && intentos <= maxReintentos) {
        val espera = Reintentos.espera(reply, reintento = intentos - 1)
        if (espera.milliseconds >= queda()) {
            log.log(etiqueta, "HTTP ${reply.status} · sin reintento: no cabe en el tope de ${Reintentos.corto(tope)} $deQue")
            return Envio(reply, intentos, topado = true)
        }
        log.log(etiqueta, "HTTP ${reply.status} transitorio · reintento $intentos/$maxReintentos en ${espera}ms")
        sleep(espera)
        intentos++
        reply = uno()
    }
    return Envio(reply, intentos, topado = false)
}
