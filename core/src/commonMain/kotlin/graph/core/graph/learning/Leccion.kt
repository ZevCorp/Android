package graph.core.graph.learning

import graph.core.domain.GraphLog
import graph.core.graph.Reintentos
import graph.core.graph.TransportReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

/**
 * Un cierre que no salió, o del que todavía no se sabe si salió —el provisional que va a disco antes de la red (420)—:
 * `cierres-pendientes/<sesión>.json`. [intentos]: cuántas veces lo intentó un arranque; manda el turno del siguiente (411).
 */
@Serializable
class CierrePendiente(val sessionId: String, val workflowId: String, val cuandoMs: Long, val intentos: Int = 0)

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

/**
 * Lo que dejó [Leccion.reintentarPendientes]. [siguen]: los que no salieron y los que el tope dejó para el arranque siguiente.
 * [descartados]: Graph ya no los conoce o pudo haberlos cerrado.
 */
class PendientesReintentados(val cerrados: Int, val siguen: Int, val descartados: Int)

/**
 * UNA ENSEÑANZA contra Graph y el disco (spec 004, fase 4A2). Espejo del comportamiento de
 * `WorkflowRecorder.cs` + `WorkflowTeachSession.cs` de Windows, sin Android, sin MediaProjection y sin red
 * propia: habla con Graph por [LearningClient], con el disco por [Almacen], y el video le llega como una
 * función. Una instancia, una demostración: NUEVA → GRABANDO → TERMINADA o DESCARTADA.
 *
 * Reglas:
 *  - sin sesión abierta en Graph no se enseña: [empezar] devuelve el motivo y no deja nada abierto (408); si lo
 *    cancelan, vuelve a NUEVA;
 *  - [pasoObservado] y [nota] solo ENCOLAN (los llama el hilo de la superficie, que no se puede bloquear) y
 *    UN SOLO LECTOR manda en serie: Graph numera los pasos por orden de llegada. Un paso que falla queda
 *    contado con su motivo y sigue el siguiente (407);
 *  - el lector vive en [scope], que no es de la lección: si se cancela o falla, lo que no viajó cuenta como no
 *    enviado con su motivo, y la lección no se da por entera ni se anuncia como aprendida (417);
 *  - [terminar] va en este orden, y el orden es el contrato: vaciar la cola (con tope) → la lección a disco
 *    → el video → la nota → el cierre (409-412). Cancelarlo no lo deja a medias: se cierra, o queda pendiente (418);
 *  - [descartar] no publica nada: ni pasos pendientes, ni nota, ni cierre, ni lección (412);
 *  - un cierre que no sale por un transitorio o por la key queda en disco y [reintentarPendientes] lo intenta al
 *    arrancar, una vez por arranque, hasta [MAX_PENDIENTES_POR_ARRANQUE] en [TOPE_DE_ARRANQUE]; una lectura agotada no deja
 *    pendiente automático (411);
 *  - el cierre provisional va a disco con la lección, antes de la red: ni un proceso que muere ni un cierre cancelado que agota
 *    su [TOPE_DE_CIERRE_CANCELADO] dejan la sesión sin pendiente (420);
 *  - el log sale del teléfono (en la app, `LogBus` manda cada línea a telemetría): lleva ids, cuentas, estados y códigos. Lo que
 *    el usuario dijo, escribió o nombró, y el texto de un fallo, van al resultado y a disco, nunca al log (419).
 */
class Leccion(
    private val cliente: LearningClient,
    private val almacen: Almacen,
    /** Donde vive el lector de pasos: el de la enseñanza en la app; el de la prueba en el contrato. */
    private val scope: CoroutineScope,
    /** El `app_id` de la sesión. */
    private val appId: String,
    /** El reloj de pared (ms Unix): cuándo se observó cada paso, cuándo empezó y terminó, cuándo quedó un pendiente. */
    private val ahoraMs: () -> Long,
    /** `context.surface` de la sesión. */
    private val superficie: String = SUPERFICIE,
    private val log: GraphLog = SIN_LOG,
    /** Lo que hay que decirle al usuario mientras enseña: el aviso de grabación larga. */
    private val avisar: (String) -> Unit = {},
    /** Cuánto se espera a que salgan los pasos encolados al terminar (`WorkflowRecorder.cs`: 30 s). */
    private val topeDeVaciado: Duration = TOPE_DE_VACIADO,
    /** El reloj del tope de [reintentarPendientes]; inyectable para que el contrato mida sin esperar. */
    private val reloj: TimeSource = TimeSource.Monotonic,
    /** Cómo espera un cierre cancelado su [TOPE_DE_CIERRE_CANCELADO]; inyectable para que el contrato decida cuándo vence. */
    private val esperarTope: suspend (Duration) -> Unit = { delay(it) },
) {
    private enum class Estado { NUEVA, ABRIENDO, GRABANDO, CERRANDO, TERMINADA, DESCARTADA }

    /** Lo que llega mientras se graba, en el orden en que llega: un paso, o un trozo de lo dicho. */
    private sealed interface Observado {
        class Paso(val paso: StepRequest, val horaMs: Long) : Observado
        class Nota(val texto: String) : Observado
    }

    /** Lo que el lector lleva hecho. Solo lo toca el lector; los demás lo leen cuando el lector ya terminó. */
    private class Leido {
        val pasos = mutableListOf<PasoDeLeccion>()
        val nota = StringBuilder()
        var enCurso: Observado.Paso? = null
        /**
         * Lo que la cola le entregó al lector cuando ya lo estaban cancelando y no llegó a tomar (`onUndeliveredElement`). Una lista
         * sin candado, porque sus escrituras van siempre en serie: la del lector, al cancelarse, antes de que termine; las de
         * `cola.cancel()`, solo en [descartar] y con el lector ya terminado; y [cortar] la lee con el lector terminado. `trySend`
         * sobre la cola cerrada o cancelada no llama a `onUndeliveredElement` (medido en kotlinx-coroutines 1.8.1).
         */
        val sinEntregar = mutableListOf<Observado>()
        /** Por qué terminó el lector: `null` si vació la cola; la cancelación o el fallo de su scope, si no. */
        @Volatile var fin: Throwable? = null
        var mandados = 0
        var avisado = false

        fun anotar(texto: String) {
            if (nota.isNotEmpty()) nota.append('\n')
            nota.append(texto)
        }
    }

    /** Lo que salió mal sin tumbar la enseñanza, dos veces: para el usuario, con su causa, y para el log, medido (419). */
    private class Avisos {
        val paraUsuario = mutableListOf<String>()
        val paraLog = mutableListOf<String>()

        fun anotar(usuario: String, log: String = usuario) {
            paraUsuario += usuario
            paraLog += log
        }
    }

    /** Lo que dejó la red del cierre: cómo quedó, con qué workflow y, si no salió, por qué, entero y medido. */
    private class EnGraph(val cierre: Cierre, val workflowId: String, val motivo: String, val medida: String, val faltaElResumen: Boolean)

    private class Abierta(
        val sesion: Arranque.Ensenando,
        val identidad: IdentidadDePantalla,
        val descripcion: String,
        val empezoMs: Long,
        val cola: Channel<Observado>,
        val leido: Leido,
        val lector: Job,
    )

    private val candado = Mutex()
    @Volatile private var estado = Estado.NUEVA
    @Volatile private var abierta: Abierta? = null

    /**
     * Abre la sesión en Graph. Si no sale —la key no vale, Graph no respondió, no devolvió id—, no se enseña:
     * devuelve el motivo en una línea, no queda lector ni cola, y se puede volver a intentar. Lo mismo si el [scope]
     * del lector ya está cancelado, que ni llama a Graph, o si se cancela mientras Graph abre (417). Si cancelan
     * `empezar`, la lección vuelve a NUEVA antes de que salga la cancelación.
     */
    suspend fun empezar(identidad: IdentidadDePantalla, descripcion: String): Arranque {
        candado.withLock {
            check(estado == Estado.NUEVA) { "esta lección ya empezó (${estado.name.lowercase()}): una lección, una demostración" }
            if (!scope.isActive) {
                val motivo = "no se puede enseñar: el scope del lector de pasos ya está cancelado; no se abrió la sesión en graph"
                log.log(TAG, motivo)
                return Arranque.NoSePuede(motivo)
            }
            estado = Estado.ABRIENDO
        }
        val info = try {
            cliente.crearSesion(
                StartSessionRequest(
                    description = descripcion,
                    appId = appId,
                    sourceUrl = identidad.url,
                    sourceOrigin = identidad.origin,
                    sourcePathname = identidad.pathname,
                    sourceTitle = identidad.title,
                    context = mapOf("surface" to superficie, "platform" to PLATAFORMA),
                ),
            )
        } catch (e: CancellationException) {
            volverANueva()
            throw e
        } catch (e: Exception) {
            volverANueva()
            log.log(TAG, "no se puede enseñar: graph no abrió la sesión — ${medidaDe(e)}")
            return Arranque.NoSePuede("no se puede enseñar: graph no abrió la sesión — ${unaLinea(e)}")
        }
        val id = checkNotNull(info.id) { "crearSesion devolvió una sesión sin id" }
        val sesion = Arranque.Ensenando(sessionId = id, workflowId = info.workflowId ?: id)
        return try {
            candado.withLock {
                if (estado != Estado.ABRIENDO) {
                    // Se descartó mientras Graph abría: allá la sesión existe, aquí no se hace nada más (ver descartar).
                    log.log(TAG, "la sesión ${sesion.sessionId} se abrió con la demostración ya descartada: no se enseña")
                    return@withLock Arranque.NoSePuede("no se enseña: la demostración se descartó mientras graph abría la sesión")
                }
                val empezoMs = ahoraMs()
                // Quien llamó ya no está para enseñar: no se abre aquí lo que nadie va a cerrar.
                currentCoroutineContext().ensureActive()
                if (!scope.isActive) {
                    estado = Estado.NUEVA
                    val motivo = "no se puede enseñar: el scope del lector de pasos quedó cancelado mientras graph abría la sesión"
                    log.log(TAG, "$motivo (la sesión ${sesion.sessionId} queda sin cerrar en graph)")
                    return@withLock Arranque.NoSePuede(motivo)
                }
                val leido = Leido()
                val cola = Channel<Observado>(Channel.UNLIMITED, onUndeliveredElement = { leido.sinEntregar += it })
                val lector = scope.launch { leer(sesion.sessionId, cola, leido) }
                lector.invokeOnCompletion { leido.fin = it }
                abierta = Abierta(sesion, identidad, descripcion, empezoMs, cola, leido, lector)
                estado = Estado.GRABANDO
                log.log(TAG, "▶ enseñando (sesión ${sesion.sessionId}, workflow ${sesion.workflowId})")
                sesion
            }
        } catch (e: CancellationException) {
            // Cancelado esperando el candado o justo antes de abrir: no queda en ABRIENDO para siempre.
            volverANueva()
            log.log(TAG, "se canceló empezar con la sesión ${sesion.sessionId} ya abierta en graph: no se enseña y allá queda sin cerrar")
            throw e
        }
    }

    /**
     * Encola un paso observado y vuelve enseguida. `false` si no hay enseñanza en curso, o si el lector ya no vive (se
     * canceló o falló su scope): el paso no va a ningún lado.
     */
    fun pasoObservado(paso: StepRequest): Boolean {
        val a = abierta ?: return false
        if (estado != Estado.GRABANDO || !a.lector.isActive) return false
        return a.cola.trySend(Observado.Paso(paso, ahoraMs())).isSuccess
    }

    /** Guarda un trozo de lo que el usuario explica de viva voz. Viaja al terminar, antes del cierre. */
    fun nota(transcripcion: String) {
        val texto = transcripcion.trim()
        if (texto.isEmpty()) return
        val a = abierta ?: return
        if (estado != Estado.GRABANDO) return
        a.cola.trySend(Observado.Nota(texto))
    }

    /**
     * Cierra la enseñanza, en este orden:
     *  a) vacía la cola: los últimos pasos son tan parte del workflow como los primeros. Con tope: pasado
     *     [topeDeVaciado] se corta el lector, y lo que no salió cuenta como no enviado, con su motivo. Si el lector ya
     *     se había muerto (se canceló o falló su scope), lo que quedó en vuelo o en la cola tampoco salió: cuenta igual,
     *     y la lección no se da por entera ni se anuncia como aprendida (417);
     *  b) escribe la lección en disco ANTES de tocar la red, en una sola escritura: el video, la nota y el
     *     cierre pueden fallar (429, 504, sin red) y la lección no depende de ninguno. Con ella va el cierre PROVISIONAL a
     *     `cierres-pendientes/`: si el proceso muere antes de saber cómo salió `finish` —procesando el video, con `finish` en
     *     vuelo—, al arrancar queda un pendiente que lo termina (420);
     *  c) procesa el video con [video]; si lanza o devuelve `null`, se sigue y queda marcado para reprocesar;
     *  d) la nota de contexto (lo hablado y el resumen del video), solo si hay: después del cierre ya no hay
     *     sesión a la que adjuntarla;
     *  e) cierra la sesión y el provisional se borra. Si no sale por un transitorio, o por la key, se queda como pendiente;
     *     si Graph no respondió a tiempo, pudo haberla cerrado y se borra, igual que si Graph dijo que no.
     *
     * Si el proceso muere después de que `finish` salió y antes de borrar el provisional, el arranque reintenta una sesión que
     * Graph ya cerró. Si Graph responde 404 o 400, [trasFallo] lo juzga FALLIDO y el pendiente se borra tras ese único intento;
     * si respondiera 2xx y post-procesara otra vez, cobraría dos veces (supuesto sin verificar de la spec 004).
     *
     * CANCELAR NO LA DEJA A MEDIAS (418). Lo que espera —vaciar la cola, procesar el video— se puede cancelar: lo que
     * no salió cuenta como no enviado y el video queda para reprocesar, sin empezarlo si aún no empezó. Lo que escribe o
     * publica —la lección, la marca del video, la nota, el cierre y su pendiente— corre hasta el final bajo
     * [NonCancellable], y la cancelación sale después, con la lección ya TERMINADA. Y NO LA CUELGA (420): desde que llega la
     * cancelación, la red del cierre —la nota y `finish`— tiene [TOPE_DE_CIERRE_CANCELADO]; vencido, se corta, queda el
     * pendiente y la cancelación sale. Windows cierra entero con `CancellationToken.None`, video incluido.
     */
    suspend fun terminar(dondeTermina: String, video: suspend () -> ResumenDeVideo?): ResultadoDeLeccion {
        val a = candado.withLock {
            check(estado == Estado.GRABANDO) { "no hay ninguna enseñanza en curso (${estado.name.lowercase()})" }
            estado = Estado.CERRANDO
            checkNotNull(abierta)
        }
        try {
            return cerrar(a, dondeTermina, video)
        } finally {
            // Pase lo que pase —una cancelación, un Error—, la lección no se queda trabada en CERRANDO.
            estado = Estado.TERMINADA
            abierta = null
        }
    }

    private suspend fun cerrar(a: Abierta, dondeTermina: String, video: suspend () -> ResumenDeVideo?): ResultadoDeLeccion {
        val sid = a.sesion.sessionId
        val avisos = Avisos()
        var cancelada: CancellationException? = null
        // La corrida de quien llamó: si la cancelan, la red del cierre tiene tope (420).
        val quienLlama = currentCoroutineContext()[Job]

        /** Un fallo de lo que no se cancela, en una línea. Si es una cancelación, se anota para relanzarla al final. */
        fun fallo(e: Exception): String {
            if (e is CancellationException && cancelada == null) cancelada = e
            return unaLinea(e)
        }

        // a) Vaciar la cola.
        a.cola.close()
        var topado = false
        try {
            topado = withTimeoutOrNull(topeDeVaciado) { a.lector.join() } == null
        } catch (e: CancellationException) {
            cancelada = e
        }
        // El lector se murió solo si nadie lo cortó: ni el tope ni la cancelación de este cierre.
        val lectorSeMurio = !topado && cancelada == null && a.lector.isCancelled
        /** Por qué se detuvo el lector, con su causa entera (para la lección) o medida (para el log, 419); `null` si no se murió. */
        fun lectorMuerto(causa: (Throwable) -> String): String? =
            if (lectorSeMurio) "el lector de pasos se detuvo antes de cerrar (${a.leido.fin?.let(causa) ?: "sin causa"})" else null

        var pasos = emptyList<PasoDeLeccion>()
        var nota = ""
        var leccion: String? = null
        var provisional = false
        sinCancelar {
            if (topado || cancelada != null) a.lector.cancelAndJoin()
            fun porQue(causa: (Throwable) -> String) = when {
                topado -> "antes de cerrar (tope de ${Reintentos.corto(topeDeVaciado)})"
                cancelada != null -> "porque se canceló el cierre"
                else -> "porque ${lectorMuerto(causa) ?: "el lector de pasos se detuvo"}"
            }
            val cortados = cortar(a, "no llegó a enviarse ${porQue(PARA_EL_USUARIO)}")
            if (cortados > 0) avisos.anotar(
                "$cortados paso(s) no llegaron a enviarse ${porQue(PARA_EL_USUARIO)}",
                "$cortados paso(s) no llegaron a enviarse ${porQue(PARA_EL_LOG)}",
            )
            pasos = a.leido.pasos.toList()
            nota = a.leido.nota.toString()

            // b) La lección, a disco, antes de la red.
            val faltante = faltante(pasos, dondeTermina, lectorMuerto(PARA_EL_USUARIO))
            val rutaLeccion = ruta(CARPETA_LECCIONES, sid)
            leccion = try {
                val entera = LeccionEnDisco(
                    sessionId = sid,
                    workflowId = a.sesion.workflowId,
                    descripcion = a.descripcion,
                    identidad = a.identidad,
                    dondeEmpezo = a.identidad.url,
                    dondeTermino = dondeTermina.trim(),
                    empezoMs = a.empezoMs,
                    terminoMs = ahoraMs(),
                    pasos = pasos,
                    nota = nota,
                    motivo = faltante,
                )
                almacen.escribirEntero(rutaLeccion, LeccionJson.encodeToString(LeccionEnDisco.serializer(), entera))
                rutaLeccion
            } catch (e: Exception) {
                avisos.anotar(
                    "la lección no se pudo guardar en disco (${fallo(e)}): graph sigue teniendo los pasos",
                    "la lección no se pudo guardar en disco (${medidaDe(e)}): graph sigue teniendo los pasos",
                )
                null
            }
            faltante?.let { avisos.anotar("a la lección le falta algo: $it", "a la lección le falta algo: ${faltante(pasos, dondeTermina, lectorMuerto(PARA_EL_LOG))}") }

            // Y con ella, el cierre provisional (420): desde aquí, pase lo que pase con el proceso, el arranque sabe cerrar la sesión.
            provisional = escribirPendiente(CierrePendiente(sid, a.sesion.workflowId, ahoraMs()))
                ?.also { log.log(TAG, "el cierre provisional de la sesión $sid no se pudo escribir (${medidaDe(it)}): si finish no sale, se intenta otra vez") } == null
        }?.let { if (cancelada == null) cancelada = it }

        // c) El video: tarda minutos y se puede cancelar. Si ya se canceló el cierre, ni se empieza.
        var resumen: ResumenDeVideo? = null
        var motivoVideo: String? = null
        var medidaVideo: String? = null
        if (cancelada != null) {
            motivoVideo = "se canceló el cierre antes de procesarlo"
        } else {
            try {
                resumen = video()
                if (resumen == null) motivoVideo = "procesar el video no dejó resultado"
            } catch (e: CancellationException) {
                cancelada = e
                motivoVideo = "se canceló el cierre mientras se procesaba"
            } catch (e: Exception) {
                motivoVideo = unaLinea(e)
                medidaVideo = medidaDe(e)
            }
        }

        var resultado: ResultadoDeLeccion? = null
        sinCancelar {
            motivoVideo?.let { m ->
                var marca = "queda para reprocesar"
                var marcaMedida = marca
                try {
                    almacen.escribirEntero(
                        ruta(CARPETA_VIDEOS, sid),
                        LeccionJson.encodeToString(VideoParaReprocesar.serializer(), VideoParaReprocesar(sid, leccion, m, ahoraMs())),
                    )
                } catch (e: Exception) {
                    marca = "y no se pudo marcar para reprocesar (${fallo(e)})"
                    marcaMedida = "y no se pudo marcar para reprocesar (${medidaDe(e)})"
                }
                avisos.anotar(
                    "el video no se procesó ($m): la sesión se cierra con los pasos y el video $marca",
                    "el video no se procesó (${medidaVideo ?: m}): la sesión se cierra con los pasos y el video $marcaMedida",
                )
            }

            // d) y e) La red del cierre. Si ya cancelaron, o cancelan mientras tanto, con tope; vencido, queda pendiente (420).
            val tope = "se agotó el tope de ${Reintentos.corto(TOPE_DE_CIERRE_CANCELADO)} del cierre cancelado"
            val enGraph = conTopeSiCancelan(quienLlama) { cerrarEnGraph(a, nota, resumen, avisos) }
                ?: EnGraph(Cierre.PENDIENTE, a.sesion.workflowId, tope, tope, faltaElResumen = false)
            val cierre = enGraph.cierre
            val workflowId = enGraph.workflowId
            // El provisional se queda si el cierre quedó pendiente; si salió, o no hay nada que reintentar, se borra.
            if (cierre == Cierre.PENDIENTE) {
                if (provisional) log.log(TAG, "cierre pendiente guardado (sesión $sid): se reintenta al arrancar")
                else guardarPendiente(sid, a.sesion.workflowId, avisos)
            } else if (provisional) {
                borrarPendiente(ruta(CARPETA_PENDIENTES, sid))
            }

            // f) El resultado. Con pasos que no llegaron, o con el lector muerto, lo que Graph tiene está incompleto y
            //    no se anuncia como aprendido (417). Se compone dos veces: para el usuario, con el nombre y las causas, y
            //    para el log, que sale del teléfono, con el id del workflow y las medidas (419).
            val mandados = pasos.count { it.enviado }
            val cuantos = if (mandados == 1) "1 paso" else "$mandados pasos"
            val sinComprobar = Comprobacion.SIN_COMPROBAR.texto
            fun componer(quien: String, motivoCierre: String, causa: (Throwable) -> String): String {
                val incompleta = listOfNotNull(
                    pasos.count { !it.enviado }.takeIf { it > 0 }?.let { "$it de ${pasos.size} paso(s) no llegaron" },
                    lectorMuerto(causa),
                ).joinToString("; ").ifEmpty { null }
                val yFalta = incompleta?.let { "; $it" } ?: ""
                return when (cierre) {
                    Cierre.CERRADA ->
                        if (incompleta == null) "aprendí $quien ($cuantos), $sinComprobar"
                        else "$quien quedó incompleto en Graph ($cuantos$yFalta), $sinComprobar"
                    Cierre.PENDIENTE ->
                        (if (incompleta == null) "aprendido pero pendiente" else "incompleto ($incompleta) y pendiente") +
                            " de cerrar en Graph: $cuantos ya guardados, " +
                            (if (enGraph.faltaElResumen) "falta el resumen" else "no se pudo cerrar ($motivoCierre)") +
                            " y se reintenta al arrancar; $sinComprobar"
                    Cierre.INCIERTO -> "Graph no respondió a tiempo al cerrar y pudo haberlo cerrado: no se reintenta solo para no cobrar dos veces ($cuantos$yFalta); $sinComprobar"
                    Cierre.FALLIDO -> "Graph no cerró la sesión ($motivoCierre): $cuantos mandados$yFalta; $sinComprobar"
                }
            }
            val mensaje = componer("«${a.descripcion.trim().ifEmpty { workflowId }}»", enGraph.motivo, PARA_EL_USUARIO)
            val medido = componer("el workflow $workflowId", enGraph.medida, PARA_EL_LOG)
            log.log(TAG, "■ $medido · sesión $sid" + if (avisos.paraLog.isEmpty()) "" else " · ${avisos.paraLog.joinToString(" · ")}")
            resultado = ResultadoDeLeccion(
                sessionId = sid,
                workflowId = workflowId,
                pasos = pasos,
                cierre = cierre,
                leccion = leccion,
                videoParaReprocesar = motivoVideo != null,
                resumenDeVideo = resumen,
                avisos = avisos.paraUsuario,
                mensaje = mensaje,
            )
        }?.let { if (cancelada == null) cancelada = it }

        cancelada?.let {
            log.log(TAG, "el cierre de la sesión $sid se canceló: se completó igual y la cancelación sigue su curso")
            throw it
        }
        return checkNotNull(resultado)
    }

    /**
     * La demostración salió mal y se tira: no publica NADA. Corta el lector (un paso en vuelo se cancela) y
     * suelta lo encolado y la nota; no escribe en disco y no llama a Graph. Si la sesión ya estaba abierta en
     * Graph, queda sin cerrar: el único cierre que Graph tiene es `finish`, que post-procesa y persiste, y
     * cerrar sería publicar. Windows hace lo mismo (`TeachSession.DiscardAsync` borra el mp4 sin llamar a
     * Graph). Solo tiene efecto mientras se abre o se graba: descartar después de terminar no des-publica.
     *
     * Devuelve `true` si la demostración queda descartada, por esta llamada o por una anterior, y `false` si no había
     * nada que descartar: no empezó, ya se está cerrando o ya se cerró. Durante [terminar] no corta nada: lo que se
     * cierra se publica, y un cierre a medias es peor que ninguno.
     */
    suspend fun descartar(): Boolean {
        val a = candado.withLock {
            when (estado) {
                Estado.GRABANDO, Estado.ABRIENDO -> Unit
                Estado.DESCARTADA -> return true
                Estado.CERRANDO -> {
                    log.log(TAG, "no se descarta: la lección ya se está cerrando, y lo que se cierra se publica")
                    return false
                }
                Estado.NUEVA, Estado.TERMINADA -> return false
            }
            estado = Estado.DESCARTADA
            abierta.also { abierta = null }
        }
        if (a != null) {
            // En serie: el lector suelta lo que la cola le entregó sin tomarlo, y termina, antes de que la cola suelte lo suyo.
            // Así `onUndeliveredElement` nunca escribe `sinEntregar` desde dos hilos a la vez.
            a.lector.cancelAndJoin()
            a.cola.cancel()
        }
        log.log(TAG, "demostración descartada: no se publica nada" + (a?.let { " (la sesión ${it.sesion.sessionId} queda sin cerrar en graph)" } ?: ""))
        return true
    }

    /**
     * Para el arranque de la app, que lo llama en segundo plano sin bloquear la UI (4C): reintenta los cierres que quedaron pendientes, UNA vez cada uno, como Windows
     * (`PendingFinish.cs:69`): los tres intentos del cierre, con sus esperas y un post-procesado de LLM cada uno, se
     * repetirían en cada arranque y para siempre. El que sale se borra; el que vuelve a no salir por un transitorio,
     * o no se pudo intentar (sin key, la key no vale), se queda. Los que Graph ya no reconoce (la sesión murió con
     * su instancia) o a los que no respondió a tiempo (pudo haberlos cerrado) se borran: reintentarlos sería para
     * siempre, o cobrar dos veces. El criterio es el mismo que al cerrar ([trasFallo]).
     *
     * Con tope (411): hasta [MAX_PENDIENTES_POR_ARRANQUE] intentos, y ninguno que empiece pasado [TOPE_DE_ARRANQUE]; con Graph
     * caído, N pendientes × 90 s se comerían el arranque. El resto queda para el siguiente, que empieza por los que menos veces se
     * intentaron: sin ese turno, cinco pendientes que nunca salen taparían al sexto para siempre. El intento que ya salió termina en
     * su propio tope de 90 s: cortarlo a mitad dejaría a Graph cerrando sin que nadie lo sepa.
     */
    suspend fun reintentarPendientes(): PendientesReintentados {
        val empezo = reloj.markNow()
        val rutas = try {
            almacen.listar(CARPETA_PENDIENTES).sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(TAG, "no pude leer los cierres pendientes: ${medidaDe(e)}")
            return PendientesReintentados(0, 0, 0)
        }
        var cerrados = 0
        var siguen = 0
        var descartados = 0
        val leidos = rutas.mapNotNull { ruta ->
            try {
                almacen.leer(ruta)?.let { ruta to LeccionJson.decodeFromString(CierrePendiente.serializer(), it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(TAG, "el cierre pendiente $ruta no se pudo leer (${medidaDe(e)}): se deja")
                siguen++
                null
            }
        }
        // El turno: primero los que menos veces intentó un arranque; a igual número, los más viejos, y después por ruta.
        val turno = leidos.sortedWith(compareBy({ it.second.intentos }, { it.second.cuandoMs }))
        var intentados = 0
        for ((ruta, pendiente) in turno) {
            if (intentados == MAX_PENDIENTES_POR_ARRANQUE || empezo.elapsedNow() >= TOPE_DE_ARRANQUE) {
                siguen++
                continue
            }
            intentados++
            try {
                cliente.terminar(pendiente.sessionId, pendiente.workflowId, intentos = 1)
                cerrados++
                log.log(TAG, "✓ cierre completado (sesión ${pendiente.sessionId})")
                borrarPendiente(ruta)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (trasFallo(e)) {
                    Cierre.PENDIENTE -> {
                        siguen++
                        log.log(TAG, when (e) {
                            is FinishPendiente -> "el cierre de ${pendiente.sessionId} sigue sin poder completarse (HTTP ${e.status})"
                            is GraphException -> "el cierre de ${pendiente.sessionId} espera una key que valga: ${medidaDe(e)}"
                            else -> "el cierre de ${pendiente.sessionId} no se pudo intentar (${medidaDe(e)}): se deja"
                        })
                        anotarIntento(ruta, pendiente)
                    }
                    else -> {
                        descartados++
                        val porque = if ((e as? GraphException)?.status == TransportReply.TIMED_OUT) "graph no respondió a tiempo y pudo haberlo cerrado" else "no es reintentable"
                        log.log(TAG, "cierre de ${pendiente.sessionId} descartado, $porque: ${medidaDe(e)}")
                        borrarPendiente(ruta)
                    }
                }
            }
        }
        (turno.size - intentados).takeIf { it > 0 }?.let {
            log.log(TAG, "$it cierre(s) pendiente(s) quedan para el próximo arranque (tope: $MAX_PENDIENTES_POR_ARRANQUE por arranque y ${Reintentos.corto(TOPE_DE_ARRANQUE)} en total)")
        }
        return PendientesReintentados(cerrados, siguen, descartados)
    }

    /* ────────────── Por dentro ────────────── */

    /** EL ÚNICO LECTOR: de a un paso, esperando cada respuesta (`WorkflowRecorder.cs`: `SingleReader`). */
    private suspend fun leer(sessionId: String, cola: Channel<Observado>, leido: Leido) {
        for (o in cola) when (o) {
            is Observado.Nota -> leido.anotar(o.texto)
            is Observado.Paso -> mandar(sessionId, o, leido)
        }
    }

    private suspend fun mandar(sessionId: String, o: Observado.Paso, leido: Leido) {
        leido.enCurso = o
        val orden = leido.pasos.size + 1
        val paso = try {
            PasoDeLeccion(orden, o.horaMs, o.paso, enviado = true, stepOrder = cliente.mandarPaso(sessionId, o.paso))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Un paso perdido no aborta la grabación: mejor un workflow con un hueco que perder la demostración entera.
            log.log(TAG, "el paso $orden no llegó a graph: ${medidaDe(e)}")
            PasoDeLeccion(orden, o.horaMs, o.paso, enviado = false, motivo = unaLinea(e))
        }
        leido.pasos += paso
        leido.enCurso = null
        if (!paso.enviado) return
        leido.mandados++
        if (leido.mandados >= AVISO_PASOS && !leido.avisado) {
            // El post-procesado de Graph escribe título, resumen y guía con un LLM sobre TODOS los pasos: en un
            // flujo largo se pasa del límite de Vercel y el cierre da 504. El techo no se puede subir; avisar, sí.
            leido.avisado = true
            val aviso = "grabación larga: ${leido.mandados} pasos. Riesgo de 504 al cerrar (el post-procesado de graph se pasa del límite de vercel): mejor pártela en dos"
            log.log(TAG, aviso)
            try {
                avisar(aviso)
            } catch (e: Exception) {
                log.log(TAG, "el aviso no se pudo dar: ${medidaDe(e)}")
            }
        }
    }

    /**
     * Deja el cierre en `cierres-pendientes/` para [reintentarPendientes], cuando el provisional no llegó a disco. Corre bajo
     * [NonCancellable]: si no puede, lo dice y sigue.
     */
    private suspend fun guardarPendiente(sessionId: String, workflowId: String, avisos: Avisos) {
        val fallo = escribirPendiente(CierrePendiente(sessionId, workflowId, ahoraMs()))
        if (fallo == null) log.log(TAG, "cierre pendiente guardado (sesión $sessionId): se reintenta al arrancar")
        else avisos.anotar(
            "el cierre pendiente no se pudo guardar (${unaLinea(fallo)}): no se reintentará solo",
            "el cierre pendiente no se pudo guardar (${medidaDe(fallo)}): no se reintentará solo",
        )
    }

    /** Escribe [pendiente] en [donde]: `null` si quedó, o lo que falló. La cancelación sale tal cual. */
    private suspend fun escribirPendiente(pendiente: CierrePendiente, donde: String = ruta(CARPETA_PENDIENTES, pendiente.sessionId)): Exception? = try {
        almacen.escribirEntero(donde, LeccionJson.encodeToString(CierrePendiente.serializer(), pendiente))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }

    /** Un intento más en el pendiente de [ruta]: el arranque siguiente empieza por los que menos veces se intentaron (411). */
    private suspend fun anotarIntento(ruta: String, p: CierrePendiente) {
        escribirPendiente(CierrePendiente(p.sessionId, p.workflowId, p.cuandoMs, p.intentos + 1), ruta)
            ?.let { log.log(TAG, "no pude anotar el intento del cierre pendiente $ruta (${medidaDe(it)})") }
    }

    /**
     * d) y e) La nota de contexto, solo si hay —después del cierre ya no hay sesión a la que adjuntarla—, y el cierre. Lo que no
     * sale se juzga con el mismo criterio que al arrancar (411). La cancelación sale tal cual: la manda el tope (420).
     */
    private suspend fun cerrarEnGraph(a: Abierta, nota: String, resumen: ResumenDeVideo?, avisos: Avisos): EnGraph {
        val sid = a.sesion.sessionId
        val contexto = listOfNotNull(
            nota.vacioEsAusente(),
            resumen?.resumen.vacioEsAusente()?.let { "lo que se vio en el video: ${it.trim()}" },
        ).joinToString("\n\n")
        if (contexto.isNotEmpty()) {
            try {
                cliente.notaDeContexto(sid, contexto)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                avisos.anotar(
                    "la nota de contexto no viajó (${unaLinea(e)}): la sesión se cierra sin ella",
                    "la nota de contexto no viajó (${medidaDe(e)}): la sesión se cierra sin ella",
                )
            }
        }
        return try {
            val cerrada = cliente.terminar(sid, a.sesion.workflowId)
            EnGraph(Cierre.CERRADA, cerrada.workflowId ?: a.sesion.workflowId, motivo = "", medida = "", faltaElResumen = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnGraph(trasFallo(e), a.sesion.workflowId, unaLinea(e), medidaDe(e), faltaElResumen = e is FinishPendiente)
        }
    }

    /**
     * Corre [red] —ya bajo [NonCancellable]— sin tope mientras nadie cancele a [quienLlama]. Si ya lo cancelaron, o lo cancelan
     * mientras tanto, [red] tiene [TOPE_DE_CIERRE_CANCELADO], esperado con [esperarTope]: vencido, se corta y devuelve `null`
     * (420). Un `viewModelScope` cancelado no queda minutos detrás de una nota y tres `finish` de 90 s. Cortar a tiempo depende de
     * que el transporte sea cancelable, y el de la app lo es (`GraphTransport`: `disconnect()` al cancelar).
     */
    private suspend fun <T> conTopeSiCancelan(quienLlama: Job?, red: suspend () -> T): T? = coroutineScope {
        val cancelaron = CompletableDeferred<Unit>()
        // Un hijo de quien llama se entera de su cancelación; lo que corre aquí, bajo NonCancellable, no.
        val vigia = when {
            quienLlama == null -> null
            quienLlama.isCancelled -> null.also { cancelaron.complete(Unit) }
            else -> CoroutineScope(this.coroutineContext.minusKey(Job) + quienLlama).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    cancelaron.complete(Unit)
                }
            }
        }
        val trabajo = async { red() }
        val cronometro = launch {
            cancelaron.await()
            esperarTope(TOPE_DE_CIERRE_CANCELADO)
            trabajo.cancel(CancellationException("se agotó el tope del cierre cancelado"))
        }
        try {
            trabajo.await()
        } catch (e: CancellationException) {
            null
        } finally {
            cronometro.cancel()
            vigia?.cancel()
        }
    }

    /**
     * Qué es un cierre que no salió, con UN criterio al cerrar y al arrancar (411). Pendiente: el transitorio que agotó
     * sus intentos, la key que no vale y lo que no se pudo ni intentar (sin key), porque una key se arregla y la sesión
     * no murió por eso. Incierto: la lectura agotada, porque Graph pudo haberlo cerrado, y cobrado. Lo demás, fallido.
     */
    private fun trasFallo(e: Exception): Cierre = when {
        e is FinishPendiente -> Cierre.PENDIENTE
        e is GraphException && (e.status == 401 || e.status == 403) -> Cierre.PENDIENTE
        e is GraphException && e.status == TransportReply.TIMED_OUT -> Cierre.INCIERTO
        e is GraphException -> Cierre.FALLIDO
        else -> Cierre.PENDIENTE
    }

    /** Un [empezar] que no llegó a abrir vuelve a NUEVA, bajo candado y aunque lo hayan cancelado: si se descartó mientras tanto, sigue descartada. */
    private suspend fun volverANueva() {
        withContext(NonCancellable) { candado.withLock { if (estado == Estado.ABRIENDO) estado = Estado.NUEVA } }
    }

    /**
     * Corre [bloque] entero aunque cancelen la corrida: lo que escribe en disco o publica en Graph no se deja a medias
     * (418). Devuelve la cancelación que llegó mientras tanto, o `null`. Lo que [bloque] produce lo deja en variables,
     * nunca como valor: con la corrida cancelada, `withContext` descarta su resultado y lanza al volver.
     */
    private suspend fun sinCancelar(bloque: suspend () -> Unit): CancellationException? = try {
        withContext(NonCancellable) { bloque() }
        null
    } catch (e: CancellationException) {
        e
    }

    /**
     * Lo que el lector ya no va a mandar —el paso en vuelo, lo que la cola le entregó sin que llegara a tomarlo, lo que
     * sigue en la cola—, en ese orden: los pasos cuentan como no enviados con [motivo] y las notas se suman a la nota.
     * Solo con el lector terminado. Devuelve cuántos pasos.
     */
    private fun cortar(a: Abierta, motivo: String): Int {
        val leido = a.leido
        val sinSalir = buildList<Observado> {
            leido.enCurso?.let { add(it) }
            addAll(leido.sinEntregar)
            while (true) add(a.cola.tryReceive().getOrNull() ?: break)
        }
        leido.enCurso = null
        leido.sinEntregar.clear()
        var cortados = 0
        for (o in sinSalir) when (o) {
            is Observado.Nota -> leido.anotar(o.texto)
            is Observado.Paso -> {
                cortados++
                leido.pasos += PasoDeLeccion(leido.pasos.size + 1, o.horaMs, o.paso, enviado = false, motivo = motivo)
            }
        }
        return cortados
    }

    private suspend fun borrarPendiente(ruta: String) {
        try {
            almacen.borrar(ruta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(TAG, "no pude borrar el cierre pendiente $ruta (${medidaDe(e)}): el próximo arranque lo intentará una vez")
        }
    }

    /** Qué le falta a la lección, en una línea; `null` si está entera. Con el lector muerto nunca lo está (417). */
    private fun faltante(pasos: List<PasoDeLeccion>, dondeTermina: String, lectorMuerto: String?): String? {
        val falta = mutableListOf<String>()
        if (pasos.isEmpty()) falta += "no se observó ningún paso"
        val perdidos = pasos.count { !it.enviado }
        if (perdidos > 0) falta += "$perdidos de ${pasos.size} paso(s) no llegaron a graph"
        lectorMuerto?.let { falta += "$it: lo que se observó después no viajó" }
        if (dondeTermina.isBlank()) falta += "no se supo dónde terminó"
        return if (falta.isEmpty()) null else falta.joinToString("; ")
    }

    companion object {
        const val CARPETA_LECCIONES = "lecciones"
        const val CARPETA_PENDIENTES = "cierres-pendientes"
        const val CARPETA_VIDEOS = "videos-por-reprocesar"
        const val SUPERFICIE = "a11y"
        const val PLATAFORMA = "android"
        /** `WorkflowTeachSession.cs`: `AvisoPasos`. */
        const val AVISO_PASOS = 30
        val TOPE_DE_VACIADO: Duration = 30.seconds
        /** Lo que un cierre cancelado le da a la red antes de dejar el pendiente y soltar (420). */
        val TOPE_DE_CIERRE_CANCELADO: Duration = 2.minutes
        /** Cuántos cierres pendientes intenta un arranque; el resto, en el siguiente (411). */
        const val MAX_PENDIENTES_POR_ARRANQUE = 5
        /** Lo que puede tardar un arranque en total antes de dejar el resto para el siguiente (411). */
        val TOPE_DE_ARRANQUE: Duration = 2.minutes
        private const val TAG = "leccion"
        private const val HEX = "0123456789ABCDEF"

        /** `carpeta/<id>.json`, con el id escapado: todo lo que no es letra, dígito, `-`, `_` o `.` va como `%XX`. */
        fun ruta(carpeta: String, id: String): String = "$carpeta/${archivo(id)}.json"

        private fun archivo(id: String): String = id.encodeToByteArray().joinToString("") { byte ->
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (b < 0x80 && (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.')) c.toString()
            else "%" + HEX[b shr 4] + HEX[b and 0xF]
        }

        /** El mensaje de un fallo en una sola línea: lo que se le dice al usuario y lo que queda en disco. Nunca va al log (419). */
        private fun unaLinea(e: Throwable): String =
            (e.message ?: e::class.simpleName ?: "sin causa").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "sin causa" }

        /** La causa de un fallo como la dice cada destino: entera para el usuario y el disco, medida para el log (419). */
        private val PARA_EL_USUARIO: (Throwable) -> String = { unaLinea(it) }
        private val PARA_EL_LOG: (Throwable) -> String = { medidaDe(it) }
    }
}
