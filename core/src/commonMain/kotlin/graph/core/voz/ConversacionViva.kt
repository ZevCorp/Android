package graph.core.voz

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * LA CONVERSACIÓN VIVA (docs/specs/002, fase A2): abrir, oír, contestar herramientas, cerrar turnos y decidir en UN
 * solo sitio si se termina, se dice por qué o se reconecta. Pura: el socket es [CanalDeVoz], el tiempo es [Reloj] y
 * las manos son [ejecutar]. Comportamiento de `U-Windows-App/windows-client/src/Voice/ConversacionEnVivo.cs`,
 * reescrito, no traducido: cada decisión de aquí costó una sesión de voz real en Windows.
 *
 * TODO VIVE EN UN SOLO HILO O CORRUTINA CONFINADA: [TurnosSinMarca] no tiene candado. Quien la cablea (fase B) llama
 * a [conversar], [oirMicrofono], [escribir], [avisar] y [detener] desde el mismo despachador de un hilo. Las
 * herramientas corren en una corrutina hija del mismo despachador, así que una acción que tarda no frena la escucha.
 *
 * LO QUE ANTES FALLABA EN SILENCIO, y por eso está aquí y no en el cableado:
 *  - «sesión abierta» se escribía al conectar el socket, y sin crédito salió en el mismo segundo que el error (U, 2026-09-12);
 *  - con dos llamadas a 52 ms, un `response.create` por llamada dio `function_call_outputs_required` (U, 2026-09-12);
 *  - la cuenta sin crédito reconectó cuatro veces con el mismo `session.start` diciendo «Sigo…» cada vez;
 *  - el marcador de turnos no se recreaba al arrancar y lo dicho en una sesión cerraba el turno de la siguiente (W5).
 */
class ConversacionViva(
    private val canal: CanalDeVoz,
    private val protocolo: ProtocoloGptLive = ProtocoloGptLive(),
    /** La clave de hoy (OpenAI) o el token de mañana (Graph): se pide al arrancar, nunca se guarda aquí. */
    private val credencial: () -> String?,
    private val instruccionesVoz: String,
    instruccionesDelegado: String,
    utensilios: List<Utensilio>,
    private val ejecutar: suspend (Llamada) -> String,
    private val reproducir: (ByteArray) -> Unit,
    private val callar: () -> Unit,
    /** La cola del altavoz tiene bytes. Es la llave de la compuerta: el estado, jamás el volumen. */
    private val sonando: () -> Boolean,
    /** Lo que se le muestra o anuncia al usuario. Nunca la transcripción: esa va por [transcribe]. */
    private val dice: (String) -> Unit,
    private val log: (tag: String, mensaje: String) -> Unit,
    private val reloj: Reloj,
    private val compuertaActiva: Boolean = ModoDeCaptura.activa(forzada = false, aec = false),
    /** Lo dicho hasta ahora en el turno, acumulado, y de quién: quien pinte reemplaza la línea en vez de añadir. */
    private val transcribe: (texto: String, esDeU: Boolean) -> Unit = { _, _ -> },
    /** Empezó una petición del usuario, por voz o por texto. La cuenta de la petición (fase 3C) cuelga de aquí. */
    private val alAbrirPeticion: (por: String) -> Unit = {},
) {

    companion object {
        const val TAG = "voz-viva"

        const val AL_ARRANCAR = "Te escucho."

        /** GPT-Live no sabe volver a una sesión: la que abre es nueva, y fingir continuidad sería mentir. */
        const val AL_VOLVER = "Se cortó un instante. Sigo, pero olvidé lo último que hablábamos."

        const val SIN_CREDENCIAL = "No hay voz en vivo: falta la clave de la voz (OPENAI_API_KEY)."
        const val SIN_RED = "No pude abrir la voz: no hay conexión con el servidor. Lo intenté 3 veces; revisa tu internet y vuelve a darle al micrófono."
        const val NO_VUELVE = "Se me cortó la conexión y no consigo volver. Vuelve a darle al micrófono."

        /** Solo la red se reintenta al abrir: una clave que no vale falla igual las tres veces. */
        const val INTENTOS_DE_APERTURA = 3
        const val ESPERA_DE_APERTURA_MS = 1000L

        /** Tras un corte: máximo 4, con 300·n ms. El contador vuelve a cero en cada turno cerrado: hubo conversación. */
        const val RECONEXIONES = 4
        const val ESPERA_DE_RECONEXION_MS = 300L

        /** «Backend response input history is limited to 128 items and 32768 UTF-8 bytes per session» (U, 2026-09-12). */
        const val TOPE_DE_ITEMS = 128
        const val AVISO_DE_ITEMS = 120

        const val AVISO_DEL_SISTEMA = "[aviso del sistema] "
    }

    /** Lo que es de UNA conexión, y por eso muere con ella: la sesión del servidor que abre es otra. */
    private class Conexion(reloj: Reloj, val alConfirmar: String) {
        val turnos = TurnosSinMarca({ reloj.ahora() })

        /** Pedidas y sin contestar, por instancia: un call_id puede venir vacío. */
        val sinContestar = mutableListOf<Llamada>()

        var confirmada = false

        /** Lo primero que dijo el servidor antes de confirmar: si el socket muere sin confirmar, eso es por qué no abrió. */
        var fallaAntesDeAbrir = ""

        /** Por qué no se arregla reconectando. La primera gana: el error llega antes que el cierre y con su mensaje. */
        var causa: String? = null
        var dichoDeLaCausa = ""

        var cayoSolo = false
        var resultadosSinPedir = false
        var segundos = 0.0
        var items = 0

        fun anotar(porque: String?, dicho: String) {
            if (porque == null || causa != null) return
            causa = porque
            dichoDeLaCausa = dicho
        }
    }

    private class Tanda(val conexion: Conexion, val llamadas: List<Llamada>)

    private var delegado = instruccionesDelegado
    private var herramientas = utensilios

    /** El modo vigente no es el de siempre: una sesión que abre en él se lo tiene que repetir a la voz. */
    private var modoEspecial = false

    var viva = false
        private set

    /** Peticiones del usuario abiertas en esta conversación, por voz o por texto. Un aviso del sistema no es una. */
    var peticiones = 0
        private set

    /** Items creados en la sesión del servidor en curso: mensajes escritos, resultados y avisos. */
    val itemsEnSesion: Int get() = conexion?.items ?: 0

    private var detenida = false
    private var conexion: Conexion? = null
    private var conexiones = 0
    private var reconexiones = 0
    private var segundosAnteriores = 0.0
    private var tandas = Channel<Tanda>(Channel.UNLIMITED)

    /** Una tanda que contesta y un aviso que sale no se cruzan: si no, el `response.create` podía salir dos veces. */
    private val envio = Mutex()

    private val retiradas = mutableSetOf<String>()
    private val avisos = ArrayDeque<String>()
    private val fraseU = StringBuilder()
    private val fraseUsuario = StringBuilder()

    private val compuerta = CompuertaDeEco()
    private val detector = DetectorDeInterrupcion()
    private var tragadoAnunciado = 0L
    private var ultimoFalloDeEnvio = ""

    /** La espera entre intentos que está en curso: [detener] la corta en vez de esperar a que venza. */
    private var espera: Job? = null

    /**
     * Abre y conversa hasta que se acaba: vuelve cuando la voz terminó, por la vía que sea. Toda escucha que termina
     * —cierre, excepción, cancelación, o una apertura que no llegó a escuchar— pasa por [alTerminarLaEscucha].
     */
    suspend fun conversar() {
        if (viva) return
        val clave = credencial()?.trim().orEmpty()
        if (clave.isEmpty()) {
            log(TAG, "sin credencial: no se llama a nadie")
            dice(SIN_CREDENCIAL)
            return
        }
        viva = true
        detenida = false
        reconexiones = 0
        conexiones = 0
        segundosAnteriores = 0.0
        conexion = null
        retiradas.clear()
        avisos.clear()
        val cola = Channel<Tanda>(Channel.UNLIMITED).also { tandas = it }

        coroutineScope {
            // UNA TANDA DETRÁS DE OTRA, en el orden en que llegaron: dos manos sobre la pantalla a la vez no se cruzan.
            val obrero = launch { for (tanda in cola) atender(tanda) }
            try {
                var reconectando = false
                while (true) {
                    val c = empiezaUnaConexion(if (reconectando) AL_VOLVER else AL_ARRANCAR)
                    var via = "apertura"
                    var reconecta = false
                    try {
                        if (reconectando) esperar(ESPERA_DE_RECONEXION_MS * reconexiones)
                        if (!detenida && conectar(c, clave, reconectando)) via = escuchar(c)
                    } catch (e: CancellationException) {
                        via = "cancelación"
                        throw e
                    } finally {
                        reconecta = alTerminarLaEscucha(c, via)
                    }
                    if (!reconecta) break
                    reconectando = true
                }
            } finally {
                cola.close()
                obrero.cancel()
                terminar()
            }
        }
    }

    /**
     * Lo pide el usuario: corta la espera en curso, y nunca reconecta ni anuncia un fatal. La decisión la toma igual
     * [alTerminarLaEscucha].
     */
    fun detener() {
        if (!viva || detenida) return
        detenida = true
        espera?.cancel()
        log(TAG, "la voz se detiene a pedido")
        callar()
        canal.cerrar("fin")
    }

    /**
     * Un trozo de micrófono. Sin sesión confirmada no sale: el servidor aún no escucha. Con la compuerta activa, lo que
     * Ü suena se sustituye por silencio del mismo tamaño, y el detector es el único oído que queda para la interrupción.
     */
    suspend fun oirMicrofono(pcm: ByteArray) {
        val c = conexion
        if (!viva || detenida || c == null || !c.confirmada) return
        var trozo = pcm
        if (compuertaActiva) {
            val ahora = reloj.ahora()
            val filtrado = compuerta.filtrar(pcm, sonando(), ahora)
            // EL DETECTOR VIVE EN LA ERA DE LA COMPUERTA (trozo tragado = eco), no en el estado crudo de la cola: con el
            // crudo, cada parpadeo entre ráfagas re-arrancaba la siembra y no disparó nunca (U, 2026-08-31).
            val trago = filtrado !== pcm
            trozo = filtrado
            if (trago && detector.oye(rms(pcm), sonando = true, ahora = ahora)) {
                // La primera sílaba de quien interrumpe es lo que el servidor necesita oír: viaja ESTE trozo, intacto.
                callar()
                compuerta.abrir()
                trozo = pcm
                log(TAG, "te oí encima: corto mi voz y te escucho")
            } else if (!trago) {
                detector.oye(0.0, sonando = false, ahora = ahora)
            }
            if (trozo === pcm && compuerta.msTragados > tragadoAnunciado) {
                log(TAG, "compuerta de eco: tragó ${compuerta.msTragados - tragadoAnunciado} ms de micrófono mientras Ü sonaba; el micrófono vuelve a viajar")
                tragadoAnunciado = compuerta.msTragados
            }
        }
        try {
            canal.enviar(protocolo.audio(trozo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Un trozo perdido no tira la sesión, pero perderlo en silencio sería un mensaje mudo: una línea por motivo.
            if (e.message != ultimoFalloDeEnvio) {
                ultimoFalloDeEnvio = e.message.orEmpty()
                log(TAG, "un trozo de micrófono no llegó al servidor: ${e.message}")
            }
        }
    }

    /** Lo escrito es una petición y pide respuesta: sin el `response.create` el servidor lo acepta y calla (medido). */
    suspend fun escribir(texto: String) {
        if (texto.isBlank()) return
        val c = conexion
        if (!viva || detenida || c == null || !c.confirmada) {
            log(TAG, "no hay sesión confirmada: lo escrito no sale")
            return
        }
        abrePeticion("texto")
        transcribe(texto, false)
        enviando("lo escrito") {
            val (item, pide) = protocolo.texto(texto)
            mandarItem(c, item)
            canal.enviar(pide)
        }
    }

    /**
     * Una nota para la voz que nadie dijo en voz alta («la tarea terminó: …»). NO abre petición, y ESPERA a que no quede
     * ninguna llamada sin contestar: su `response.create` con una salida pendiente es lo que el servidor rechaza.
     */
    suspend fun avisar(texto: String) {
        if (!viva || detenida || texto.isBlank()) return
        avisos.addLast(texto.trim())
        val c = conexion ?: return
        if (c.sinContestar.isNotEmpty()) {
            log(TAG, "aviso del sistema en cola: sale cuando se contesten las ${c.sinContestar.size} llamada(s) pendientes")
        }
        enviando("el aviso del sistema") { pedirRespuestaSiToca(c) }
    }

    /** Llamadas que ya no hay que hacer: ni se ejecutan ni se contestan. Contestarlas es lo que las hacía repetirse. */
    fun retirar(ids: List<String>) {
        val validos = ids.filter { it.isNotEmpty() }
        if (validos.isEmpty()) return
        if (retiradas.size > 200) retiradas.clear()
        retiradas += validos
        log(TAG, "retiradas: ${validos.joinToString()}")
    }

    /**
     * Otro modo sin reabrir la sesión: otro `session.start` sería otra conversación. Se recuerda, y una reconexión
     * abre ya en este modo: la delegación en el `session.start` y, al confirmarse, el append a la voz.
     */
    suspend fun cambiarModo(instrucciones: String, utensilios: List<Utensilio>, vuelve: Boolean) {
        delegado = instrucciones
        herramientas = utensilios
        modoEspecial = !vuelve
        val c = conexion
        if (!viva || detenida || c == null || !c.confirmada) {
            log(TAG, "modo guardado para la próxima apertura: no hay sesión confirmada a la que cambiárselo")
            return
        }
        enviando("el cambio de modo") {
            for (m in protocolo.cambiarDeModo(instrucciones, utensilios, vuelve, instruccionesVoz)) canal.enviar(m)
            log(TAG, "modo cambiado sin reabrir la sesión: ${utensilios.size} herramienta(s), instrucciones de ${instrucciones.length} car." + if (vuelve) " · vuelve al de siempre" else "")
        }
    }

    // ── Abrir ────────────────────────────────────────────────────────────────

    /**
     * Cada conexión nace con su marcador de turnos, sin causa, sin falla y sin confirmar: lo dicho —o una llamada sin
     * devolver— en la anterior no cierra ni sujeta un turno de esta (W5 en U). Sus segundos se apartan para sumarlos.
     */
    private fun empiezaUnaConexion(alConfirmar: String): Conexion {
        conexion?.let { segundosAnteriores += it.segundos }
        fraseU.clear()
        fraseUsuario.clear()
        conexiones++
        return Conexion(reloj, alConfirmar).also { conexion = it }
    }

    /** Abre el socket y manda el único `session.start`. Verdadero si hay algo que escuchar. */
    private suspend fun conectar(c: Conexion, clave: String, reconectando: Boolean): Boolean {
        var intento = 0
        while (true) {
            val apertura = try {
                canal.abrir(protocolo.url, protocolo.cabeceras(clave))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // El canal traduce lo que sabe; lo que no traduce no trae HTTP, y sin HTTP lo único que cabe es la red.
                Apertura.SinRed(e.message ?: "error al abrir")
            }
            when (apertura) {
                Apertura.Ok -> {
                    if (detenida) {
                        canal.cerrar("fin")
                        return false
                    }
                    try {
                        canal.enviar(protocolo.apertura(instruccionesVoz, delegado, herramientas))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(TAG, "no pude mandar la apertura: ${e.message}")
                        c.cayoSolo = true
                        return false
                    }
                    // CONECTAR NO ES ABRIR: la línea dice lo que se sabe. «Sesión abierta» la escribe Hecho.Abierta.
                    log(TAG, "socket conectado, esperando confirmación de «${protocolo.modelo}»")
                    return true
                }

                is Apertura.Rechazo -> {
                    val dicho = "HTTP ${apertura.httpStatus}" + (apertura.codigoCabecera?.let { " $it" } ?: "")
                    log(TAG, "el apretón de manos no pasó: $dicho")
                    val causa = causaFatal(apertura.httpStatus.toString()) ?: causaFatal(apertura.codigoCabecera)
                    when {
                        causa != null -> c.anotar(causa, dicho)
                        reconectando -> c.cayoSolo = true
                        else -> c.fallaAntesDeAbrir = dicho
                    }
                    return false
                }

                is Apertura.SinRed -> {
                    log(TAG, "no se pudo abrir la sesión: sin red (${apertura.motivo})")
                    if (reconectando) {
                        c.cayoSolo = true
                        return false
                    }
                    intento++
                    if (intento >= INTENTOS_DE_APERTURA) {
                        dice(SIN_RED)
                        return false
                    }
                    esperar(ESPERA_DE_APERTURA_MS * intento)
                    if (detenida) return false
                    log(TAG, "reintentando abrir la voz (${intento + 1}/$INTENTOS_DE_APERTURA)…")
                }
            }
        }
    }

    /**
     * Esperar entre intentos, CORTABLE. Con la espera del reloj a secas, detener durante un reintento dejaba la voz viva
     * hasta que vencía (hasta 2 s): el micrófono en rojo sin nadie al otro lado. Quien esperaba mira después [detenida].
     */
    private suspend fun esperar(ms: Long) {
        if (detenida) return
        coroutineScope {
            val j = launch { reloj.esperar(ms) }
            espera = j
            j.join()
            espera = null
        }
    }

    // ── Escuchar ─────────────────────────────────────────────────────────────

    /** Recibe hasta que se acaba. Devuelve por dónde: «cierre» o «corte». La cancelación sale como excepción. */
    private suspend fun escuchar(c: Conexion): String {
        try {
            while (true) {
                when (val r = canal.recibir()) {
                    is Recibido.Mensaje -> try {
                        procesar(c, r.texto)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(TAG, "no pude reaccionar a un mensaje del servidor: ${e.message}")
                    }

                    is Recibido.Cierre -> {
                        cerro(c, r)
                        return "cierre"
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, "se cortó la escucha: ${e.message}")
            c.cayoSolo = true
            return "corte"
        }
    }

    /**
     * El socket se cerró. Si no lo pedimos, cayó solo; y la descripción del cierre es la segunda puerta de la causa
     * («insufficient_quota.credit_balance_exhausted»). El número no se mira: 1013 es «vuelve a intentarlo» en el RFC.
     */
    private fun cerro(c: Conexion, r: Recibido.Cierre) {
        if (detenida) return
        c.cayoSolo = true
        if (r.porRed) {
            log(TAG, "se cortó la conexión sin trama de cierre (${r.codigo})")
            return
        }
        log(TAG, "el servidor cerró la conexión: ${r.codigo} «${r.motivo}»")
        c.anotar(causaFatal(r.motivo), r.motivo)
    }

    /**
     * Cada mensaje: sus hechos, cada uno con su reacción, y DESPUÉS, traiga hechos o no, la pregunta de si toca cerrar
     * el turno. El audio de GPT-Live llega sin parar, también en silencio: ese es el tic, sin temporizador.
     */
    private suspend fun procesar(c: Conexion, json: String) {
        val hechos = protocolo.leer(json)
        if (hechos.isEmpty()) volcarCrudo(json)
        for (hecho in hechos) {
            if (c.turnos.oye(hecho)) abrePeticion("voz")
            reaccionar(c, hecho)
        }
        if (c.turnos.tocaCerrar()) cierraElTurno()
    }

    /**
     * LO QUE NO SE TRADUCE SE VUELCA, porque un evento desconocido es justo lo que hay que ver. Salvo el audio y lo que
     * teclea el delegado: en un turno medido, 78 de 98 mensajes sin hechos eran deltas del delegado, uno por ficha, y
     * se comían el log (U, 2026-09-12). De un `response.event` solo se escribe su tipo: su contenido es del delegado.
     */
    private fun volcarCrudo(json: String) {
        // Anidado de más ni se lee ni se vuelca: parsearlo aquí reventaba igual que en el traductor, y no se sabe de quién es.
        if (demasiadoAnidado(json)) {
            log(TAG, "← un mensaje anidado a más de $PROFUNDIDAD_MAXIMA niveles (${json.length} car.): no se lee")
            return
        }
        val m = leerSinReventar(json) as? JsonObject
        val tipo = (m?.get("type") as? JsonPrimitive)?.content.orEmpty()
        if (tipo == "session.output_audio.delta") return
        if (tipo == "response.event") {
            val evento = ((m?.get("event") as? JsonObject)?.get("type") as? JsonPrimitive)?.content.orEmpty()
            if (!evento.endsWith(".delta")) log(TAG, "← response.event «$evento»")
            return
        }
        log(TAG, "← " + if (json.length > 400) json.take(400) + "…" else json)
    }

    private suspend fun reaccionar(c: Conexion, hecho: Hecho) {
        when (hecho) {
            is Hecho.Suena -> reproducir(hecho.pcm)

            is Hecho.DiceElUsuario -> {
                fraseUsuario.append(hecho.trozo)
                transcribe(fraseUsuario.toString(), false)
            }

            is Hecho.DiceU -> {
                fraseU.append(hecho.trozo)
                transcribe(fraseU.toString(), true)
            }

            // SE ANOTAN ANTES DE LANZARLAS: la siguiente de la tanda ya las cuenta aunque esta termine enseguida.
            is Hecho.Pide -> {
                log(TAG, "llamada recibida: " + hecho.llamadas.joinToString { it.nombre })
                for (l in hecho.llamadas) if (c.sinContestar.none { it === l }) c.sinContestar += l
                tandas.trySend(Tanda(c, hecho.llamadas))
            }

            is Hecho.Retira -> retirar(hecho.ids)

            // ACUMULADO, no incremento: 12.0 y luego 25.0 en la misma sesión son 25 s, no 37.
            is Hecho.Duracion -> c.segundos = max(c.segundos, hecho.segundos)

            // La primera puerta de la causa: el code, nunca la prosa, que está en inglés y cambia de redacción.
            is Hecho.Falla -> {
                log(TAG, "el servidor dice: ${hecho.que}")
                if (!c.confirmada && c.fallaAntesDeAbrir.isEmpty()) c.fallaAntesDeAbrir = hecho.que
                c.anotar(causaFatal(hecho.codigo), hecho.que)
            }

            // LA SESIÓN ABRIÓ DE VERDAD: lo único que afirma «sesión abierta», y una vez por conexión.
            // EN UN MODO ESPECIAL LA VOZ LO OYE OTRA VEZ: el `session.start` abre con su persona, y sin el append el
            // delegado reabría en un modo y la voz en el de siempre. Antes de confirmar el servidor aún no escucha.
            Hecho.Abierta -> if (!c.confirmada) {
                c.confirmada = true
                log(TAG, "sesión abierta con «${protocolo.modelo}»: el servidor la confirmó")
                dice(c.alConfirmar)
                enviando("el modo vigente") {
                    if (modoEspecial) {
                        canal.enviar(protocolo.recordarModo(delegado))
                        log(TAG, "la sesión abrió en un modo especial: se le repite a la voz (instrucciones de ${delegado.length} car.)")
                    }
                    pedirRespuestaSiToca(c)
                }
            }

            Hecho.CierraElTurno -> cierraElTurno()

            Hecho.HablaronEncima -> callar()
        }
    }

    private fun abrePeticion(por: String) {
        peticiones++
        log(TAG, "petición nueva (por $por)")
        alAbrirPeticion(por)
    }

    /** Lo dicho queda en el log una vez, entero; y hubo conversación, así que las caídas seguidas vuelven a cero. */
    private fun cierraElTurno() {
        if (fraseUsuario.isNotEmpty()) log(TAG, "usuario dijo: $fraseUsuario")
        if (fraseU.isNotEmpty()) log(TAG, "Ü dijo: $fraseU")
        fraseUsuario.clear()
        fraseU.clear()
        reconexiones = 0
    }

    // ── Contestar ────────────────────────────────────────────────────────────

    /**
     * Una tanda, llamada por llamada y en orden. La retirada se salta sin contestar. Lo que revienta se contesta con su
     * error. Y la devolución al marcador va en finally: sin ella, tras la primera herramienta que falla, el turno no se
     * cerraría nunca.
     */
    private suspend fun atender(tanda: Tanda) {
        val c = tanda.conexion
        val hechas = mutableListOf<Resultado>()
        try {
            for (llamada in tanda.llamadas) {
                if (llamada.id.isNotEmpty() && llamada.id in retiradas) {
                    log(TAG, "«${llamada.nombre}» no se ejecuta ni se contesta: se retiró")
                    continue
                }
                // Una sesión nueva no sabe de esta llamada: ejecutarla sería actuar por una petición que ya nadie recuerda.
                if (c !== conexion || detenida) {
                    log(TAG, "«${llamada.nombre}» no se ejecuta: la pidió una conexión que ya se cerró")
                    continue
                }
                log(TAG, "ejecutando «${llamada.nombre}»…")
                val salida = try {
                    ejecutar(llamada)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(TAG, "«${llamada.nombre}» reventó: ${e.message}")
                    "la herramienta falló: ${e.message ?: e::class.simpleName}"
                }
                hechas += Resultado(llamada.id, salida)
            }
        } finally {
            c.turnos.devuelta(tanda.llamadas)
            for (llamada in tanda.llamadas) c.sinContestar.removeAll { it === llamada }
        }
        if (c !== conexion || detenida) return
        enviando("el resultado") {
            // Cada resultado pasa por el recorte de 32 768 B: uno más grande deja la llamada pendiente para siempre.
            for (m in protocolo.resultados(hechas)) mandarItem(c, m)
            if (hechas.isNotEmpty()) c.resultadosSinPedir = true
            if (c.sinContestar.isNotEmpty()) {
                log(TAG, "resultado devuelto; la respuesta se pide cuando se contesten las ${c.sinContestar.size} llamada(s) que faltan")
            }
            pedirRespuestaSiToca(c)
        }
    }

    /**
     * UN `response.create` Y SOLO SIN LLAMADAS PENDIENTES. Con una salida pendiente el servidor lo rechaza con
     * `function_call_outputs_required` (medido con dos llamadas a 52 ms). Los avisos en cola salen delante, en el mismo
     * pedido. Se llama con [envio] tomado.
     */
    private suspend fun pedirRespuestaSiToca(c: Conexion) {
        if (c !== conexion || !c.confirmada || c.sinContestar.isNotEmpty()) return
        if (avisos.isEmpty() && !c.resultadosSinPedir) return
        while (avisos.isNotEmpty()) mandarItem(c, protocolo.texto(AVISO_DEL_SISTEMA + avisos.removeFirst()).first())
        c.resultadosSinPedir = false
        canal.enviar(protocolo.pedirRespuesta())
    }

    /** Un item de la sesión del servidor. Pasado el tope rechaza lo que llegue: se avisa antes, una vez, sin cortar. */
    private suspend fun mandarItem(c: Conexion, json: String) {
        canal.enviar(json)
        c.items++
        if (c.items == AVISO_DE_ITEMS) {
            log(TAG, "la sesión lleva $AVISO_DE_ITEMS items de los $TOPE_DE_ITEMS que admite el servidor; pasado el tope rechaza lo que se le mande (response_input_buffer_full). No se corta nada")
        }
    }

    private suspend fun enviando(que: String, cuerpo: suspend () -> Unit) {
        try {
            envio.withLock { cuerpo() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, "no pude mandar $que: ${e.message}")
        }
    }

    // ── Terminar ─────────────────────────────────────────────────────────────

    /**
     * SE ACABÓ LA ESCUCHA: EL ÚNICO SITIO QUE DECIDE. En U lo decidían dos (el finally de la recepción y el catch de la
     * reconexión) y los dos reconectaban también lo que no se arregla reconectando. El orden importa: la causa por
     * código es la más precisa, «no abrió» va después, y reconectar solo si cayó solo. Devuelve si se reconecta.
     */
    private fun alTerminarLaEscucha(c: Conexion, via: String): Boolean {
        val cancelada = detenida || via == "cancelación"
        val sigue = viva && !cancelada
        val causa = c.causa
        val noAbrio = !c.confirmada && c.fallaAntesDeAbrir.isNotEmpty()
        val por = if (cancelada) "cancelación" else via

        if (sigue && causa == null && !noAbrio && c.cayoSolo && reconexiones < RECONEXIONES) {
            reconexiones++
            log(TAG, "fin de la escucha por $por: reconecto en ${ESPERA_DE_RECONEXION_MS * reconexiones} ms ($reconexiones/$RECONEXIONES), en una sesión nueva")
            return true
        }

        val (veredicto, frase) = when {
            !sigue -> "termina" to null
            causa != null -> "no se reintenta, $causa («${c.dichoDeLaCausa}»): con la misma cuenta, clave y modelo fallaría igual" to
                "No sigo con la voz en vivo: $causa («${c.dichoDeLaCausa}»)."
            noAbrio -> "no llegó a abrir: el servidor contestó «${c.fallaAntesDeAbrir}» en vez de confirmarla" to
                "No pude abrir la voz en vivo. El servidor dice: ${c.fallaAntesDeAbrir}"
            c.cayoSolo -> "se cortó ${RECONEXIONES + 1} veces seguidas: se deja" to NO_VUELVE
            else -> "termina" to null
        }
        log(TAG, "fin de la escucha por $por: $veredicto")
        terminar()
        frase?.let(dice)
        return false
    }

    /** Una vez: calla, cierra y deja los segundos de TODAS las conexiones en una línea, porque el panel cuenta fichas. */
    private fun terminar() {
        if (!viva) return
        viva = false
        val segundos = segundosAnteriores + (conexion?.segundos ?: 0.0)
        callar()
        canal.cerrar("fin")
        log(TAG, "la voz duró ${enSegundos(segundos)} s según el servidor, sumando $conexiones conexión(es)")
        log(TAG, "sesión cerrada")
    }

    private fun enSegundos(s: Double): String {
        val decimas = (s * 10).roundToLong()
        return "${decimas / 10}.${decimas % 10}"
    }
}
