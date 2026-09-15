package graph.core.graph.learning

import graph.core.domain.GraphLog
import graph.core.graph.Reintentos
import graph.core.graph.TransportReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * UNA ENSEÑANZA contra Graph y el disco (spec 004, fase 4A2). Espejo del comportamiento de
 * `WorkflowRecorder.cs` + `WorkflowTeachSession.cs` de Windows, sin Android, sin MediaProjection y sin red
 * propia: habla con Graph por [LearningClient], con el disco por [Almacen], y el video le llega como una
 * función. Una instancia, una demostración: NUEVA → GRABANDO → TERMINADA o DESCARTADA.
 *
 * Reglas:
 *  - sin sesión abierta en Graph no se enseña: [empezar] devuelve el motivo y no deja nada abierto (408);
 *  - [pasoObservado] y [nota] solo ENCOLAN (los llama el hilo de la superficie, que no se puede bloquear) y
 *    UN SOLO LECTOR manda en serie: Graph numera los pasos por orden de llegada. Un paso que falla queda
 *    contado con su motivo y sigue el siguiente (407);
 *  - [terminar] va en este orden, y el orden es el contrato: vaciar la cola (con tope) → la lección a disco
 *    → el video → la nota → el cierre (409-412);
 *  - [descartar] no publica nada: ni pasos pendientes, ni nota, ni cierre, ni lección (412);
 *  - un cierre que no sale por un transitorio queda en disco y [reintentarPendientes] lo intenta al
 *    arrancar; una lectura agotada no deja pendiente automático (411).
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
        var mandados = 0
        var avisado = false

        fun anotar(texto: String) {
            if (nota.isNotEmpty()) nota.append('\n')
            nota.append(texto)
        }
    }

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
     * devuelve el motivo en una línea, no queda lector ni cola, y se puede volver a intentar.
     */
    suspend fun empezar(identidad: IdentidadDePantalla, descripcion: String): Arranque {
        candado.withLock {
            check(estado == Estado.NUEVA) { "esta lección ya empezó (${estado.name.lowercase()}): una lección, una demostración" }
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
            if (estado == Estado.ABRIENDO) estado = Estado.NUEVA
            throw e
        } catch (e: Exception) {
            if (estado == Estado.ABRIENDO) estado = Estado.NUEVA
            val motivo = "no se puede enseñar: graph no abrió la sesión — ${unaLinea(e)}"
            log.log(TAG, motivo)
            return Arranque.NoSePuede(motivo)
        }
        val id = checkNotNull(info.id) { "crearSesion devolvió una sesión sin id" }
        val sesion = Arranque.Ensenando(sessionId = id, workflowId = info.workflowId ?: id)
        return candado.withLock {
            if (estado != Estado.ABRIENDO) {
                // Se descartó mientras Graph abría: allá la sesión existe, aquí no se hace nada más (ver descartar).
                log.log(TAG, "la sesión ${sesion.sessionId} se abrió con la demostración ya descartada: no se enseña")
                return@withLock Arranque.NoSePuede("no se enseña: la demostración se descartó mientras graph abría la sesión")
            }
            val cola = Channel<Observado>(Channel.UNLIMITED)
            val leido = Leido()
            val lector = scope.launch { leer(sesion.sessionId, cola, leido) }
            abierta = Abierta(sesion, identidad, descripcion, ahoraMs(), cola, leido, lector)
            estado = Estado.GRABANDO
            log.log(TAG, "▶ enseñando en ${identidad.url} (sesión ${sesion.sessionId})")
            sesion
        }
    }

    /** Encola un paso observado y vuelve enseguida. `false` si no hay enseñanza en curso: el paso no va a ningún lado. */
    fun pasoObservado(paso: StepRequest): Boolean {
        val a = abierta ?: return false
        if (estado != Estado.GRABANDO) return false
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
     *     [topeDeVaciado] se corta el lector, y lo que no salió cuenta como no enviado, con su motivo;
     *  b) escribe la lección en disco ANTES de tocar la red, en una sola escritura: el video, la nota y el
     *     cierre pueden fallar (429, 504, sin red) y la lección no depende de ninguno;
     *  c) procesa el video con [video]; si lanza o devuelve `null`, se sigue y queda marcado para reprocesar;
     *  d) la nota de contexto (lo hablado y el resumen del video), solo si hay: después del cierre ya no hay
     *     sesión a la que adjuntarla;
     *  e) cierra la sesión. Si no sale por un transitorio, el cierre queda pendiente en disco; si Graph no
     *     respondió a tiempo, pudo haberla cerrado y no se deja pendiente.
     */
    suspend fun terminar(dondeTermina: String, video: suspend () -> ResumenDeVideo?): ResultadoDeLeccion {
        val a = candado.withLock {
            check(estado == Estado.GRABANDO) { "no hay ninguna enseñanza en curso (${estado.name.lowercase()})" }
            estado = Estado.CERRANDO
            checkNotNull(abierta)
        }
        val sid = a.sesion.sessionId
        val avisos = mutableListOf<String>()

        // a) Vaciar la cola.
        a.cola.close()
        if (withTimeoutOrNull(topeDeVaciado) { a.lector.join() } == null) {
            a.lector.cancelAndJoin()
            val motivo = "no llegó a enviarse antes de cerrar (tope de ${Reintentos.corto(topeDeVaciado)})"
            val sinSalir = buildList<Observado> {
                a.leido.enCurso?.let { add(it) }
                while (true) add(a.cola.tryReceive().getOrNull() ?: break)
            }
            var cortados = 0
            for (o in sinSalir) when (o) {
                is Observado.Nota -> a.leido.anotar(o.texto)
                is Observado.Paso -> {
                    cortados++
                    a.leido.pasos += PasoDeLeccion(a.leido.pasos.size + 1, o.horaMs, o.paso, enviado = false, motivo = motivo)
                }
            }
            if (cortados > 0) avisos += "$cortados paso(s) no llegaron a enviarse antes de cerrar (tope de ${Reintentos.corto(topeDeVaciado)})"
        }
        val pasos = a.leido.pasos.toList()
        val nota = a.leido.nota.toString()

        // b) La lección, a disco, antes de la red.
        val faltante = faltante(pasos, dondeTermina)
        val rutaLeccion = ruta(CARPETA_LECCIONES, sid)
        val leccion = try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            avisos += "la lección no se pudo guardar en disco (${unaLinea(e)}): graph sigue teniendo los pasos"
            null
        }
        faltante?.let { avisos += "a la lección le falta algo: $it" }

        // c) El video.
        var motivoVideo: String? = null
        val resumen = try {
            video() ?: run { motivoVideo = "procesar el video no dejó resultado"; null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            motivoVideo = unaLinea(e)
            null
        }
        motivoVideo?.let { m ->
            val marca = try {
                almacen.escribirEntero(
                    ruta(CARPETA_VIDEOS, sid),
                    LeccionJson.encodeToString(VideoParaReprocesar.serializer(), VideoParaReprocesar(sid, leccion, m, ahoraMs())),
                )
                "queda para reprocesar"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "y no se pudo marcar para reprocesar (${unaLinea(e)})"
            }
            avisos += "el video no se procesó ($m): la sesión se cierra con los pasos y el video $marca"
        }

        // d) La nota de contexto, antes del cierre.
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
                avisos += "la nota de contexto no viajó (${unaLinea(e)}): la sesión se cierra sin ella"
            }
        }

        // e) El cierre.
        var workflowId = a.sesion.workflowId
        var motivoCierre = ""
        val cierre = try {
            cliente.terminar(sid, a.sesion.workflowId).workflowId?.let { workflowId = it }
            Cierre.CERRADA
        } catch (e: FinishPendiente) {
            guardarPendiente(e, avisos)
            Cierre.PENDIENTE
        } catch (e: GraphException) {
            motivoCierre = unaLinea(e)
            if (e.status == TransportReply.TIMED_OUT) Cierre.INCIERTO else Cierre.FALLIDO
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            motivoCierre = unaLinea(e)
            Cierre.FALLIDO
        }

        // f) El resultado.
        val mandados = pasos.count { it.enviado }
        val cuantos = if (mandados == 1) "1 paso" else "$mandados pasos"
        val sinComprobar = Comprobacion.SIN_COMPROBAR.texto
        val mensaje = when (cierre) {
            Cierre.CERRADA -> "aprendí «${a.descripcion.trim().ifEmpty { workflowId }}» ($cuantos), $sinComprobar"
            Cierre.PENDIENTE -> "aprendido pero pendiente de cerrar en Graph: $cuantos ya guardados, falta el resumen y se reintenta al arrancar; $sinComprobar"
            Cierre.INCIERTO -> "Graph no respondió a tiempo al cerrar y pudo haberlo cerrado: no se reintenta solo para no cobrar dos veces ($cuantos); $sinComprobar"
            Cierre.FALLIDO -> "Graph no cerró la sesión ($motivoCierre): $cuantos mandados; $sinComprobar"
        }
        estado = Estado.TERMINADA
        abierta = null
        log.log(TAG, "■ $mensaje" + if (avisos.isEmpty()) "" else " · ${avisos.joinToString(" · ")}")
        return ResultadoDeLeccion(
            sessionId = sid,
            workflowId = workflowId,
            pasos = pasos,
            cierre = cierre,
            leccion = leccion,
            videoParaReprocesar = motivoVideo != null,
            resumenDeVideo = resumen,
            avisos = avisos,
            mensaje = mensaje,
        )
    }

    /**
     * La demostración salió mal y se tira: no publica NADA. Corta el lector (un paso en vuelo se cancela) y
     * suelta lo encolado y la nota; no escribe en disco y no llama a Graph. Si la sesión ya estaba abierta en
     * Graph, queda sin cerrar: el único cierre que Graph tiene es `finish`, que post-procesa y persiste, y
     * cerrar sería publicar. Windows hace lo mismo (`TeachSession.DiscardAsync` borra el mp4 sin llamar a
     * Graph). Solo tiene efecto mientras se abre o se graba: descartar después de terminar no des-publica.
     */
    suspend fun descartar(): Boolean {
        val a = candado.withLock {
            if (estado != Estado.GRABANDO && estado != Estado.ABRIENDO) return true
            estado = Estado.DESCARTADA
            abierta.also { abierta = null }
        }
        if (a != null) {
            a.lector.cancel()
            a.cola.cancel()
            a.lector.join()
        }
        log.log(TAG, "demostración descartada: no se publica nada" + (a?.let { " (la sesión ${it.sesion.sessionId} queda sin cerrar en graph)" } ?: ""))
        return true
    }

    /**
     * Para el arranque de la app: reintenta los cierres que quedaron pendientes. El que sale se borra; el que
     * vuelve a no salir por un transitorio, o no se pudo intentar (sin key, la key no vale), se queda. Los que
     * Graph ya no reconoce (la sesión murió con su instancia) o a los que no respondió a tiempo (pudo haberlos
     * cerrado) se borran: reintentarlos sería para siempre, o cobrar dos veces.
     */
    suspend fun reintentarPendientes(): PendientesReintentados {
        val rutas = try {
            almacen.listar(CARPETA_PENDIENTES).sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(TAG, "no pude leer los cierres pendientes: ${unaLinea(e)}")
            return PendientesReintentados(0, 0, 0)
        }
        var cerrados = 0
        var siguen = 0
        var descartados = 0
        for (ruta in rutas) {
            val pendiente = try {
                almacen.leer(ruta)?.let { LeccionJson.decodeFromString(CierrePendiente.serializer(), it) } ?: continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(TAG, "el cierre pendiente $ruta no se pudo leer (${unaLinea(e)}): se deja")
                siguen++
                continue
            }
            try {
                cliente.terminar(pendiente.sessionId, pendiente.workflowId)
                cerrados++
                log.log(TAG, "✓ cierre completado (sesión ${pendiente.sessionId})")
                borrarPendiente(ruta)
            } catch (e: FinishPendiente) {
                siguen++
                log.log(TAG, "el cierre de ${pendiente.sessionId} sigue sin poder completarse (HTTP ${e.status})")
            } catch (e: GraphException) {
                if (e.status == 401 || e.status == 403) {
                    siguen++
                    log.log(TAG, "el cierre de ${pendiente.sessionId} espera una key que valga: ${unaLinea(e)}")
                } else {
                    descartados++
                    val porque = if (e.status == TransportReply.TIMED_OUT) "graph no respondió a tiempo y pudo haberlo cerrado" else "no es reintentable"
                    log.log(TAG, "cierre de ${pendiente.sessionId} descartado, $porque: ${unaLinea(e)}")
                    borrarPendiente(ruta)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                siguen++
                log.log(TAG, "el cierre de ${pendiente.sessionId} no se pudo intentar (${unaLinea(e)}): se deja")
            }
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
            val motivo = unaLinea(e)
            log.log(TAG, "el paso $orden no llegó a graph: $motivo")
            PasoDeLeccion(orden, o.horaMs, o.paso, enviado = false, motivo = motivo)
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
                log.log(TAG, "el aviso no se pudo dar: ${unaLinea(e)}")
            }
        }
    }

    private suspend fun guardarPendiente(e: FinishPendiente, avisos: MutableList<String>) {
        try {
            almacen.escribirEntero(
                ruta(CARPETA_PENDIENTES, e.sessionId),
                LeccionJson.encodeToString(CierrePendiente.serializer(), CierrePendiente(e.sessionId, e.workflowId, ahoraMs())),
            )
            log.log(TAG, "cierre pendiente guardado (sesión ${e.sessionId}): se reintenta al arrancar")
        } catch (x: CancellationException) {
            throw x
        } catch (x: Exception) {
            avisos += "el cierre pendiente no se pudo guardar (${unaLinea(x)}): no se reintentará solo"
        }
    }

    private suspend fun borrarPendiente(ruta: String) {
        try {
            almacen.borrar(ruta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(TAG, "no pude borrar el cierre pendiente $ruta (${unaLinea(e)}): el próximo arranque lo verá cerrado")
        }
    }

    /** Qué le falta a la lección, en una línea; `null` si está entera. */
    private fun faltante(pasos: List<PasoDeLeccion>, dondeTermina: String): String? {
        val falta = mutableListOf<String>()
        if (pasos.isEmpty()) falta += "no se observó ningún paso"
        val perdidos = pasos.count { !it.enviado }
        if (perdidos > 0) falta += "$perdidos de ${pasos.size} paso(s) no llegaron a graph"
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

        /** El mensaje de un fallo en una sola línea: lo que se le dice al usuario y lo que queda en disco. */
        private fun unaLinea(e: Throwable): String =
            (e.message ?: e::class.simpleName ?: "sin causa").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "sin causa" }
    }
}
