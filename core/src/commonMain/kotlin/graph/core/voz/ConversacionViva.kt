package graph.core.voz

import graph.core.json.PROFUNDIDAD_MAXIMA
import graph.core.json.demasiadoAnidado
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * LA CONVERSACIÓN VIVA (docs/specs/002, fase A2): abrir, oír, contestar herramientas, cerrar turnos y decidir en UN
 * solo sitio si se termina, se dice por qué o se reconecta. Pura: el socket es [CanalDeVoz], el tiempo es [Reloj] y
 * las manos son [ejecutar]. Comportamiento de `U-Windows-App/windows-client/src/Voice/ConversacionEnVivo.cs`,
 * reescrito, no traducido: cada decisión de aquí costó una sesión de voz real en Windows.
 *
 * DE A UNA COSA POR VEZ, AUNQUE LA LLAMEN DESDE VARIOS HILOS (promesa 236). En la fase B la llaman los callbacks de
 * OkHttp, el AudioRecord desde IO y la pantalla: [TurnosSinMarca] no tiene candado, y el estado de aquí tampoco. Por eso
 * cada método público salta a [hilo], un despachador de un solo hilo, y todo envío pasa por UN escritor: confinar no
 * basta, porque entre dos suspensiones de un envío cabe otro. Las herramientas corren en corrutinas hijas de la
 * CONEXIÓN, en ese mismo hilo: las que actúan en la pantalla de a una, las de control en el acto. Una acción que tarda
 * no frena la escucha, y la conexión que muere se las lleva. Una herramienta que BLOQUEA el hilo en vez de suspender lo
 * bloquea para todo: que salte a su propio despachador.
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
    /** La clave de hoy (OpenAI) o el token de mañana (Graph): se pide en CADA apertura, nunca se guarda aquí. */
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
    /** Acaba en la telemetría remota (`LogBus`): aquí nunca llega el contenido de una herramienta ni de un error. */
    private val log: (tag: String, mensaje: String) -> Unit,
    private val reloj: Reloj,
    private val compuertaActiva: Boolean = ModoDeCaptura.activa(forzada = false, aec = false),
    /** Lo dicho hasta ahora en el turno, acumulado, y de quién: quien pinte reemplaza la línea en vez de añadir. */
    private val transcribe: (texto: String, esDeU: Boolean) -> Unit = { _, _ -> },
    /** Empezó una petición del usuario, por voz o por texto. La cuenta de la petición (fase 3C) cuelga de aquí. */
    private val alAbrirPeticion: (por: String) -> Unit = {},
    /**
     * Si la herramienta actúa sobre la pantalla (lo dice el catálogo de la fase 2C). Esas van al obrero de la conexión,
     * de a una; las de control (`parar`, `como_va`, `self_*`) corren en el acto. Por defecto todas actúan: nada se cruza.
     */
    private val actuaEnPantalla: (nombre: String) -> Boolean = { true },
    /**
     * Con la compuerta activa, el detector de energía calla a Ü cuando le hablan encima. APAGADO por defecto, como en U
     * (`U_BARGEIN_ENERGIA`, `ConversacionEnVivo.cs:1183-1189`): allí la voz del usuario llegaba ~3× más débil que el eco y
     * el único que cruzaba el umbral era el propio eco. Se enciende donde se mida que la energía separa.
     */
    private val bargeInPorEnergia: Boolean = false,
    /**
     * Donde vive la conversación: UN hilo. Otro contexto solo para juzgarla; uno de varios hilos rompe el confinamiento.
     * El contrato común pasa el vacío porque `corre` ya es de un solo hilo.
     */
    private val hilo: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
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

        /** La salida de una llamada retirada: el servidor la espera igual, y sin ella rechaza el siguiente `response.create`. */
        const val RETIRADA = "retirada: no se ejecutó"

        /** Lo que cabe del mensaje de un error del canal en una línea del log. */
        const val LARGO_DEL_MOTIVO = 120
    }

    /** Lo que es de UNA conexión, y por eso muere con ella: la sesión del servidor que abre es otra. */
    private class Conexion(reloj: Reloj, val alConfirmar: String, val trabajo: CoroutineScope) {
        val turnos = TurnosSinMarca({ reloj.ahora() })

        /** Las tandas que actúan en la pantalla, para el obrero de esta conexión y de ninguna otra. */
        val tandas = Channel<Tanda>(Channel.UNLIMITED)

        /** Pedidas y sin contestar, por instancia: un call_id puede venir vacío. */
        val sinContestar = mutableListOf<Llamada>()

        /**
         * Lo que espera a que no quede ninguna llamada sin contestar —avisos y lo escrito, en el orden en que llegaron y ya
         * como item— para salir todo junto con UN `response.create`.
         */
        val cola = ArrayDeque<EnCola>()

        var confirmada = false
        var acabada = false

        /** Los cambios de modo que llevaba su `session.start`: si al confirmar hay más, el servidor abrió con uno viejo. */
        var cambiosAlAbrir = -1

        /** Lo primero que dijo el servidor antes de confirmar: si el socket muere sin confirmar, eso es por qué no abrió. */
        var fallaAntesDeAbrir = ""

        /** Por qué no se arregla reconectando. La primera gana: el error llega antes que el cierre y con su mensaje. */
        var causa: String? = null
        var dichoDeLaCausa = ""

        /** Al reabrir, `credencial()` no dio nada: no se llama a nadie. */
        var faltaCredencial = false

        /** `abrir` lanzó algo que no es la red (TLS, URL mala), ya saneado: falla igual si se reintenta. */
        var errorAlAbrir: String? = null

        var cayoSolo = false
        var resultadosSinPedir = false
        var segundos = 0.0
        var items = 0

        fun anotar(porque: String?, dicho: String) {
            if (porque == null || causa != null) return
            causa = porque
            dichoDeLaCausa = dicho
        }

        /**
         * SE ACABÓ: lo que corría por ella —el obrero y las de control— se cancela. Con un obrero para toda la conversación,
         * una herramienta colgada en una conexión muerta dejaba en cola para siempre las de la siguiente.
         */
        fun acabar() {
            acabada = true
            tandas.close()
            trabajo.cancel()
        }
    }

    private class Tanda(val conexion: Conexion, val llamadas: List<Llamada>)

    private class EnCola(val item: String, val esAviso: Boolean)

    private var delegado = instruccionesDelegado
    private var herramientas = utensilios

    /** El modo vigente no es el de siempre: una sesión que abre en él se lo tiene que repetir a la voz. */
    private var modoEspecial = false

    /** Cuántas veces se cambió de modo en esta conversación: lo que compara la confirmación con su `session.start`. */
    private var cambiosDeModo = 0

    /** Se lee desde cualquier hilo; se escribe solo en [hilo]. */
    @Volatile
    var viva = false
        private set

    /** Peticiones del usuario abiertas en esta conversación, por voz o por texto. Un aviso del sistema no es una. */
    @Volatile
    var peticiones = 0
        private set

    /** Items creados en la sesión del servidor en curso: llamadas del delegado, mensajes escritos, resultados y avisos. */
    val itemsEnSesion: Int get() = conexion?.items ?: 0

    private var detenida = false
    private var conexion: Conexion? = null
    private var conexiones = 0
    private var reconexiones = 0
    private var segundosAnteriores = 0.0

    /** Alguna sesión de esta conversación llegó a confirmarse: solo entonces hay algo que «olvidé» al volver. */
    private var algunaConfirmada = false

    /**
     * EL ÚNICO ESCRITOR DEL SOCKET (en U, `ConversacionEnVivo.cs:1341-1350`): el micrófono, la apertura, una tanda que
     * contesta y un aviso que sale no se cruzan. Sin él, el `response.create` podía salir dos veces, y un trozo de audio
     * se colaba a mitad de otro envío.
     */
    private val escritor = Mutex()

    private val retiradas = mutableSetOf<String>()
    private val fraseU = StringBuilder()
    private val fraseUsuario = StringBuilder()

    private val compuerta = CompuertaDeEco()
    private val detector = DetectorDeInterrupcion()
    private var tragadoAnunciado = 0L
    private var ultimoFalloDeEnvio = ""

    /** La espera entre intentos que está en curso: [detener] la corta en vez de esperar a que venza. */
    private var espera: Job? = null

    /** Todo lo que entra desde afuera pasa por aquí: al hilo de la conversación, y de a uno. */
    private suspend fun <T> confinado(bloque: suspend () -> T): T = withContext(hilo) { bloque() }

    /**
     * Abre y conversa hasta que se acaba: vuelve cuando la voz terminó, por la vía que sea. Toda escucha que termina
     * —cierre, excepción, cancelación, o una apertura que no llegó a escuchar— pasa por [alTerminarLaEscucha].
     */
    suspend fun conversar(): Unit = confinado {
        if (viva) return@confinado
        if (credencial().isNullOrBlank()) {
            log(TAG, "sin credencial: no se llama a nadie")
            dice(SIN_CREDENCIAL)
            return@confinado
        }
        viva = true
        detenida = false
        reconexiones = 0
        conexiones = 0
        segundosAnteriores = 0.0
        conexion = null
        algunaConfirmada = false
        retiradas.clear()

        coroutineScope {
            try {
                var reconectando = false
                while (true) {
                    val c = empiezaUnaConexion(if (algunaConfirmada) AL_VOLVER else AL_ARRANCAR, this)
                    var via = "apertura"
                    var reconecta = false
                    try {
                        if (reconectando) esperar(ESPERA_DE_RECONEXION_MS * reconexiones)
                        if (!detenida && conectar(c, reconectando)) via = escuchar(c)
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) {
                            via = "cancelación"
                            throw e
                        }
                        // Se escapó de un puerto con la voz viva: no es la nuestra, es un corte.
                        log(TAG, "se cortó la escucha: ${motivoSaneado(e)}")
                        c.cayoSolo = true
                        via = "corte"
                    } finally {
                        reconecta = alTerminarLaEscucha(c, via)
                    }
                    if (!reconecta) break
                    reconectando = true
                }
            } finally {
                conexion?.acabar()
                terminar()
            }
        }
    }

    /**
     * Lo pide el usuario: corta la espera en curso, y nunca reconecta ni anuncia un fatal. La decisión la toma igual
     * [alTerminarLaEscucha].
     */
    suspend fun detener(): Unit = confinado {
        if (!viva || detenida) return@confinado
        detenida = true
        espera?.cancel()
        log(TAG, "la voz se detiene a pedido")
        callar()
        canal.cerrar("fin")
    }

    /**
     * Un trozo de micrófono. Sin sesión confirmada no sale: el servidor aún no escucha. Con la compuerta activa, lo que
     * Ü suena se sustituye por silencio del mismo tamaño, y el detector —si está encendido— es el único oído que queda
     * para la interrupción.
     */
    suspend fun oirMicrofono(pcm: ByteArray): Unit = confinado {
        val c = conexion
        if (!viva || detenida || c == null || !c.confirmada || c.acabada) return@confinado
        var trozo = pcm
        if (compuertaActiva) {
            val ahora = reloj.ahora()
            val filtrado = compuerta.filtrar(pcm, sonando(), ahora)
            // EL DETECTOR VIVE EN LA ERA DE LA COMPUERTA (trozo tragado = eco), no en el estado crudo de la cola: con el
            // crudo, cada parpadeo entre ráfagas re-arrancaba la siembra y no disparó nunca (U, 2026-08-31).
            val trago = filtrado !== pcm
            trozo = filtrado
            if (bargeInPorEnergia && trago && detector.oye(rms(pcm), sonando = true, ahora = ahora)) {
                // La primera sílaba de quien interrumpe es lo que el servidor necesita oír: viaja ESTE trozo, intacto.
                callar()
                compuerta.abrir()
                trozo = pcm
                log(TAG, "te oí encima: corto mi voz y te escucho")
            } else if (!trago) {
                // LA COMPUERTA SE REABRIÓ: para el detector, Ü ya no suena. Sin esto seguía en la frase anterior, la siguiente
                // no sembraba su eco y un eco más fuerte pasaba por alguien encima.
                detector.oye(0.0, sonando = false, ahora = ahora)
            }
            if (trozo === pcm && compuerta.msTragados > tragadoAnunciado) {
                log(TAG, "compuerta de eco: tragó ${compuerta.msTragados - tragadoAnunciado} ms de micrófono mientras Ü sonaba; el micrófono vuelve a viajar")
                tragadoAnunciado = compuerta.msTragados
            }
        }
        try {
            escribiendo { canal.enviar(protocolo.audio(trozo)) }
        } catch (e: Exception) {
            if (e is CancellationException) relanzarSiEsNuestra(e)
            // Un trozo perdido no tira la sesión, pero perderlo en silencio sería un mensaje mudo: una línea por motivo.
            val motivo = motivoSaneado(e)
            if (motivo != ultimoFalloDeEnvio) {
                ultimoFalloDeEnvio = motivo
                log(TAG, "un trozo de micrófono no llegó al servidor: $motivo")
            }
        }
    }

    /**
     * Lo escrito es una petición y pide respuesta: sin el `response.create` el servidor lo acepta y calla (medido). Con
     * llamadas sin contestar, ESPERA en la cola de los avisos: su `response.create` es el que el servidor rechazaría.
     */
    suspend fun escribir(texto: String): Unit = confinado {
        if (texto.isBlank()) return@confinado
        val c = conexion
        if (!viva || detenida || c == null || !c.confirmada || c.acabada) {
            log(TAG, "no hay sesión confirmada: lo escrito no sale")
            return@confinado
        }
        abrePeticion("texto")
        transcribe(texto, false)
        c.cola.addLast(EnCola(protocolo.texto(texto).first(), esAviso = false))
        if (c.sinContestar.isNotEmpty()) {
            log(TAG, "lo escrito espera en cola: sale cuando se contesten las ${c.sinContestar.size} llamada(s) pendientes")
        }
        enviando("lo escrito") { pedirRespuestaSiToca(c) }
    }

    /**
     * Una nota para la voz que nadie dijo en voz alta («la tarea terminó: …»). NO abre petición, y ESPERA a que la sesión
     * se confirme y a que no quede ninguna llamada sin contestar. Verdadero si saldrá; con la voz muerta no va a ninguna
     * parte, y eso se devuelve y queda en el log.
     */
    suspend fun avisar(texto: String): Boolean = confinado {
        if (texto.isBlank()) return@confinado false
        val c = conexion
        if (!viva || detenida || c == null) {
            log(TAG, "aviso del sistema descartado: la voz no está viva")
            return@confinado false
        }
        c.cola.addLast(EnCola(protocolo.texto(AVISO_DEL_SISTEMA + texto.trim()).first(), esAviso = true))
        when {
            c.acabada -> log(TAG, "aviso del sistema en cola: sale cuando se confirme la conexión siguiente")
            !c.confirmada -> log(TAG, "aviso del sistema en cola: sale cuando se confirme la sesión")
            c.sinContestar.isNotEmpty() -> log(TAG, "aviso del sistema en cola: sale cuando se contesten las ${c.sinContestar.size} llamada(s) pendientes")
        }
        enviando("el aviso del sistema") { pedirRespuestaSiToca(c) }
        true
    }

    /**
     * Llamadas que ya no hay que hacer: no se ejecutan, pero SE CONTESTAN con [RETIRADA]. En U las retiraba el modelo al
     * hablarle encima, y contestarlas las hacía repetirse (ConversacionEnVivo.cs:1688). GPT-Live nunca retira: la retirada
     * viene de afuera (la tarea de la fase 2C), el servidor sigue esperando la salida y sin ella rechaza el siguiente
     * `response.create` con `function_call_outputs_required`.
     */
    suspend fun retirar(ids: List<String>): Unit = confinado {
        val validos = ids.filter { it.isNotEmpty() }
        if (validos.isEmpty()) return@confinado
        if (retiradas.size > 200) retiradas.clear()
        retiradas += validos
        log(TAG, "retiradas: ${validos.joinToString()}")
    }

    /**
     * Otro modo sin reabrir la sesión: otro `session.start` sería otra conversación. Se recuerda, y una reconexión
     * abre ya en este modo: la delegación en el `session.start` y, al confirmarse, el append a la voz. Con la sesión
     * abierta y SIN CONFIRMAR, su `session.start` ya salió con el modo de antes: al confirmarse sale este entero.
     */
    suspend fun cambiarModo(instrucciones: String, utensilios: List<Utensilio>, vuelve: Boolean): Unit = confinado {
        delegado = instrucciones
        herramientas = utensilios
        modoEspecial = !vuelve
        cambiosDeModo++
        val c = conexion
        if (viva && !detenida && c != null && !c.confirmada && !c.acabada) {
            log(TAG, "modo guardado: sale entero cuando se confirme la sesión")
            return@confinado
        }
        if (!viva || detenida || c == null || !c.confirmada || c.acabada) {
            log(TAG, "modo guardado para la próxima apertura: no hay sesión confirmada a la que cambiárselo")
            return@confinado
        }
        enviando("el cambio de modo") {
            for (m in protocolo.cambiarDeModo(instrucciones, utensilios, vuelve, instruccionesVoz)) canal.enviar(m)
            log(TAG, "modo cambiado sin reabrir la sesión: ${utensilios.size} herramienta(s), instrucciones de ${instrucciones.length} car." + if (vuelve) " · vuelve al de siempre" else "")
        }
    }

    // ── Abrir ────────────────────────────────────────────────────────────────

    /**
     * Cada conexión nace con su marcador de turnos, sin causa, sin falla y sin confirmar: lo dicho —o una llamada sin
     * devolver— en la anterior no cierra ni sujeta un turno de esta (W5 en U). Sus segundos se apartan para sumarlos, y
     * sus avisos en cola pasan a esta: siguen siendo verdad.
     */
    private fun empiezaUnaConexion(alConfirmar: String, alcance: CoroutineScope): Conexion {
        val anterior = conexion
        anterior?.let { segundosAnteriores += it.segundos }
        fraseU.clear()
        fraseUsuario.clear()
        conexiones++
        val trabajo = CoroutineScope(alcance.coroutineContext + Job(alcance.coroutineContext.job))
        val c = Conexion(reloj, alConfirmar, trabajo)
        // Lo escrito ya se descartó en [alTerminarLaEscucha]: lo que queda en la cola anterior son avisos.
        anterior?.cola?.let {
            c.cola.addAll(it)
            it.clear()
        }
        // UNA TANDA DETRÁS DE OTRA, en el orden en que llegaron: dos manos sobre la pantalla a la vez no se cruzan.
        trabajo.launch { for (tanda in c.tandas) atender(tanda) }
        conexion = c
        return c
    }

    /** Abre el socket y manda el único `session.start`. Verdadero si hay algo que escuchar. */
    private suspend fun conectar(c: Conexion, reconectando: Boolean): Boolean {
        var intento = 0
        while (true) {
            // LA CREDENCIAL SE PIDE EN CADA APERTURA: el token efímero de mañana caduca, y una clave rotada vale desde la
            // siguiente (en U se relee en cada reconexión).
            val clave = credencial()?.trim().orEmpty()
            if (clave.isEmpty()) {
                log(TAG, "sin credencial al abrir: no se llama a nadie")
                c.faltaCredencial = true
                return false
            }
            val apertura = try {
                canal.abrir(protocolo.url, protocolo.cabeceras(clave))
            } catch (e: CancellationException) {
                // El `withTimeout` del adaptador que vence no es la cancelación de la voz: es tiempo agotado, y eso es la red.
                relanzarSiEsNuestra(e)
                Apertura.SinRed("tiempo agotado (${tipoDe(e)})")
            } catch (e: Exception) {
                // SOLO LA RED SE REINTENTA, y la red la marca el canal con SinRed. Lo que lanza es otra cosa —TLS roto, URL
                // mala— y fallaría igual tres veces diciendo «revisa tu internet».
                c.errorAlAbrir = motivoSaneado(e)
                log(TAG, "no se pudo abrir la sesión y no es la red: ${c.errorAlAbrir}")
                return false
            }
            when (apertura) {
                Apertura.Ok -> {
                    if (detenida) {
                        canal.cerrar("fin")
                        return false
                    }
                    try {
                        escribiendo {
                            // Anotado con el mensaje armado, sin suspender entre medias: un cambio de modo después ya no viaja en él.
                            c.cambiosAlAbrir = cambiosDeModo
                            canal.enviar(protocolo.apertura(instruccionesVoz, delegado, herramientas))
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) relanzarSiEsNuestra(e)
                        log(TAG, "no pude mandar la apertura: ${motivoSaneado(e)}")
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
                    } catch (e: Exception) {
                        if (e is CancellationException) relanzarSiEsNuestra(e)
                        // Revienta un puerto que maneja lo dicho o lo que suena: de su mensaje, ni una palabra al log.
                        log(TAG, "no pude reaccionar a un mensaje del servidor: ${tipoDe(e)}")
                    }

                    is Recibido.Cierre -> {
                        cerro(c, r)
                        return "cierre"
                    }
                }
            }
        } catch (e: Exception) {
            // Un adaptador que cancela su Channel o vence un `withTimeout` en recibir() también es un corte.
            if (e is CancellationException) relanzarSiEsNuestra(e)
            log(TAG, "se cortó la escucha: ${motivoSaneado(e)}")
            c.cayoSolo = true
            return "corte"
        }
    }

    /**
     * UNA CANCELACIÓN AJENA NO PARA LA VOZ. Un `withTimeout` del adaptador o su `Channel` cancelado lanzan
     * `CancellationException` con la voz viva. Solo si ESTA corrutina está cancelada la excepción es nuestra, y se relanza;
     * si no, quien la atrapa la trata como el fallo que es. Vale para los puertos del canal; una herramienta puede cancelar
     * su propio contexto, y esa se decide en [atender].
     */
    private suspend fun relanzarSiEsNuestra(e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
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
        log(TAG, "el servidor cerró la conexión: ${r.codigo} «${saneado(r.motivo)}»")
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
            // Las que actúan en la pantalla van al obrero, de a una; las de control corren ya: «para» no puede esperar a
            // que acabe lo que se está parando. Al log, solo los nombres: los argumentos traen lo que se va a escribir.
            is Hecho.Pide -> {
                log(TAG, "llamada recibida: " + hecho.llamadas.joinToString { it.nombre })
                for (l in hecho.llamadas) {
                    if (c.sinContestar.none { it === l }) c.sinContestar += l
                    // El function_call del delegado también es un item de la sesión del servidor, aunque no lo mande la voz.
                    contarItem(c)
                }
                val (enPantalla, deControl) = hecho.llamadas.partition { actuaEnPantalla(it.nombre) }
                if (enPantalla.isNotEmpty()) c.tandas.trySend(Tanda(c, enPantalla))
                for (l in deControl) c.trabajo.launch { atender(Tanda(c, listOf(l))) }
            }

            is Hecho.Retira -> retirar(hecho.ids)

            // ACUMULADO, no incremento: 12.0 y luego 25.0 en la misma sesión son 25 s, no 37.
            is Hecho.Duracion -> c.segundos = max(c.segundos, hecho.segundos)

            // La primera puerta de la causa: el code, nunca la prosa, que está en inglés y cambia de redacción.
            is Hecho.Falla -> {
                log(TAG, "el servidor dice: ${saneado(hecho.que)}")
                if (!c.confirmada && c.fallaAntesDeAbrir.isEmpty()) c.fallaAntesDeAbrir = hecho.que
                c.anotar(causaFatal(hecho.codigo), hecho.que)
            }

            // LA SESIÓN ABRIÓ DE VERDAD: lo único que afirma «sesión abierta», y una vez por conexión.
            // EN UN MODO ESPECIAL LA VOZ LO OYE OTRA VEZ: el `session.start` abre con su persona, y sin el append el
            // delegado reabría en un modo y la voz en el de siempre. Antes de confirmar el servidor aún no escucha.
            // Y SI EL MODO CAMBIÓ DESPUÉS DEL START, el servidor abrió con el de antes: solo el append dejaba a la voz en un
            // modo y al delegado, con sus instrucciones y herramientas, en el otro. Sale el cambio entero.
            Hecho.Abierta -> if (!c.confirmada) {
                c.confirmada = true
                algunaConfirmada = true
                log(TAG, "sesión abierta con «${protocolo.modelo}»: el servidor la confirmó")
                dice(c.alConfirmar)
                enviando("el modo vigente") {
                    if (c.cambiosAlAbrir != cambiosDeModo) {
                        for (m in protocolo.cambiarDeModo(delegado, herramientas, vuelve = !modoEspecial, instruccionesVoz)) canal.enviar(m)
                        log(TAG, "el modo cambió después de abrir: se manda entero al confirmar (${herramientas.size} herramienta(s), instrucciones de ${delegado.length} car.)")
                    } else if (modoEspecial) {
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
     * Una tanda, llamada por llamada y en orden. La retirada no se ejecuta y se contesta como tal. Lo que revienta o se
     * para solo se contesta con su motivo, y la tanda sigue. Y la devolución al marcador va en finally: sin ella, la
     * cancelación de la voz a mitad de una tanda dejaba el turno abierto.
     */
    private suspend fun atender(tanda: Tanda) {
        val c = tanda.conexion
        val hechas = mutableListOf<Resultado>()
        var soltada = false

        /** Devuelta al marcador y fuera de las pendientes, una sola vez. */
        fun soltar() {
            if (soltada) return
            soltada = true
            c.turnos.devuelta(tanda.llamadas)
            for (llamada in tanda.llamadas) c.sinContestar.removeAll { it === llamada }
        }

        try {
            for (llamada in tanda.llamadas) {
                if (llamada.id.isNotEmpty() && llamada.id in retiradas) {
                    log(TAG, "«${llamada.nombre}» no se ejecuta: se retiró; se contesta como no ejecutada")
                    hechas += Resultado(llamada.id, RETIRADA)
                    continue
                }
                // Una sesión nueva no sabe de esta llamada: ejecutarla sería actuar por una petición que ya nadie recuerda.
                if (c !== conexion || c.acabada || detenida) {
                    log(TAG, "«${llamada.nombre}» no se ejecuta: la pidió una conexión que ya se cerró")
                    continue
                }
                log(TAG, "ejecutando «${llamada.nombre}»…")
                val salida = try {
                    // CADA LLAMADA EN SU PROPIA CORRUTINA. La que cancela su contexto cancelaba el del obrero o el de la de
                    // control que la corría: el obrero moría, y la de control se iba sin salida y dejaba en el servidor un
                    // function_call huérfano que rechaza cada response.create. Arranca en el acto, como una llamada directa.
                    supervisorScope { async(start = CoroutineStart.UNDISPATCHED) { ejecutar(llamada) }.await() }
                } catch (e: Throwable) {
                    // NO SE ESCAPA NADA salvo la cancelación de la voz. Una cancelación ajena (`withTimeout`, el freno de
                    // 3A) mataba al obrero en silencio y la cola ya no se atendía; un Error (`TODO()`) tumbaba la voz.
                    // Al log, SOLO EL TIPO: el mensaje de una herramienta puede traer lo que se escribió, y el log acaba en
                    // la telemetría remota. Al modelo, el motivo: es quien tiene que saber por qué.
                    if (e is CancellationException) {
                        // ES DE LA VOZ SI LA VOZ TERMINÓ: la conexión se acabó, se detuvo, o se canceló ESTA corrutina. El
                        // contexto de aquí sí es de la voz (la herramienta corre en el suyo), y hace falta: cancelar
                        // conversar() le llega al obrero antes de que acabar() marque la conexión.
                        if (c.acabada || detenida || !currentCoroutineContext().isActive) throw e
                        log(TAG, "«${llamada.nombre}» se paró (${tipoDe(e)}); el motivo va solo al modelo")
                        "la herramienta se paró: ${e.message ?: tipoDe(e)}"
                    } else {
                        log(TAG, "«${llamada.nombre}» reventó (${tipoDe(e)}); el motivo va solo al modelo")
                        "la herramienta falló: ${e.message ?: tipoDe(e)}"
                    }
                }
                hechas += Resultado(llamada.id, salida)
            }
            if (c !== conexion || c.acabada || detenida) return
            enviando("el resultado") {
                // Cada resultado pasa por el recorte de 32 768 B: uno más grande deja la llamada pendiente para siempre.
                for (m in protocolo.resultados(hechas)) mandarItem(c, m)
                if (hechas.isNotEmpty()) c.resultadosSinPedir = true
                // SE SUELTA CON LA SALIDA YA MANDADA Y EL ESCRITOR TOMADO. Soltarla antes y esperar al escritor dejaba un
                // hueco: un aviso pedía respuesta con esta llamada fuera de las pendientes y su salida sin mandar (236).
                soltar()
                if (c.sinContestar.isNotEmpty()) {
                    log(TAG, "resultado devuelto; la respuesta se pide cuando se contesten las ${c.sinContestar.size} llamada(s) que faltan")
                }
                pedirRespuestaSiToca(c)
            }
        } finally {
            soltar()
        }
    }

    /**
     * UN `response.create` Y SOLO SIN LLAMADAS PENDIENTES. Con una salida pendiente el servidor lo rechaza con
     * `function_call_outputs_required` (medido con dos llamadas a 52 ms). La cola —avisos y lo escrito— sale delante, en
     * el mismo pedido. Se llama con el [escritor] tomado.
     */
    private suspend fun pedirRespuestaSiToca(c: Conexion) {
        if (c !== conexion || c.acabada || !c.confirmada || c.sinContestar.isNotEmpty()) return
        if (c.cola.isEmpty() && !c.resultadosSinPedir) return
        while (c.cola.isNotEmpty()) mandarItem(c, c.cola.removeFirst().item)
        // MANDAR SUSPENDE, y mientras tanto la escucha pudo recibir otra llamada: se mira otra vez justo antes del pedido. Lo
        // ya mandado queda sin pedir, y lo pide la tanda que conteste la nueva.
        if (c !== conexion || c.acabada || c.sinContestar.isNotEmpty()) {
            c.resultadosSinPedir = true
            return
        }
        c.resultadosSinPedir = false
        canal.enviar(protocolo.pedirRespuesta())
    }

    /** Un item de la sesión del servidor. Se llama con el [escritor] tomado. */
    private suspend fun mandarItem(c: Conexion, json: String) {
        canal.enviar(json)
        contarItem(c)
    }

    /** Pasado el tope el servidor rechaza lo que llegue: se avisa antes, una vez, sin cortar. */
    private fun contarItem(c: Conexion) {
        c.items++
        if (c.items == AVISO_DE_ITEMS) {
            log(TAG, "la sesión lleva $AVISO_DE_ITEMS items de los $TOPE_DE_ITEMS que admite el servidor; pasado el tope rechaza lo que se le mande (response_input_buffer_full). No se corta nada")
        }
    }

    /** TODO envío al socket pasa por aquí, de a uno. */
    private suspend fun <T> escribiendo(cuerpo: suspend () -> T): T = escritor.withLock { cuerpo() }

    private suspend fun enviando(que: String, cuerpo: suspend () -> Unit) {
        try {
            escribiendo(cuerpo)
        } catch (e: Exception) {
            if (e is CancellationException) relanzarSiEsNuestra(e)
            log(TAG, "no pude mandar $que: ${motivoSaneado(e)}")
        }
    }

    // ── Terminar ─────────────────────────────────────────────────────────────

    /**
     * SE ACABÓ LA ESCUCHA: EL ÚNICO SITIO QUE DECIDE. En U lo decidían dos (el finally de la recepción y el catch de la
     * reconexión) y los dos reconectaban también lo que no se arregla reconectando. El orden importa: la causa por
     * código es la más precisa, «no abrió» va después, y reconectar solo si cayó solo. Devuelve si se reconecta.
     */
    private fun alTerminarLaEscucha(c: Conexion, via: String): Boolean {
        // La sesión de esta conexión ya no existe: lo que corría por ella no tiene a quién contestar.
        if (c.sinContestar.isNotEmpty()) log(TAG, "se cancelan ${c.sinContestar.size} llamada(s) sin contestar de la conexión que se acabó")
        c.acabar()
        // Lo escrito esperaba a una sesión que ya no existe, y la nueva no lo recuerda. Los avisos se quedan: pasan a la siguiente.
        val escritos = c.cola.count { !it.esAviso }
        if (escritos > 0) {
            c.cola.removeAll { !it.esAviso }
            log(TAG, "lo escrito en cola se descarta: $escritos mensaje(s) de la conexión que se acabó")
        }
        val cancelada = detenida || via == "cancelación"
        val sigue = viva && !cancelada
        val causa = c.causa
        val noAbrio = !c.confirmada && c.fallaAntesDeAbrir.isNotEmpty()
        val por = if (cancelada) "cancelación" else via

        if (sigue && causa == null && !c.faltaCredencial && c.errorAlAbrir == null && !noAbrio && c.cayoSolo && reconexiones < RECONEXIONES) {
            reconexiones++
            log(TAG, "fin de la escucha por $por: reconecto en ${ESPERA_DE_RECONEXION_MS * reconexiones} ms ($reconexiones/$RECONEXIONES), en una sesión nueva")
            return true
        }

        val (veredicto, frase) = when {
            !sigue -> "termina" to null
            // Al log, lo dicho por el servidor saneado; al usuario, tal cual: es quien tiene que leer por qué.
            causa != null -> "no se reintenta, $causa («${saneado(c.dichoDeLaCausa)}»): con la misma cuenta, clave y modelo fallaría igual" to
                "No sigo con la voz en vivo: $causa («${c.dichoDeLaCausa}»)."
            c.faltaCredencial -> "no hay credencial para abrir otra vez: no se llama a nadie" to SIN_CREDENCIAL
            c.errorAlAbrir != null -> "abrir falló sin ser la red (${c.errorAlAbrir}): no se reintenta" to
                "No pude abrir la voz en vivo: ${c.errorAlAbrir}."
            noAbrio -> "no llegó a abrir: el servidor contestó «${saneado(c.fallaAntesDeAbrir)}» en vez de confirmarla" to
                "No pude abrir la voz en vivo. El servidor dice: ${c.fallaAntesDeAbrir}"
            c.cayoSolo -> "se cortó ${RECONEXIONES + 1} veces seguidas: se deja" to NO_VUELVE
            else -> "termina" to null
        }
        log(TAG, "fin de la escucha por $por: $veredicto")
        if (c.cola.isNotEmpty()) log(TAG, "se descartan ${c.cola.size} aviso(s) del sistema en cola: la voz terminó")
        c.cola.clear()
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

/** El nombre del tipo de un error: de una herramienta, lo único que va al log. */
private fun tipoDe(e: Throwable): String = e::class.simpleName ?: "Throwable"

private val ENTRE_COMILLAS = Regex("«[^»]*»|“[^”]*”|\"[^\"]*\"|(?<![\\p{L}\\p{N}])'[^']*'")
private val CON_FORMA_DE_CLAVE = Regex("(?i)\\bbearer\\s+\\S+|\\b(?:sk|rk|pk|ek)-\\S+|[\\p{L}\\p{N}_\\-]{20,}")

/**
 * LO QUE DE UN TEXTO AJENO PUEDE IR AL LOG, que `LogBus` reenvía a la telemetría remota: la primera línea sin lo que va
 * entre comillas (lo citado es contenido), sin nada con forma de clave o de token, sin caracteres de control y recortada.
 * Para el mensaje de un error del canal y para lo que dice el servidor, que repite los valores que se le mandaron
 * («Invalid value: 'voz_que_no_existe'») y la cola de la clave («sk-proj-****0000»).
 */
private fun saneado(texto: String?): String {
    val linea = texto?.lineSequence()?.firstOrNull().orEmpty()
        .replace(ENTRE_COMILLAS, "«…»")
        .replace(CON_FORMA_DE_CLAVE, "…")
        .filterNot { it.isISOControl() }
        .trim()
    return if (linea.length > ConversacionViva.LARGO_DEL_MOTIVO) linea.take(ConversacionViva.LARGO_DEL_MOTIVO).trimEnd() + "…" else linea
}

/** Lo que de un error del canal puede ir al log: el tipo y su mensaje [saneado]. De una herramienta, ni eso: solo [tipoDe]. */
private fun motivoSaneado(e: Throwable): String {
    val linea = saneado(e.message)
    return if (linea.isEmpty()) tipoDe(e) else "${tipoDe(e)}: $linea"
}
