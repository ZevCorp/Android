package com.zevcorp.graph.voice.live

import android.content.Context
import com.zevcorp.graph.Ejecucion
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.GraphAccessibilityService
import com.zevcorp.graph.platform.LogBus
import graph.core.domain.McpTool
import graph.core.graph.AndroidSurface
import graph.core.graph.TurnScreenState
import graph.core.graph.toTurnState
import graph.core.voz.CanalOkHttp
import graph.core.voz.CatalogoDeVoz
import graph.core.voz.ConversacionViva
import graph.core.voz.HerramientasDeVoz
import graph.core.voz.ModoDeCaptura
import graph.core.voz.PersonaDeLaVoz
import graph.core.voz.ProtocoloGptLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LA VOZ EN VIVO DE PRUEBA (docs/specs/002, fases B1b y 2B2a): `ConversacionViva` sobre `CanalOkHttp`, el micrófono y el
 * altavoz del teléfono, con la persona corta de la voz y las tres herramientas de SOLO LECTURA del delegado (dónde está,
 * qué ve y qué podrá hacer). Solo la arranca el panel de desarrollador hasta pasar el nivel 4 (promesa 246).
 *
 * LOS OJOS MIRAN EL MISMO ESTADO QUE EL TURNO DE GRAPH y el catálogo sale de `Ejecucion.herramientas(…)`, que lo arma
 * sobre la puerta única (spec 003, promesa 307): leer la pantalla pasa siempre, porque mirar no es actuar. Manos no se le
 * dan a nadie aquí: ejecutar es la fase siguiente.
 *
 * NUNCA HAY UNA KEY REAL EN EL APK: se conecta al proxy de Graph (`/api/android/live/session?device_id=…`), que
 * retransmite hacia OpenAI con SU clave — el celular nunca la ve. `ProtocoloGptLive` en sí mismo sigue documentando el
 * protocolo real de OpenAI (misma URL y `Authorization` que mide Windows); acá se lo overridea con la URL del proxy y
 * cabeceras vacías. Antes de este cableado esto usaba `BuildConfig.DEFAULT_OPENAI_KEY` horneada en el build como
 * placeholder temporal — ya no.
 *
 * EL PROXY VA A CORTAR EL SOCKET CADA VEZ QUE LA FUNCIÓN SERVERLESS DE VERCEL LLEGUE A SU TOPE DE DURACIÓN, a mitad de
 * una conversación real: no hace falta lógica nueva para eso. `ConversacionViva` YA reconecta sola frente a cualquier
 * cierre cuyo motivo no matchee una causa fatal conocida (`causaFatal`, `Fatales.kt`) — el número de cierre no se mira
 * (1013 del RFC es «reintentá»), y por eso un corte del proxy por su propio límite de tiempo entra ahí sin que haga
 * falta reconocer su motivo exacto de antemano (promesa 223: hasta 4 veces con espera creciente, el contador vuelve a
 * cero en cada turno cerrado; promesa 219: «Se cortó un instante. Sigo, pero olvidé lo último que hablábamos.»). LO QUE
 * SÍ QUEDA PENDIENTE cuando el proxy esté desplegado: si alguna vez rechaza un `device_id` no autorizado —por HTTP en
 * el apretón de manos o cerrando el socket con un motivo propio—, ese motivo hay que sumarlo a `CAUSAS` en
 * `Fatales.kt` para que no reconecte 4 veces en vano contra algo que nunca va a abrir.
 *
 * TIENE SU PROPIO ALCANCE, y parar vive en él. Desde el alcance cancelado de una Activity que se cierra, `detener()` lanza
 * antes de correr y el micrófono queda abierto. Micrófono y altavoz se sueltan cuando `conversar()` vuelve, por la vía que sea:
 * parar, un fatal, la falta de clave o una red que no vuelve.
 */
class VozEnVivoDev(private val contexto: Context) {

    enum class Fase { PARADA, ARRANCANDO, EN_CURSO, PARANDO }

    /** [linea] es lo que se pinta en el panel: una línea, sin log. */
    data class Estado(val fase: Fase, val linea: String)

    companion object {
        const val TAG = "voz-dev"

        /** Lo que espera parar a que la conversación cierre sola antes de cancelarla. */
        private const val TOPE_AL_PARAR_MS = 3_000L

        /** 5 s de micrófono en espera; si la conversación se atrasa, lo que se pierde es lo más viejo. */
        private const val TROZOS_EN_ESPERA = 50
    }

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Arrancar y parar se cruzan desde la pantalla y desde el fin de la conversación. */
    private val candado = Any()

    private val _estado = MutableStateFlow(Estado(Fase.PARADA, "Voz en vivo: parada"))
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    /** Bajo [candado]. */
    private var trabajo: Job? = null
    private var conversacion: ConversacionViva? = null

    /** La última frase dicha que no es la de conexión: si la voz se acaba sola, es por qué. */
    @Volatile
    private var ultimaFrase: String? = null

    fun start() {
        synchronized(candado) {
            if (trabajo != null) return
            fijar(Fase.ARRANCANDO, "Voz en vivo: abriendo el micrófono…")
            ultimaFrase = null
            val trozos = Channel<ByteArray>(TROZOS_EN_ESPERA, BufferOverflow.DROP_OLDEST)
            val microfono = MicrofonoPcm(contexto, alCapturar = { trozos.trySend(it) }, log = ::registrar)
            if (!microfono.arrancar()) {
                fijar(Fase.PARADA, "Voz en vivo: ${microfono.porQueNo}")
                return
            }
            val altavoz = AltavozPcm(::registrar)
            if (!altavoz.arrancar()) {
                microfono.parar()
                fijar(Fase.PARADA, "Voz en vivo: el altavoz no abrió")
                return
            }
            val conv = ConversacionViva(
                canal = CanalOkHttp(log = ::registrar),
                protocolo = ProtocoloGptLive(
                    urlDeConexion = urlDelProxy(),
                    cabecerasDeConexion = { emptyMap() },
                ),
                credencial = { deviceIdDelProxy() },
                instruccionesVoz = PersonaDeLaVoz.INSTRUCCIONES_VOZ,
                instruccionesDelegado = PersonaDeLaVoz.INSTRUCCIONES_DELEGADO,
                utensilios = CatalogoDeVoz.UTENSILIOS,
                ejecutar = ojos::ejecutar,
                actuaEnPantalla = CatalogoDeVoz::actuaEnPantalla,
                reproducir = altavoz::reproducir,
                callar = altavoz::callar,
                sonando = altavoz::sonando,
                dice = ::alDecir,
                log = ::registrar,
                reloj = RelojAndroid,
                compuertaActiva = ModoDeCaptura.activa(forzada = false, aec = microfono.hayAec),
                transcribe = { texto, esDeU -> enCurso((if (esDeU) "Ü: " else "Tú: ") + texto) },
            )
            fijar(Fase.ARRANCANDO, "Voz en vivo: abriendo la sesión…")
            registrar(TAG, "arranca la voz en vivo de prueba: ${CatalogoDeVoz.UTENSILIOS.size} herramientas de solo lectura, AEC del sistema ${if (microfono.hayAec) "encendido" else "no disponible"}")
            val job = alcance.launch {
                // DE A UN TROZO Y EN ORDEN: un launch por trozo los dejaba competir por entrar a la conversación.
                val oido = launch { for (t in trozos) conv.oirMicrofono(t) }
                try {
                    conv.conversar()
                } finally {
                    oido.cancel()
                    trozos.close()
                    microfono.parar()
                    altavoz.parar()
                    synchronized(candado) {
                        trabajo = null
                        conversacion = null
                    }
                    fijar(Fase.PARADA, ultimaFrase?.let { "Voz en vivo parada: $it" } ?: "Voz en vivo: parada")
                }
            }
            trabajo = job
            conversacion = conv
        }
    }

    /** Para desde cualquier hilo y desde cualquier alcance: el trabajo corre en el de la voz. */
    fun stop() {
        val (job, conv) = synchronized(candado) { (trabajo ?: return) to (conversacion ?: return) }
        fijar(Fase.PARANDO, "Voz en vivo: parando…")
        alcance.launch {
            conv.detener()
            if (withTimeoutOrNull(TOPE_AL_PARAR_MS) { job.join() } == null) {
                registrar(TAG, "la voz no terminó $TOPE_AL_PARAR_MS ms después de detenerla: se cancela")
                job.cancelAndJoin()
            }
        }
    }

    /**
     * LOS OJOS: solo lectura. La pantalla es la MISMA que arma el turno de Graph (`ScreenState` → `toTurnState`, sin
     * captura), y el catálogo, el que armaría una corrida sobre la puerta. Sin servicio de accesibilidad no hay estado
     * que leer, y la voz lo dice en vez de inventárselo.
     */
    private val ojos = HerramientasDeVoz(
        pantalla = ::estadoDeLaPantalla,
        acciones = ::catalogoDeAcciones,
        mirarEn = Dispatchers.IO,
        log = ::registrar,
    )

    private suspend fun estadoDeLaPantalla(): TurnScreenState? =
        GraphApp.instance.ui?.state(withScreenshot = false)?.let {
            it.toTurnState(apps = null, surface = AndroidSurface.from(it.screen), withScreenshot = false)
        }

    /**
     * `null` Y NO UNA LISTA VACÍA cuando no hay servicio: no saber el catálogo no es lo mismo que no saber hacer nada.
     * Y las aprendidas salen del único sitio que decide cuáles ve una corrida, el mismo que usa la anticipación.
     */
    private fun catalogoDeAcciones(): List<McpTool>? =
        (GraphApp.instance.ui as? GraphAccessibilityService)?.let {
            Ejecucion.herramientas(it, GraphApp.instance.aprendidasDisponibles())
        }

    /**
     * `wss://{graphBaseUrl}/api/android-live-session?device_id=<id>`: mismo `graphBaseUrl` que resuelve el cerebro
     * remoto (`GraphApp.resolvedGraphBaseUrl()`, pref `graphBaseUrl` o la horneada), pasado de http(s) a ws(s) porque
     * es un socket, no una request. El backend valida el `device_id` contra su whitelist y hace de relay hacia OpenAI
     * con su propia clave: el APK no lleva ninguna.
     *
     * EL PATH ES EL REAL DE LA FUNCIÓN, NO EL "BONITO" `/api/android/live/session`. Medido contra producción
     * (2026-09-18): los rewrites de `vercel.json` de Graph no se aplican a un WebSocket upgrade (solo a HTTP
     * normal) — conectar a la ruta con rewrite daba 404 sin llegar a la función; conectar directo a
     * `/api/android-live-session` sí llega y responde (403 con un `device_id` no autorizado, como se espera).
     */
    private fun urlDelProxy(): String {
        val base = GraphApp.instance.resolvedGraphBaseUrl()
            .replaceFirst(Regex("^https://"), "wss://")
            .replaceFirst(Regex("^http://"), "ws://")
        val id = java.net.URLEncoder.encode(GraphApp.instance.resolvedDeviceId(), "UTF-8")
        return "$base/api/android-live-session?device_id=$id"
    }

    /** El mismo `X-Miracle-Device-Id` que ya usa `GraphBrain`/`RealtimeVoiceClient`. Sin él no hay a quién autorizar. */
    private fun deviceIdDelProxy(): String? =
        GraphApp.instance.resolvedDeviceId().trim().ifBlank { null }

    private fun alDecir(frase: String) {
        registrar(TAG, "dice: $frase")
        if (frase != ConversacionViva.AL_ARRANCAR && frase != ConversacionViva.AL_VOLVER) ultimaFrase = frase
        enCurso(frase)
    }

    /** Lo que pasa con la voz viva. Si ya se está parando, la fase sigue diciendo que para. */
    private fun enCurso(linea: String) {
        fijar(if (_estado.value.fase == Fase.PARANDO) Fase.PARANDO else Fase.EN_CURSO, linea)
    }

    private fun fijar(fase: Fase, linea: String) {
        _estado.value = Estado(fase, linea.replace('\n', ' '))
    }

    private fun registrar(tag: String, mensaje: String) = LogBus.log(tag, mensaje)
}
