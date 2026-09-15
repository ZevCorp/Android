package graph.core.precision

import graph.core.domain.Gestures
import graph.core.domain.GraphLog
import graph.core.domain.Phone
import graph.core.domain.ScreenState
import graph.core.domain.SystemApi
import graph.core.domain.UiPlayer
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

/**
 * LA PUERTA ÚNICA AL TELÉFONO. Todo lo que toca la pantalla o lanza algo en el sistema entra por aquí:
 * las acciones de Graph en el motor, las herramientas MCP (gestos, sistema, aprendidas) y, desde 3B,
 * la voz y el reproductor de workflows. Se construye una vez, envolviendo las superficies reales.
 *
 * EL FRENO VIVE EN LA PUERTA, NO EN LOS BUCLES. Un freno que cada bucle debe acordarse de mirar protege
 * los bucles de hoy y ninguno de los de mañana (U, 2026-08-22). Aquí cada entrada:
 *  1. sin tarea abierta NO pasa: lo dice en el log y devuelve `false`. Olvidarse de abrir la tarea no
 *     deja un freno que no frena (el fallo de U): deja un teléfono que no se toca y lo dice;
 *  2. con el alto pedido lanza [Paraste] sin tocar nada: quien la reciba sabe que lo paraste tú, no
 *     que algo falló;
 *  3. si no, delega en la superficie real.
 * Leer la pantalla (`state`) pasa siempre: mirar no es actuar.
 *
 * DOS INTENTOS Y NO TRES (fase 3C, promesas 310-315). Con [tope], tocar, escribir y tocar por etiqueta
 * consultan primero: si la tercera va a un destino que ya falló dos veces, no se toca el teléfono, se
 * devuelve `false` y el log dice por qué. Si pasa: huella antes, se actúa, [asentar], huella después, y el
 * resultado va al tope y a la [cuenta]. Las demás entradas solo cuentan como llamadas. Sin tope ni cuenta,
 * la puerta es la de 3A.
 *
 * Son cuatro vistas y no una clase que implemente las cuatro interfaces: `Phone.openApp` y
 * `SystemApi.openApp` tienen la misma firma y delegan en objetos distintos.
 *
 * EL LOG NOMBRA LA ACCIÓN, NUNCA LO QUE LLEVA (promesa 317). El log sale del teléfono por la telemetría: dice el
 * tipo de entrada, su celda o el largo de su texto, y el destino del tope como [TopeDeIntentos.enLog]: su estructura sellada
 * con la llave del proceso, o su tipo y su largo; nunca lo que se escribe, a quién se llama, qué se busca o se copia, qué
 * etiqueta se toca ni lo que la pantalla muestra, tampoco sellado.
 */
class Puerta(
    private val freno: Freno,
    private val phone: Phone,
    private val gestures: Gestures,
    private val system: SystemApi,
    private val player: UiPlayer? = null,
    private val log: GraphLog = GraphLog { _, _ -> },
    /** El nodo vivo bajo un punto, el que se tocaría (3E). Sin él, el tope cuenta por celda de 48 dp. */
    private val nodoEn: ((Int, Int) -> NodoVivo?)? = null,
    /** La huella de la pantalla CON los textos visibles de la ventana activa (3C). Sin ella no se juzga «cambió». */
    private val huella: (suspend () -> String)? = null,
    /** Dos intentos y no tres por petición. Sin él, tocar y escribir pasan como en 3A. */
    private val tope: TopeDeIntentos? = null,
    /** La medida de la petición: cada entrada que pasa el freno es una llamada. */
    private val cuenta: CuentaDePeticion? = null,
    /** Espera a que la pantalla se asiente antes de la huella de después. Hoy no espera: lo cablea 3E, sin sleeps fijos. */
    private val asentar: suspend () -> Unit = {},
) {
    @Volatile private var dijeSinHuella = false

    private suspend fun pasa(accion: String, vigilada: Vigilada? = null, entra: suspend () -> Boolean): Boolean {
        if (!freno.abierta) {
            log.log("puerta", "sin tarea abierta, no paso «$accion»")
            return false
        }
        if (freno.pedido) {
            log.log("puerta", "$PARASTE_TU, no paso «$accion»")
            throw Paraste(PARASTE_TU)
        }
        if (tope == null && cuenta == null) return entra()
        val herramienta = accion.substringBefore(' ').substringBefore('(')
        if (vigilada == null) {
            cuenta?.llamada(herramienta)
            return entra().also { cuenta?.resultado(herramienta, actuo = it) }
        }
        return vigila(accion, herramienta, vigilada, entra)
    }

    /** Tocar o escribir: lo que el tope vigila. [destino] se calcula antes de tocar. */
    private class Vigilada(val escribe: Boolean, val destino: (TopeDeIntentos) -> TopeDeIntentos.Destino)

    private suspend fun vigila(accion: String, herramienta: String, v: Vigilada, entra: suspend () -> Boolean): Boolean {
        val t = tope
        val destino = if (t != null) v.destino(t) else null
        // Cómo va el destino al log se decide con el destino entero, antes de actuar: la clave sola no sabe si es un nombre.
        val enLog = if (t != null && destino != null) t.enLog(destino) else null
        cuenta?.llamada(herramienta, if (t != null && destino != null) t.clave(destino) else "", enLog)
        if (t != null && destino != null) {
            t.rechazo(destino)?.let { porque ->
                // El rechazo entero nombra el destino y lo que salió: es para el modelo, no para el log.
                log.log("tope", "no paso «$accion»: tercera entrada a «$enLog», que ya falló dos veces en esta petición")
                cuenta?.rechazada(herramienta)
                return false
            }
        }
        // Escribir se juzga por lo que devuelve la escritura: no hace falta leer la pantalla dos veces.
        val antes = if (v.escribe) null else lee()
        if (freno.pedido) {                                       // leer la huella lleva su rato
            log.log("puerta", "$PARASTE_TU, no paso «$accion»")
            throw Paraste(PARASTE_TU)
        }
        val dio = try {
            entra()
        } catch (e: CancellationException) {
            throw e                                               // parar no es fallar
        } catch (e: Exception) {
            if (t != null && destino != null) t.despues(destino, TopeDeIntentos.Salida.Revento("reventó: ${e.message ?: e::class.simpleName}"))
            cuenta?.resultado(herramienta, actuo = false)
            throw e
        }
        val cambio = if (v.escribe || !dio || antes == null) null else {
            asentar()
            lee()?.let { it != antes }
        }
        val escribio = v.escribe && dio
        if (t != null && destino != null) t.despues(destino, TopeDeIntentos.Salida.Intento(dio, escribio, cambio, queSalio(dio, escribio, cambio)))
        cuenta?.resultado(herramienta, actuo = TopeDeIntentos.logro(dio, escribio, cambio) == true)
        return dio
    }

    /** La huella, o `null` si no hay con qué juzgar. Sin huella lo dice una vez: el tope no frena por adivinar. */
    private suspend fun lee(): String? {
        val h = huella
        if (h == null) {
            if (!dijeSinHuella) {
                dijeSinHuella = true
                log.log("tope", "sin huella no juzgo si la pantalla cambió: un toque que se da no cuenta como fallo")
            }
            return null
        }
        return try {
            h()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log("tope", "no pude tomar la huella (${e::class.simpleName})")
            null
        }
    }

    private fun nodoVivoEn(x: Int, y: Int): NodoVivo? = try {
        nodoEn?.invoke(x, y)
    } catch (e: Exception) {
        log.log("tope", "no pude leer el nodo en ($x,$y) (${e::class.simpleName}); cuento por celda")
        null
    }

    private fun queSalio(dio: Boolean, escribio: Boolean, cambio: Boolean?) = when {
        !dio -> "no se dio"
        escribio -> "escribió"
        cambio == true -> "la pantalla cambió"
        cambio == false -> "se dio y la pantalla no cambió"
        else -> "se dio; sin huella no sé si cambió"
    }

    /** Lo que se dice en el log de un texto: su largo. */
    private fun largo(texto: String) = "(${texto.length} caracteres)"

    val telefono: Phone = object : Phone {
        override suspend fun state(withScreenshot: Boolean): ScreenState = phone.state(withScreenshot)
        override suspend fun tap(x: Int, y: Int) =
            pasa("tap($x,$y)", Vigilada(escribe = false) { it.alTocar(x, y, nodoVivoEn(x, y)) }) { phone.tap(x, y) }
        override suspend fun type(x: Int, y: Int, text: String) =
            pasa("type($x,$y)", Vigilada(escribe = true) { it.alEscribirEn(x, y) }) { phone.type(x, y, text) }
        override suspend fun openApp(query: String) = pasa("open_app ${largo(query)}") { phone.openApp(query) }
        override suspend fun scroll(down: Boolean) = pasa("scroll ${if (down) "down" else "up"}") { phone.scroll(down) }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long) =
            pasa("swipe($x1,$y1→$x2,$y2)") { phone.swipe(x1, y1, x2, y2, ms) }
        override suspend fun pressKey(key: String) = pasa("key $key") { phone.pressKey(key) }
    }

    val gestos: Gestures = object : Gestures {
        override suspend fun home() = pasa("go_home") { gestures.home() }
        override suspend fun appDrawer() = pasa("open_app_drawer") { gestures.appDrawer() }
        override suspend fun notifications() = pasa("open_notifications") { gestures.notifications() }
        override suspend fun panHome(right: Boolean) = pasa("pan_home ${if (right) "right" else "left"}") { gestures.panHome(right) }
        override suspend fun scrollMenu(down: Boolean) = pasa("scroll_menu ${if (down) "down" else "up"}") { gestures.scrollMenu(down) }
    }

    val sistema: SystemApi = object : SystemApi {
        override suspend fun openApp(name: String) = pasa("launch_app ${largo(name)}") { system.openApp(name) }
        override suspend fun setAlarm(hour: Int, minute: Int, message: String) = pasa("set_alarm") { system.setAlarm(hour, minute, message) }
        override suspend fun setTimer(seconds: Int, message: String) = pasa("set_timer") { system.setTimer(seconds, message) }
        override suspend fun showAlarms() = pasa("show_alarms") { system.showAlarms() }
        override suspend fun createEvent(title: String, startIso: String, location: String) = pasa("create_event") { system.createEvent(title, startIso, location) }
        override suspend fun dial(number: String) = pasa("dial") { system.dial(number) }
        override suspend fun call(number: String) = pasa("call") { system.call(number) }
        override suspend fun sendSms(number: String, message: String) = pasa("send_sms") { system.sendSms(number, message) }
        override suspend fun sendEmail(to: String, subject: String, body: String) = pasa("send_email") { system.sendEmail(to, subject, body) }
        override suspend fun webSearch(query: String) = pasa("web_search") { system.webSearch(query) }
        override suspend fun openUrl(url: String) = pasa("open_url") { system.openUrl(url) }
        override suspend fun maps(query: String) = pasa("open_maps") { system.maps(query) }
        override suspend fun directions(destination: String) = pasa("directions") { system.directions(destination) }
        override suspend fun openCamera() = pasa("open_camera") { system.openCamera() }
        override suspend fun openSettings(section: String) = pasa("open_settings $section") { system.openSettings(section) }
        override suspend fun shareText(text: String) = pasa("share_text") { system.shareText(text) }
        override suspend fun setClipboard(text: String) = pasa("set_clipboard") { system.setClipboard(text) }
        override suspend fun setVolume(stream: String, percent: Int) = pasa("set_volume $stream") { system.setVolume(stream, percent) }
        override suspend fun adjustVolume(stream: String, direction: String) = pasa("adjust_volume $stream $direction") { system.adjustVolume(stream, direction) }
    }

    /** Sin reproductor real, tocar por etiqueta no se puede: pasa por la puerta igual y devuelve `false`. */
    val reproductor: UiPlayer = object : UiPlayer {
        override suspend fun tapLabel(label: String) =
            pasa("tap_label ${largo(label)}", Vigilada(escribe = false) { TopeDeIntentos.Destino.Nombre(label) }) { player?.tapLabel(label) == true }
    }
}
