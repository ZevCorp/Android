package com.zevcorp.graph.voice.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.os.Process
import graph.core.voz.ProtocoloGptLive
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * EL MICRÓFONO DE LA VOZ EN VIVO (docs/specs/002, fase B1b): PCM16 mono a 24 kHz en trozos de 100 ms, que es lo que
 * `session.input_audio.append` espera. Delgado a propósito: qué se hace con cada trozo lo decide `ConversacionViva`.
 *
 * `VOICE_COMMUNICATION` Y NO `VOICE_RECOGNITION`: es la fuente a la que el sistema le cuelga su cancelador de eco. Si además
 * hay `AcousticEchoCanceler`, se enciende y [hayAec] lo dice, para que `ModoDeCaptura` sepa quién corta el eco.
 */
class MicrofonoPcm(
    private val contexto: Context,
    /** Un trozo de [BYTES_POR_TROZO], desde el hilo del micrófono. Tiene que volver enseguida. */
    private val alCapturar: (ByteArray) -> Unit,
    private val log: (tag: String, mensaje: String) -> Unit,
) {

    companion object {
        const val TAG = "voz-audio"
        const val RITMO = ProtocoloGptLive.RITMO

        /** 100 ms a 24 kHz, 2 bytes por muestra: 4800 B. */
        const val BYTES_POR_TROZO = RITMO * 2 / 10
    }

    /** El cancelador de eco del sistema quedó encendido sobre esta grabación. */
    @Volatile
    var hayAec = false
        private set

    /** Por qué no arrancó, dicho para una persona. Vacío si arrancó. */
    var porQueNo = ""
        private set

    @Volatile
    private var activo = false
    private var grabadora: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var hilo: Thread? = null

    /** Abre y empieza a entregar trozos. Falso si no pudo, con [porQueNo]: sin permiso no revienta, lo dice. */
    fun arrancar(): Boolean {
        if (activo) return true
        porQueNo = ""
        if (contexto.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return noArranca("falta el permiso del micrófono (RECORD_AUDIO)")
        }
        val minimo = AudioRecord.getMinBufferSize(RITMO, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RITMO, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minimo, BYTES_POR_TROZO * 4),
            )
        } catch (e: Exception) {
            // Un permiso revocado entre el chequeo y aquí lanza SecurityException; un formato que no admite, IllegalArgument.
            return noArranca("el micrófono no abrió (${e::class.simpleName})")
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return noArranca("el micrófono no inicializó")
        }
        hayAec = encenderAec(r.audioSessionId)
        try {
            r.startRecording()
        } catch (e: IllegalStateException) {
            soltar(r)
            return noArranca("el micrófono no empezó a grabar (${e::class.simpleName})")
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            soltar(r)
            return noArranca("el micrófono está ocupado por otra app")
        }
        grabadora = r
        activo = true
        hilo = thread(name = "voz-microfono", isDaemon = true) { leer(r) }
        log(TAG, "micrófono abierto: $RITMO Hz mono PCM16, trozos de $BYTES_POR_TROZO B; AEC del sistema ${if (hayAec) "encendido" else "no disponible"}")
        return true
    }

    /** Suelta todo. Llamarlo dos veces, o sin haber arrancado, no hace nada. */
    fun parar() {
        val r = grabadora ?: return
        activo = false
        // stop() desbloquea el read() del hilo: sin él, el join esperaría al siguiente trozo.
        runCatching { r.stop() }
        hilo?.join(500)
        hilo = null
        soltar(r)
        grabadora = null
        log(TAG, "micrófono cerrado")
    }

    private fun noArranca(motivo: String): Boolean {
        porQueNo = motivo
        log(TAG, "sin micrófono: $motivo")
        return false
    }

    private fun encenderAec(sesion: Int): Boolean {
        if (!AcousticEchoCanceler.isAvailable()) return false
        val a = runCatching { AcousticEchoCanceler.create(sesion) }.getOrNull() ?: return false
        val encendido = runCatching { a.setEnabled(true) == AudioEffect.SUCCESS && a.enabled }.getOrDefault(false)
        if (!encendido) {
            runCatching { a.release() }
            return false
        }
        aec = a
        return true
    }

    private fun soltar(r: AudioRecord) {
        runCatching { aec?.release() }
        aec = null
        hayAec = false
        runCatching { r.release() }
    }

    /** Llena trozos enteros de 100 ms: un read() puede devolver menos, y un trozo corto desacompasa el turno. */
    private fun leer(r: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var trozo = ByteArray(BYTES_POR_TROZO)
        var lleno = 0
        var fallosDelOyente = 0
        while (activo) {
            val n = r.read(trozo, lleno, BYTES_POR_TROZO - lleno)
            if (n < 0) {
                if (activo) log(TAG, "el micrófono dejó de leer (código $n)")
                break
            }
            lleno += n
            if (lleno < BYTES_POR_TROZO) continue
            try {
                alCapturar(trozo)
            } catch (e: Exception) {
                // Un oyente que revienta no apaga el micrófono; se dice una vez, con el tipo y sin el mensaje.
                if (fallosDelOyente++ == 0) log(TAG, "quien recibe el micrófono falló (${e::class.simpleName}); sigo capturando")
            }
            trozo = ByteArray(BYTES_POR_TROZO)
            lleno = 0
        }
    }
}
