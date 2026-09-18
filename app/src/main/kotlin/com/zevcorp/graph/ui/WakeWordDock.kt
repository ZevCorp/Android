package com.zevcorp.graph.ui

import android.accessibilityservice.AccessibilityService
import android.os.PowerManager
import android.speech.SpeechRecognizer
import com.zevcorp.graph.voice.SystemTranscriber
import graph.core.voz.PalabraDeActivacion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ESCUCHA LIVIANA DE LA PALABRA DE ACTIVACIÓN (spec 007): «hola ü», «ey ü», «oye ü»… Corre en tramos
 * cortos con el reconocedor del SISTEMA, pidiéndole reconocimiento EN EL DISPOSITIVO (offline) para
 * no depender de internet ni mandar audio a ningún servidor mientras espera. Nunca llega al cerebro
 * de la reunión ni a su cola de tareas: solo decide si hay que despertarlos, y quien despierta a esos
 * dos es [VoiceDock] a través de [onDetected]. Nunca guarda ni loguea lo escuchado que NO coincide con
 * la palabra —se descarta en el acto—, ni la frase completa que sí coincide: [onDetected] no recibe
 * texto, solo el aviso de que pasó.
 * Solo corre con la pantalla ENCENDIDA: sin eso, ni intenta escuchar (ahorra batería y no sirve de
 * nada con el teléfono guardado). No sobrevive a pantalla apagada ni a Doze —eso queda para un motor
 * dedicado de palabra de activación, en una versión futura.
 */
class WakeWordDock(
    private val service: AccessibilityService,
    /** Verdadero mientras tenga sentido escuchar: interruptor prendido, nada más usando el micrófono. */
    private val shouldListen: () -> Boolean,
    /** Se llama al detectar la palabra. Nunca recibe la frase que se dijo. */
    private val onDetected: () -> Unit,
) {

    companion object {
        /** Sin reconocedor de voz en el dispositivo: no tiene sentido reintentar cada rato, gasta batería para nada. */
        private const val SIN_RECONOCEDOR_DELAY_MS = 8_000L
    }

    private var loopJob: Job? = null
    private var transcriber: SystemTranscriber? = null

    /**
     * Se incrementa en cada [start]. La iteración de [loop] captura su valor al arrancar: si para
     * cuando termina de limpiarse ya hay una generación más nueva corriendo (un stop()+start() rápido),
     * se abstiene de tocar [transcriber]/[listening] para no pisar el estado del reconocedor nuevo.
     */
    private var gen = 0

    /** Hay un tramo de escucha de la palabra en curso (para no competir con otro uso del micrófono). */
    @Volatile var listening = false
        private set

    private val screenOn: Boolean
        get() = service.getSystemService(PowerManager::class.java)?.isInteractive != false

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        val myGen = ++gen
        loopJob = scope.launch(Dispatchers.Main) { loop(myGen) }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        transcriber?.stop()
        transcriber = null
        listening = false
    }

    private suspend fun loop(myGen: Int) {
        while (currentCoroutineContext().isActive) {
            if (!screenOn || !shouldListen()) {
                delay(500)
                continue
            }
            if (!SpeechRecognizer.isRecognitionAvailable(service)) {
                delay(SIN_RECONOCEDOR_DELAY_MS)
                continue
            }
            listening = true
            val t = SystemTranscriber(service, preferOffline = true)
            transcriber = t
            val heard = runCatching { t.listen() }.getOrElse { e -> if (e is CancellationException) throw e else "" }
            if (myGen == gen) {
                listening = false
                transcriber = null
            }
            if (myGen == gen && heard.isNotBlank() && PalabraDeActivacion.activa(heard)) {
                onDetected()
                delay(400) // deja que arranque el Modo Reunión antes de volver a mirar shouldListen()
            } else {
                delay(150) // silencio o algo que no es la palabra: se descarta sin loguear, se sigue escuchando
            }
        }
    }
}
