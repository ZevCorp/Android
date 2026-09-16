package com.zevcorp.graph.voice.live

import android.content.Context
import com.zevcorp.graph.BuildConfig
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
 * LA CLAVE ES LA DEL BUILD INTERNO: la pref `openaiKey` o la horneada. Nunca la de la configuración remota, que baja de una
 * tabla pública.
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

        /**
         * LA PERSONA CORTA, adaptada al teléfono de `U-Windows-App/voz/Realtime/ProtocoloGptLive.cs:50-56`. Corta a propósito:
         * la voz no ve la pantalla ni tiene herramientas, y con las instrucciones de operar prometería lo que no puede hacer y
         * contestaría de memoria en vez de delegar. Sin la regla de no anunciar, en Windows dijo «Dame un momento para
         * revisarlo» antes de que el delegado hiciera nada.
         */
        const val INSTRUCCIONES_VOZ =
            "Eres Ü, el asistente que ayuda a usar este teléfono. " +
                "Hablas en español, con frases cortas y naturales. Tú no ves la pantalla ni la tocas: todo lo que sea mirar, " +
                "buscar, pulsar, escribir u operar el teléfono lo delegas siempre, y después cuentas lo que salió. " +
                "Nunca inventes lo que hay en pantalla ni lo que no ves." +
                " NO ANUNCIES LO QUE VAS A HACER: nada de «voy a…», «vamos a…», «déjame…», «dame un momento», «un momento», «ahora lo miro». Mientras se hace el trabajo, calla." +
                " CUANDO HABLES, HABLA EN PASADO Y DEL RESULTADO: «ya abrí la cámara», «no había ningún mensaje nuevo». Nunca en futuro."

        /** El delegado ya tiene ojos, pero no manos: que mire antes de hablar y que diga que todavía no puede actuar. */
        const val INSTRUCCIONES_DELEGADO =
            "Eres el delegado de Ü en un teléfono Android. Tienes tres herramientas y las tres SOLO MIRAN: " +
                "${CatalogoDeVoz.DONDE_ESTOY} dice en qué app y pantalla estás; ${CatalogoDeVoz.QUE_VEO} dice qué hay en la " +
                "pantalla, y con «${CatalogoDeVoz.FILTRO}» si algo concreto está o no; ${CatalogoDeVoz.QUE_PUEDO_HACER} dice " +
                "qué sabrá hacer Ü cuando pueda actuar. " +
                "MIRA ANTES DE HABLAR de la pantalla: nunca la describas de memoria ni inventes lo que no viste. " +
                "Todavía NO puedes tocar, escribir ni abrir nada: si te piden hacer algo, dilo en una frase corta y ofrece mirarlo."

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
                protocolo = ProtocoloGptLive(),
                credencial = { claveDelBuildInterno() },
                instruccionesVoz = INSTRUCCIONES_VOZ,
                instruccionesDelegado = INSTRUCCIONES_DELEGADO,
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
    private val ojos = HerramientasDeVoz(pantalla = ::estadoDeLaPantalla, acciones = ::catalogoDeAcciones, log = ::registrar)

    private suspend fun estadoDeLaPantalla(): TurnScreenState? =
        GraphApp.instance.ui?.state(withScreenshot = false)?.let {
            it.toTurnState(apps = null, surface = AndroidSurface.from(it.screen), withScreenshot = false)
        }

    private fun catalogoDeAcciones(): List<McpTool> =
        (GraphApp.instance.ui as? GraphAccessibilityService)?.let { Ejecucion.herramientas(it, emptyList()) } ?: emptyList()

    private fun claveDelBuildInterno(): String? =
        GraphApp.instance.prefs.getString("openaiKey", null)?.trim()?.ifBlank { null }
            ?: BuildConfig.DEFAULT_OPENAI_KEY.trim().ifBlank { null }

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
