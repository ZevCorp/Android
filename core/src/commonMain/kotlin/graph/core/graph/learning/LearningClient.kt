package graph.core.graph.learning

import graph.core.domain.GraphLog
import graph.core.graph.TurnTransport
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val NO_LOG = GraphLog { _, _ -> }

/** Una llamada de aprendizaje que Graph no completó, con el mensaje que Graph dio y el status. */
open class GraphException(message: String, val status: Int) : Exception(message) {
    /** ¿Puede salir bien al reintentar? Los mismos status que el turno; una lectura agotada (-1) no. */
    val transitorio: Boolean get() = TODO()
}

/** El cierre no llegó, pero LOS PASOS YA ESTÁN EN GRAPH: falta el resumen, y se completa sin volver a grabar. */
class FinishPendiente(val sessionId: String, val workflowId: String, message: String, status: Int) : GraphException(message, status)

/** El cliente de aprendizaje y workflows de Graph (spec 004). */
class LearningClient(
    private val transport: TurnTransport,
    private val credentials: () -> String,
    private val baseUrl: () -> String,
    private val email: () -> String?,
    private val deviceId: () -> String?,
    private val log: GraphLog = NO_LOG,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val topeGeneral: Duration = TOPE_GENERAL,
    private val topeTeach: Duration = TOPE_TEACH,
) {
    suspend fun crearSesion(sesion: StartSessionRequest): SessionInfo = TODO()
    suspend fun mandarPaso(sessionId: String, paso: StepRequest): Int = TODO()
    suspend fun notaDeContexto(sessionId: String, transcript: String): Unit = TODO()
    suspend fun terminar(sessionId: String, workflowId: String = sessionId): FinishResponse = TODO()
    suspend fun listarWorkflows(): List<WorkflowResumen> = TODO()
    suspend fun workflow(id: String): JsonElement = TODO()
    suspend fun borrar(id: String): Unit = TODO()
    suspend fun plan(id: String, variables: Map<String, String> = emptyMap()): ExecutionPlan = TODO()
    suspend fun prependAlignment(id: String): Boolean = TODO()
    suspend fun uploadToken(contentLength: Long, userId: String): UploadTokenResponse = TODO()
    suspend fun fileState(fileUri: String): String = TODO()
    suspend fun processVideo(fileUri: String, userId: String, pasos: List<StepToRead> = emptyList()): ProcessResult = TODO()
    suspend fun interpretSteps(startsAt: String, pasos: List<StepToRead>): JsonElement? = TODO()

    companion object {
        /** `GraphClient.cs:64`: 90 s por llamada. */
        val TOPE_GENERAL: Duration = 90.seconds
        /** `BackendClient.cs:45`: 5 min en `/teach/…`. */
        val TOPE_TEACH: Duration = 5.minutes
    }
}
