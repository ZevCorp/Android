package graph.core.precision

import graph.core.domain.GraphLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * EL FRENO DE UNA TAREA. Mientras el asistente toca el teléfono, la persona puede pararlo (un botón,
 * la notificación, una orden hablada) y recuperar el control.
 *
 * ES UNA INSTANCIA, NO UN `object`. El freno de U es estático y su contrato lo juzgaba con uno falso:
 * en producción `Freno.Empezar` solo se llamaba al recolocar el escritorio, así que Escape no frenaba
 * nada más y la promesa daba verde (hallazgo grave de U, spec 003). Aquí el freno se inyecta en quien
 * lo usa —la [Puerta] y el motor— y el contrato juzga ese mismo objeto.
 *
 * SE ARMA AL EMPEZAR Y SE SUELTA AL TERMINAR. [empezar] desarma cualquier alto viejo: pararse hace diez
 * minutos, para otra cosa, no puede abortar lo siguiente. [pide] sin tarea abierta no arma nada. Y
 * [termine] suelta el alto SIEMPRE: con el freno en la puerta, un alto que se queda pedido deja el
 * teléfono muerto entre una tarea y la siguiente (U lo pagó: su promesa 26). [enTarea] es la forma de
 * no olvidarse: termina en `finally` aunque la tarea reviente.
 *
 * MIRAR Y CAMBIAR ES UN SOLO PASO. La píldora pide el alto desde el hilo de la UI mientras la corrida termina
 * su tarea y empieza la siguiente en el suyo. [empezar], [pide] y [termine] miran y cambian el estado dentro del
 * mismo [Candado], con su log y su aviso dentro: el alto de una tarea se avisa antes que su «Listo», y ningún alto
 * cae entre un [termine] y el [empezar] siguiente. Sin él, `pide` leía «abierta», `termine` corría entero y `pide`
 * armaba después: el freno quedaba armado sin tarea y la siguiente podía nacer parada (492.958 altos sin devolver
 * el control en 3 millones de vueltas; promesa 319). Leer [abierta] o [pedido] no necesita el candado.
 */
class Freno(
    private val log: GraphLog = GraphLog { _, _ -> },
    /** Lo que se le dice a la persona: el aviso del alto (una vez por tarea) y, al soltar tras un alto, [DEVUELVO_EL_CONTROL]. */
    private val avisa: (String) -> Unit = {},
    /** Reloj de [duerme]; el contrato lo cambia por uno de prueba. */
    private val reloj: TimeSource = TimeSource.Monotonic,
    /** Cómo se duerme un trozo de [duerme]; el contrato avanza su reloj en vez de dormir. */
    private val espera: suspend (Long) -> Unit = { delay(it) },
) {
    private val candado = Candado()

    @Volatile private var _tarea = ""
    @Volatile private var _abierta = false
    @Volatile private var _pedido = false

    /** Qué se está haciendo, para decirlo al pararse. Vacío sin tarea abierta. */
    val tarea: String get() = _tarea

    /** ¿Hay una tarea en marcha? Sin ella la [Puerta] no deja pasar ninguna entrada. */
    val abierta: Boolean get() = _abierta

    /** ¿La persona pidió el alto en esta tarea? */
    val pedido: Boolean get() = _pedido

    /** Arranca una tarea interrumpible. Desarma el freno: lo de antes ya no cuenta. */
    fun empezar(tarea: String) {
        candado.con { abre(tarea) }
    }

    /**
     * Arranca [tarea] solo si no hay otra abierta, mirando y abriendo en el mismo paso: dos vías que llegan a la
     * vez no abren las dos (promesa 318). Devuelve si la abrió.
     */
    fun empiezaSiNoHayOtra(tarea: String): Boolean = candado.con {
        if (_abierta) false else { abre(tarea); true }
    }

    private fun abre(tarea: String) {
        _pedido = false
        _tarea = tarea
        _abierta = true
    }

    /** Pide el alto. Sin tarea abierta no arma nada; con el alto ya pedido no vuelve a avisar. */
    fun pide(porque: String) {
        candado.con {
            if (_abierta && !_pedido) {
                _pedido = true
                log.log("freno", "alto pedido ($porque); paro «$_tarea»")
                dile(ALTO)
            }
        }
    }

    /** Se acabó la tarea, bien, parada o reventada. Suelta el freno siempre. */
    fun termine() {
        candado.con {
            val soltaUnAlto = _abierta && _pedido
            val tarea = _tarea
            _abierta = false
            _pedido = false
            _tarea = ""
            if (soltaUnAlto) {
                log.log("freno", "suelto «$tarea»: el control vuelve a ti")
                dile(DEVUELVO_EL_CONTROL)
            }
        }
    }

    /**
     * Corre [bloque] como la tarea [nombre]: la abre y la termina en `finally`, aunque reviente o la paren.
     *
     * SOLO CIERRA QUIEN ABRIÓ. Si ya hay una tarea abierta —el paso consciente de un workflow corre dentro de
     * la corrida—, el bloque corre dentro de ella sin empezar ni terminar: un [empezar] de dentro desarmaría el
     * alto de fuera, y un [termine] de dentro dejaría la corrida sin tarea, con la puerta cerrada para el resto
     * de sus pasos y el alto sin armar (promesa 306). Hay una sola tarea por freno: dos corridas de fuera
     * simultáneas comparten la de la primera, y cuando la primera acaba la otra ya no toca (y lo dice el log).
     */
    suspend fun <T> enTarea(nombre: String, bloque: suspend () -> T): T {
        if (_abierta) return bloque()
        empezar(nombre)
        try {
            return bloque()
        } finally {
            termine()
        }
    }

    /**
     * Espera [ms], pero atenta: devuelve true si hay que parar. Duerme a trozos de [TROZO_MS] mirando el
     * freno entre uno y otro; dormir de un tirón es tiempo sin poder pararse, y son justo los ratos en
     * que alguien decide que ya vio bastante. El plazo se mide con el reloj, no sumando trozos: un
     * `delay` que se pasa unos milisegundos no alarga la espera.
     */
    suspend fun duerme(ms: Long): Boolean {
        val inicio = reloj.markNow()
        while (!_pedido) {
            val falta = ms - inicio.elapsedNow().inWholeMilliseconds
            if (falta <= 0) return false
            espera(minOf(TROZO_MS, falta))
        }
        return true
    }

    /**
     * Un aviso que falla (la voz, la burbuja) no puede impedir soltar ni tapar la causa de la tarea: tampoco si falla
     * con un `Error` (un TTS que no llegó a inicializarse lanza `ExceptionInInitializerError`). Una cancelación sí sale.
     */
    private fun dile(texto: String) {
        try {
            avisa(texto)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.log("freno", "no pude avisar «$texto»: ${t.message ?: t::class.simpleName}")
        }
    }

    companion object {
        const val TROZO_MS = 40L

        /** Lo que se dice al soltar tras un alto: pararse en silencio se vive igual que colgarse. */
        const val DEVUELVO_EL_CONTROL = "Listo, tienes el control de vuelta."

        /** El aviso del alto, una vez por tarea. */
        const val ALTO = "Vale, paro."
    }
}
