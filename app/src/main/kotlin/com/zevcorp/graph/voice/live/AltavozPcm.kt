package com.zevcorp.graph.voice.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import graph.core.voz.ColaDeReproduccion
import graph.core.voz.ProtocoloGptLive
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * EL ALTAVOZ DE LA VOZ EN VIVO (docs/specs/002, fase B1b): un `AudioTrack` en `MODE_STREAM` a 24 kHz mono PCM16, alimentado
 * desde [ColaDeReproduccion] por un hilo propio. GPT-Live manda la voz más rápido de lo que suena; la cola la guarda y
 * `sonando()` es «la cola tiene bytes», que es la llave de la compuerta de eco: el estado, jamás el volumen.
 *
 * EL HILO ES EL ÚNICO DUEÑO DEL AudioTrack: [callar] vacía la cola en el acto y le pide el `flush`, que el hilo hace antes de
 * escribir otra vez. Así nadie llama al AudioTrack desde dos hilos, y lo viejo que suena tras callar es como mucho un
 * trozo de [BYTES_POR_ESCRITURA].
 *
 * `USAGE_MEDIA` y no `USAGE_VOICE_COMMUNICATION`: sin cambiar el modo de audio del teléfono, la de comunicación puede salir
 * por el auricular y la prueba no se oiría. Si el AEC no aguanta el eco del altavoz, se mide en el nivel 4.
 */
class AltavozPcm(private val log: (tag: String, mensaje: String) -> Unit) {

    companion object {
        const val TAG = MicrofonoPcm.TAG
        const val RITMO = ProtocoloGptLive.RITMO

        /** 20 ms: lo que puede sonar de más tras callar, y la precisión con que la cola sabe si aún queda voz. */
        const val BYTES_POR_ESCRITURA = RITMO * 2 / 50
    }

    private val candado = ReentrantLock()
    private val hayTrabajo = candado.newCondition()
    private val cola = ColaDeReproduccion()

    /** Bajo [candado]. */
    private var activo = false
    private var vaciarPista = false
    private var descartadosAnunciados = 0L

    private var pista: AudioTrack? = null
    private var hilo: Thread? = null

    /** Falso si el sistema no dio un AudioTrack: la voz se oiría muda, y eso se dice. */
    fun arrancar(): Boolean {
        if (pista != null) return true
        val minimo = AudioTrack.getMinBufferSize(RITMO, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val p = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RITMO)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minimo, BYTES_POR_ESCRITURA * 4))
                .build()
        } catch (e: Exception) {
            log(TAG, "sin altavoz: el sistema no dio un AudioTrack (${e::class.simpleName})")
            return false
        }
        if (p.state != AudioTrack.STATE_INITIALIZED) {
            p.release()
            log(TAG, "sin altavoz: el AudioTrack no inicializó")
            return false
        }
        p.play()
        pista = p
        candado.withLock { activo = true }
        hilo = thread(name = "voz-altavoz", isDaemon = true) { escribir(p) }
        log(TAG, "altavoz abierto: $RITMO Hz mono PCM16, cola de ${ColaDeReproduccion.SEGUNDOS} s")
        return true
    }

    /** Encola la voz de Ü. Si la cola se llenó, lo que se pierde es lo más viejo, y se dice una vez. */
    fun reproducir(pcm: ByteArray) {
        val primerDescarte = candado.withLock {
            if (!activo) return
            cola.meter(pcm)
            hayTrabajo.signal()
            (descartadosAnunciados == 0L && cola.bytesDescartados > 0).also { if (it) descartadosAnunciados = cola.bytesDescartados }
        }
        if (primerDescarte) log(TAG, "la cola del altavoz se llenó: se descarta lo más viejo")
    }

    fun sonando(): Boolean = candado.withLock { cola.sonando() }

    /** Calla YA: la cola queda vacía antes de volver, y el hilo hace el flush de lo que ya estaba en el AudioTrack. */
    fun callar() {
        candado.withLock {
            cola.callar()
            vaciarPista = true
            hayTrabajo.signal()
        }
    }

    fun parar() {
        val p = pista ?: return
        val descartados = candado.withLock {
            activo = false
            cola.callar()
            hayTrabajo.signal()
            cola.bytesDescartados
        }
        // stop() desbloquea un write() en curso.
        runCatching { p.pause(); p.flush(); p.stop() }
        hilo?.join(500)
        hilo = null
        runCatching { p.release() }
        pista = null
        val ms = descartados / (RITMO * 2 / 1000)
        log(TAG, "altavoz cerrado" + if (ms > 0) "; la cola llena descartó $ms ms" else "")
    }

    private fun escribir(p: AudioTrack) {
        while (true) {
            var vaciar = false
            val trozo = candado.withLock {
                while (activo && !cola.sonando() && !vaciarPista) hayTrabajo.await()
                if (!activo) return
                vaciar = vaciarPista
                vaciarPista = false
                cola.sacar(BYTES_POR_ESCRITURA)
            }
            try {
                if (vaciar) {
                    p.pause()
                    p.flush()
                    p.play()
                }
                if (trozo.isNotEmpty()) p.write(trozo, 0, trozo.size)
            } catch (e: IllegalStateException) {
                // El AudioTrack se soltó por debajo (parar desde otro hilo): no hay a dónde escribir.
                return
            }
        }
    }
}
