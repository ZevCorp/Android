package graph.core.graph.learning

import graph.core.domain.GraphLog
import graph.core.graph.Envio
import graph.core.graph.GraphCredentials
import graph.core.graph.GraphHeaders
import graph.core.graph.Reintentos
import graph.core.graph.TransportReply
import graph.core.graph.TurnTransport
import graph.core.graph.enviarConReintentos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val NO_LOG = GraphLog { _, _ -> }

/** Una llamada de aprendizaje que Graph no completó, con el mensaje que Graph dio y el status. */
open class GraphException(message: String, val status: Int) : Exception(message) {
    /** ¿Puede salir bien al reintentar? Los mismos status que el turno; una lectura agotada (-1) no. */
    val transitorio: Boolean get() = Reintentos.esTransitorio(status)
}

/**
 * El cierre no llegó tras sus tres intentos, pero LOS PASOS YA ESTÁN EN GRAPH: se mandaron uno a uno
 * mientras se grababa. Lo que falta es el post-procesado (título, resumen, guía), que en flujos largos se
 * pasa del tiempo de Vercel (504). Lleva la sesión para que quien llama la guarde y reintente sin volver a
 * grabar (4C). Espejo de `FinishPendingException` de Windows.
 */
class FinishPendiente(val sessionId: String, val workflowId: String, message: String, status: Int) : GraphException(message, status)

/**
 * LO ENSEÑADO VIVE EN GRAPH (spec 004). El cliente de aprendizaje y workflows: abre la sesión y le manda
 * pasos, nota y cierre; lista, trae, borra y planifica workflows; y las cuatro rutas de enseñanza por video.
 * Espejo de `GraphClient.cs` (y de `TeachSession.cs` para `/teach/…`): no piensa, no ordena, no guarda nada.
 *
 * Reglas:
 *  - cabeceras: `X-API-Key`, `X-Miracle-App: android_app`, y el id de dispositivo y el email si existen; sin
 *    `X-Miracle-Feature`, salvo `/teach/…`, que va con la del puente consciente (promesa 402);
 *  - reintentos: los del cerebro ([enviarConReintentos]) dentro del tope de la llamada —90 s, o 5 min en
 *    `/teach/…`—; el cierre tiene su propio calendario, 3 intentos con 3 s y 8 s, cada uno con su tope; una
 *    lectura agotada nunca se reintenta (promesa 403);
 *  - errores: [GraphException] con el `error` de Graph y el status. Un `error` en un 2xx también termina la
 *    llamada, y un `""` no cuenta. Sin key no se llama a nadie. La cancelación sale tal cual;
 *  - el id de sesión o de workflow va SIEMPRE en la ruta, escapado: Graph guarda las sesiones en memoria de
 *    un serverless, y otra instancia no conoce una «sesión activa».
 */
class LearningClient(
    private val transport: TurnTransport,
    private val credentials: () -> String,
    private val baseUrl: () -> String,
    private val email: () -> String?,
    private val deviceId: () -> String?,
    private val log: GraphLog = NO_LOG,
    /** Espera entre reintentos; inyectable para que el contrato no duerma. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** El reloj del tope de cada llamada; inyectable para que el contrato mida sin esperar. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val topeGeneral: Duration = TOPE_GENERAL,
    private val topeTeach: Duration = TOPE_TEACH,
) {

    /* ────────────── Grabación ────────────── */

    /** Abre la sesión de grabación. Sin descripción viaja «Workflow sin descripción», como en Windows. Sin id, falla: no se puede enseñar. */
    suspend fun crearSesion(sesion: StartSessionRequest): SessionInfo {
        val cuerpo = StartSessionRequest(
            description = sesion.description.trim().ifEmpty { SIN_DESCRIPCION },
            appId = sesion.appId,
            sourceUrl = sesion.sourceUrl,
            sourceOrigin = sesion.sourceOrigin,
            sourcePathname = sesion.sourcePathname,
            sourceTitle = sesion.sourceTitle,
            context = sesion.context,
        )
        val ruta = "/api/v1/learning/sessions"
        val reply = llamar("POST", ruta, LearningJson.encodeToString(StartSessionRequest.serializer(), cuerpo))
        val info = leer(StartSessionResponse.serializer(), reply, ruta).session
        if (info?.id == null) throw GraphException("graph no devolvió un id de sesión en $ruta (HTTP ${reply.status})", reply.status)
        return info
    }

    /**
     * Registra un paso y devuelve el `step_order` que le dio Graph (0 si no lo dijo). Graph numera por
     * LLEGADA y este cliente no paraleliza ni encola: el orden de los pasos lo garantiza quien llama, de a
     * uno y esperando cada respuesta. Un paso que falla lanza; si eso aborta la grabación lo decide él.
     */
    suspend fun mandarPaso(sessionId: String, paso: StepRequest): Int {
        val ruta = "${sesionRuta(sessionId)}/steps"
        val reply = llamar("POST", ruta, LearningJson.encodeToString(StepRequest.serializer(), paso))
        return leer(StepResponse.serializer(), reply, ruta).step?.stepOrder ?: 0
    }

    /** Lo que el usuario explicó de viva voz. Va ANTES de [terminar]: Graph la usa al post-procesar. */
    suspend fun notaDeContexto(sessionId: String, transcript: String) {
        val cuerpo = ContextNoteRequest(ContextNote.deEntrenamiento(transcript))
        llamar("POST", "${sesionRuta(sessionId)}/context-notes", LearningJson.encodeToString(ContextNoteRequest.serializer(), cuerpo))
    }

    /**
     * Cierra la sesión: Graph post-procesa y persiste el workflow. Tres intentos con 3 s y 8 s entre ellos,
     * solo en transitorios (`WorkflowRecorder.cs:143`); cada intento con su propio tope, porque un 504 de
     * Vercel tarda lo que tarda. Si los tres fallan, [FinishPendiente]. Una lectura agotada no se reintenta
     * y lanza [GraphException]: Graph pudo haber cerrado, y cobrado, la sesión.
     */
    suspend fun terminar(sessionId: String, workflowId: String = sessionId): FinishResponse {
        val ruta = "${sesionRuta(sessionId)}/finish"
        var ultimo: GraphException? = null
        for (intento in 1..CIERRE_INTENTOS) {
            try {
                return leer(FinishResponse.serializer(), llamar("POST", ruta, "{}", reintentos = 0), ruta)
            } catch (e: GraphException) {
                if (!e.transitorio) throw e
                ultimo = e
                log.log(TAG, "cierre de la grabación: intento $intento/$CIERRE_INTENTOS falló (HTTP ${e.status})")
                if (intento < CIERRE_INTENTOS) sleep(CIERRE_ESPERAS_MS[intento - 1])
            }
        }
        val status = ultimo?.status ?: TransportReply.NOT_CONNECTED
        throw FinishPendiente(
            sessionId, workflowId,
            "graph no alcanzó a cerrar la grabación (HTTP $status) tras $CIERRE_INTENTOS intentos: los pasos ya están guardados; " +
                "falta el resumen, y se completa sin volver a grabar" + (ultimo?.message?.let { " · $it" } ?: ""),
            status,
        )
    }

    /* ────────────── Workflows ────────────── */

    /**
     * Los workflows de esta key y los globales, del más nuevo al más viejo: Graph los da al revés
     * (`ORDER BY w.id ASC`) y lo recién enseñado quedaba al final. Sin fecha, por id descendente: los ids
     * de Graph son marcas de tiempo. Espejo de `SelectorDeWorkflows.Ordenar`.
     */
    suspend fun listarWorkflows(): List<WorkflowResumen> {
        val ruta = "/api/v1/workflows"
        val lista = leer(WorkflowListResponse.serializer(), llamar("GET", ruta, null), ruta).workflows
        return lista.map { WorkflowResumen.desdeJson(it) }
            .sortedWith(compareByDescending<WorkflowResumen> { it.creadoEnMs ?: Long.MIN_VALUE }.thenByDescending { it.id })
    }

    /** Un workflow COMPLETO, con los pasos tal como se guardaron (el plan filtra y transforma). */
    suspend fun workflow(id: String): JsonElement {
        val ruta = workflowRuta(id)
        val cuerpo = leer(JsonElement.serializer(), llamar("GET", ruta, null), ruta)
        return (cuerpo as? JsonObject)?.get("workflow") ?: cuerpo
    }

    /** Borra un workflow. Si Graph dice 404, ya no existe: es lo que se pedía, cuenta como borrado. */
    suspend fun borrar(id: String) {
        val reply = llamar("DELETE", workflowRuta(id), null, aceptados = setOf(404))
        if (reply.status == 404) log.log(TAG, "borrar $id: ya no existía (HTTP 404), cuenta como borrado")
    }

    /** El plan ejecutable; Graph ya filtró los pasos que no lo son. Sin plan, falla. */
    suspend fun plan(id: String, variables: Map<String, String> = emptyMap()): ExecutionPlan {
        val ruta = "${workflowRuta(id)}/plan"
        val cuerpo = PlanRequest(variables = variables, executionIntent = mapOf("source" to FUENTE, "surface" to "native"))
        val reply = llamar("POST", ruta, LearningJson.encodeToString(PlanRequest.serializer(), cuerpo))
        return leer(PlanResponse.serializer(), reply, ruta).executionPlan
            ?: throw GraphException("graph no devolvió un plan de ejecución en $ruta (HTTP ${reply.status})", reply.status)
    }

    /**
     * Enseña al workflow a alcanzar su propia superficie: un paso de alineación en orden 0. Best-effort: si
     * falla, el workflow sigue igual y devuelve `false`, pero la causa queda en el log (Windows se la tragaba
     * en un `catch { }`). Cancelar sale tal cual.
     */
    suspend fun prependAlignment(id: String): Boolean = try {
        llamar("POST", "${workflowRuta(id)}/prepend-alignment", "{}")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.log(TAG, "alinear $id falló (best-effort, el workflow sigue igual): ${e.message}")
        false
    }

    /* ────────────── Enseñanza por video ────────────── */

    /** Las URLs firmadas para subir el video. Sin la de Gemini no hay a dónde subir: falla. */
    suspend fun uploadToken(contentLength: Long, userId: String): UploadTokenResponse {
        val ruta = "/api/v1/teach/upload-token"
        val reply = llamar("POST", ruta, LearningJson.encodeToString(UploadTokenRequest.serializer(), UploadTokenRequest(contentLength, userId)), teach = true)
        val token = leer(UploadTokenResponse.serializer(), reply, ruta)
        if (token.geminiUploadUrl == null) throw GraphException("graph no devolvió url de subida para gemini (HTTP ${reply.status})", reply.status)
        token.archiveError?.let { log.log(TAG, "el archivo del video no está disponible: $it") }
        return token
    }

    /** El estado del video en Gemini (`PROCESSING`, `ACTIVE`, `FAILED`…); `UNKNOWN` si Graph no lo dijo. El sondeo es de quien llama. */
    suspend fun fileState(fileUri: String): String {
        val ruta = "/api/v1/teach/file-state"
        val reply = llamar("POST", ruta, LearningJson.encodeToString(FileStateRequest.serializer(), FileStateRequest(fileUri)), teach = true)
        return leer(FileStateResponse.serializer(), reply, ruta).state ?: "UNKNOWN"
    }

    /** Lo que el video dejó. Van los pasos de ESTA demo, si hay; sin pasos, `steps` no viaja. */
    suspend fun processVideo(fileUri: String, userId: String, pasos: List<StepToRead> = emptyList()): ProcessResult {
        val ruta = "/api/v1/teach/process-video"
        val cuerpo = ProcessRequest(fileUri = fileUri, userId = userId, steps = pasos.ifEmpty { null })
        val leido = leer(ProcessResult.serializer(), llamar("POST", ruta, LearningJson.encodeToString(ProcessRequest.serializer(), cuerpo), teach = true), ruta)
        log.log(TAG, "video procesado: ${leido.notes?.size ?: 0} nota(s), interpretación ${if (leido.interpretation != null) "presente" else "AUSENTE"}")
        return leido
    }

    /**
     * Interpreta la demo SIN video: los pasos y lo que se narró. Es el respaldo del respaldo, así que NUNCA
     * revienta: si Graph no responde, falla o contesta sin interpretación, devuelve `null` —el modelo no
     * opinó— y lo dice en el log con la causa (promesa 406). Lo único que sale es la cancelación: cancelar
     * no es que el modelo no haya opinado.
     */
    suspend fun interpretSteps(startsAt: String, pasos: List<StepToRead>): JsonElement? {
        if (pasos.isEmpty()) return noOpino("no hay pasos que interpretar")
        return try {
            val ruta = "/api/v1/teach/interpret-steps"
            val cuerpo = LearningJson.encodeToString(InterpretRequest.serializer(), InterpretRequest(startsAt, pasos))
            val leido = leer(InterpretResult.serializer(), llamar("POST", ruta, cuerpo, teach = true), ruta)
            leido.interpretation?.also { log.log(TAG, "la demo se interpretó sin video (${it.toString().length} car.)") }
                ?: noOpino("graph contestó sin interpretación")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            noOpino(e.message ?: e::class.simpleName ?: "sin causa")
        }
    }

    private fun noOpino(causa: String): JsonElement? {
        log.log(TAG, "interpret-steps: el modelo no opinó — $causa")
        return null
    }

    /* ────────────── HTTP ────────────── */

    /**
     * Una llamada con sus reintentos y su tope, ya comprobada: solo vuelve un 2xx sin `error` (o un status de
     * [aceptados]); todo lo demás es [GraphException]. Sin key no llama a nadie.
     */
    private suspend fun llamar(
        metodo: String,
        ruta: String,
        cuerpo: String?,
        teach: Boolean = false,
        reintentos: Int = Reintentos.MAX_REINTENTOS,
        aceptados: Set<Int> = emptySet(),
    ): TransportReply {
        val key = credentials().trim()
        check(key.isNotEmpty()) { GraphCredentials.FALTA }
        val feature = if (teach) GraphHeaders.FEATURE_CEREBRO else null
        val headers = GraphHeaders.build(key, email(), deviceId(), feature).let { if (cuerpo == null) it - "Content-Type" else it }
        val url = baseUrl().trimEnd('/') + ruta
        val tope = if (teach) topeTeach else topeGeneral
        val envio = enviarConReintentos(tope, timeSource, sleep, log, TAG, "de la llamada", reintentos) { queda ->
            transport.send(metodo, url, cuerpo, headers, queda)
        }
        return comprobar(envio, "$metodo $ruta", tope, aceptados)
    }

    private fun comprobar(envio: Envio, donde: String, tope: Duration, aceptados: Set<Int>): TransportReply {
        val reply = envio.reply
        if (reply.status in aceptados) return reply
        if (reply.status == 401 || reply.status == 403)
            throw GraphException("la key de graph no vale (HTTP ${reply.status})", reply.status)
        if (reply.status == TransportReply.TIMED_OUT)
            throw GraphException("graph no respondió a tiempo en $donde (${reply.body.ifBlank { "sin causa" }}); no se reintentó para no cobrar dos veces", reply.status)
        val error = errorDe(reply.body)
        if (Reintentos.esTransitorio(reply.status)) {
            val cuando = if (envio.topado) "dentro del tope de ${Reintentos.corto(tope)} de la llamada, tras ${envio.intentos} intentos"
                else "tras ${envio.intentos} intentos"
            val causa = error ?: reply.body.trim().take(200).ifBlank { null }
            throw GraphException("graph no respondió (HTTP ${reply.status}) en $donde $cuando" + (causa?.let { ": $it" } ?: ""), reply.status)
        }
        if (reply.status !in 200..299)
            throw GraphException(error?.let { "$it (HTTP ${reply.status} en $donde)" } ?: "graph HTTP ${reply.status} en $donde: ${reply.body.take(200)}", reply.status)
        if (error != null) throw GraphException("$error (HTTP ${reply.status} en $donde)", reply.status)
        return reply
    }

    /** El `error` de un cuerpo `{"error": "…"}`; `""` o un cuerpo que no es JSON, ninguno. */
    private fun errorDe(body: String): String? =
        runCatching { (LearningJson.parseToJsonElement(body) as? JsonObject)?.get("error") }.getOrNull().textoNoVacio()

    private fun <T> leer(lector: KSerializer<T>, reply: TransportReply, ruta: String): T =
        runCatching { LearningJson.decodeFromString(lector, reply.body) }.getOrElse { e ->
            if (reply.body.isBlank()) throw GraphException("respuesta vacía de graph en $ruta (HTTP ${reply.status})", reply.status)
            throw GraphException("graph respondió algo que no se pudo leer en ${rutaJson(e)} de $ruta (HTTP ${reply.status}): ${reply.body.take(200)}", reply.status)
        }

    /** Dónde se rompió la lectura, según kotlinx («… at path: $.steps[0]»); sin ruta, el cuerpo entero (`$`). */
    private fun rutaJson(e: Throwable): String =
        e.message?.let { Regex("at path: (\\S+)").find(it)?.groupValues?.get(1) } ?: "\$"

    private fun sesionRuta(sessionId: String) = "/api/v1/learning/sessions/${segmento(sessionId)}"

    private fun workflowRuta(id: String) = "/api/v1/workflows/${segmento(id)}"

    /** Un id como segmento de ruta: todo lo que no es «unreserved» (RFC 3986) va en percent-encoding UTF-8. */
    private fun segmento(id: String): String = id.encodeToByteArray().joinToString("") { byte ->
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        if (b < 0x80 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~')) c.toString()
        else "%" + HEX[b shr 4] + HEX[b and 0xF]
    }

    companion object {
        /** `GraphClient.cs:64`: 90 s por llamada. */
        val TOPE_GENERAL: Duration = 90.seconds
        /** `BackendClient.cs:45`: 5 min en `/teach/…`. */
        val TOPE_TEACH: Duration = 5.minutes
        /** `execution_intent.source`; Windows manda `windows-u` (supuesto sin verificar, spec 004). */
        const val FUENTE = "android_app"
        const val SIN_DESCRIPCION = "Workflow sin descripción"
        private const val TAG = "aprendizaje"
        private const val CIERRE_INTENTOS = 3
        private val CIERRE_ESPERAS_MS = listOf(3_000L, 8_000L)
        private const val HEX = "0123456789ABCDEF"
    }
}
