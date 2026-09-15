package graph.core.graph.learning

import graph.core.domain.GraphLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val SIN_LOG = GraphLog { _, _ -> }

/** Cómo queda en disco lo que deja una enseñanza: legible por una persona y tolerante con campos nuevos. */
val LeccionJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    prettyPrint = true
}

/** Dónde está el usuario al empezar, como la lee la superficie: en Android, `android://paquete/Activity`. */
@Serializable
class IdentidadDePantalla(val url: String, val origin: String, val pathname: String, val title: String)

/** Lo que dejó procesar el video (4C lo arma con upload-token, el PUT, file-state y process-video). */
class ResumenDeVideo(val resumen: String?, val interpretacion: JsonElement?)

/** Cómo salió [Leccion.empezar]. */
sealed interface Arranque {
    /** La sesión está abierta en Graph y los pasos ya se pueden observar. */
    class Ensenando(val sessionId: String, val workflowId: String) : Arranque

    /** No se enseña: [motivo] lo dice en una línea, y no quedó nada abierto. */
    class NoSePuede(val motivo: String) : Arranque
}

/** Un paso de la demostración, con lo que le pasó al viajar. */
@Serializable
class PasoDeLeccion(
    /** 1, 2, 3…: el orden en que ocurrió, que es el orden en que viajó. */
    val orden: Int,
    /** Cuándo se observó, en el reloj de la lección (ms Unix). */
    val horaMs: Long,
    val paso: StepRequest,
    /** ¿Lo confirmó Graph? */
    val enviado: Boolean,
    /** El `step_order` que le dio Graph (0 si no lo dijo); `null` si no llegó. */
    val stepOrder: Int? = null,
    /** Por qué no llegó; `null` si llegó. */
    val motivo: String? = null,
)

/** La lección entera, como se escribe en `lecciones/<sesión>.json` antes de tocar la red del cierre. */
@Serializable
class LeccionEnDisco(
    val version: Int = 1,
    val sessionId: String,
    val workflowId: String,
    val descripcion: String,
    val identidad: IdentidadDePantalla,
    val dondeEmpezo: String,
    val dondeTermino: String,
    val empezoMs: Long,
    val terminoMs: Long,
    val pasos: List<PasoDeLeccion>,
    val nota: String,
    /** Qué le falta, en una línea; `null` si está entera. */
    val motivo: String? = null,
)

/** Un cierre que no salió por un fallo transitorio: `cierres-pendientes/<sesión>.json`. */
@Serializable
class CierrePendiente(val sessionId: String, val workflowId: String, val cuandoMs: Long)

/** Un video que no se procesó: `videos-por-reprocesar/<sesión>.json`. [leccion] es `null` si la lección no llegó al disco. */
@Serializable
class VideoParaReprocesar(val sessionId: String, val leccion: String? = null, val motivo: String, val cuandoMs: Long)

/** Cómo quedó la sesión en Graph. */
enum class Cierre {
    /** Graph post-procesó y persistió el workflow. */
    CERRADA,
    /** Los pasos están en Graph y el cierre quedó en disco para reintentarse al arrancar. */
    PENDIENTE,
    /** Graph no respondió a tiempo y pudo haberlo cerrado: no se reintenta solo. */
    INCIERTO,
    /** Graph dijo que no: no se reintenta. */
    FALLIDO,
}

/** Si lo aprendido se comprobó reproduciéndolo (4F). Hoy nada sale comprobado. */
enum class Comprobacion(val texto: String) { SIN_COMPROBAR("SIN comprobar") }

/** Lo que devuelve [Leccion.terminar]. */
class ResultadoDeLeccion(
    val sessionId: String,
    val workflowId: String,
    val pasos: List<PasoDeLeccion>,
    val cierre: Cierre,
    /** La ruta de la lección en el almacén, o `null` si no se pudo escribir. */
    val leccion: String?,
    /** El video no se procesó y hay que reprocesarlo. */
    val videoParaReprocesar: Boolean,
    val resumenDeVideo: ResumenDeVideo?,
    /** Lo que salió mal sin tumbar la enseñanza, una línea cada uno. */
    val avisos: List<String>,
    /** Para el usuario, en una línea. */
    val mensaje: String,
    val comprobacion: Comprobacion = Comprobacion.SIN_COMPROBAR,
) {
    val pasosMandados: Int get() = pasos.count { it.enviado }
    val pasosFallidos: Int get() = pasos.count { !it.enviado }
}

/** Lo que dejó [Leccion.reintentarPendientes]. [descartados]: Graph ya no los conoce o pudo haberlos cerrado. */
class PendientesReintentados(val cerrados: Int, val siguen: Int, val descartados: Int)

/** UNA ENSEÑANZA contra Graph y el disco (spec 004, fase 4A2). Esqueleto: la implementación llega con 407-412 en rojo. */
class Leccion(
    private val cliente: LearningClient,
    private val almacen: Almacen,
    private val scope: CoroutineScope,
    private val appId: String,
    private val ahoraMs: () -> Long,
    private val superficie: String = SUPERFICIE,
    private val log: GraphLog = SIN_LOG,
    private val avisar: (String) -> Unit = {},
    private val topeDeVaciado: Duration = TOPE_DE_VACIADO,
) {
    suspend fun empezar(identidad: IdentidadDePantalla, descripcion: String): Arranque = TODO("fase 4A2")

    fun pasoObservado(paso: StepRequest): Boolean = TODO("fase 4A2")

    fun nota(transcripcion: String): Unit = TODO("fase 4A2")

    suspend fun terminar(dondeTermina: String, video: suspend () -> ResumenDeVideo?): ResultadoDeLeccion = TODO("fase 4A2")

    suspend fun descartar(): Unit = TODO("fase 4A2")

    suspend fun reintentarPendientes(): PendientesReintentados = TODO("fase 4A2")

    companion object {
        const val CARPETA_LECCIONES = "lecciones"
        const val CARPETA_PENDIENTES = "cierres-pendientes"
        const val CARPETA_VIDEOS = "videos-por-reprocesar"
        const val SUPERFICIE = "a11y"
        const val PLATAFORMA = "android"
        const val AVISO_PASOS = 30
        val TOPE_DE_VACIADO: Duration = 30.seconds
    }
}
