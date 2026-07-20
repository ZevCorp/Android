package com.zevcorp.graph.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.zevcorp.graph.platform.LogBus
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Elige el transcriptor según configuración: Deepgram nova-3 si hay key, si no el del sistema. */
fun defaultTranscriber(context: Context): Transcriber {
    // usuario → distribuida por el backend (graph_client_config) → horneada al compilar
    val key = com.zevcorp.graph.platform.RemoteConfig.resolve(
        com.zevcorp.graph.GraphApp.instance.prefs,
        "deepgramKey", "remoteDeepgramKey", com.zevcorp.graph.GraphApp.DEFAULT_DEEPGRAM_KEY)
    return if (key.isNotBlank()) DeepgramTranscriber(key) else SystemTranscriber(context)
}

/**
 * Captura del micrófono → texto. Dos implementaciones intercambiables:
 * Deepgram nova-3 (el pipeline del repo Graph) si hay API key, o el reconocedor del sistema.
 */
interface Transcriber {
    /** Escucha hasta silencio / stop() / tope de tiempo y devuelve la transcripción final. */
    suspend fun listen(): String

    /** Orden temprana de terminar (p.ej. el usuario tocó la burbuja). */
    fun stop()

    /** Transcripción parcial en vivo (si el motor la soporta). Se llama en el hilo principal. */
    var onPartial: ((String) -> Unit)?

    /** Nivel de voz 0..1 en vivo (para animar el indicador de escucha), si el motor lo soporta. */
    var onLevel: ((Float) -> Unit)?
}

/**
 * El mismo flujo del repo Graph (ClinicalRawTranscriptionService): audio → Deepgram nova-3 en
 * español por REST. Aquí se graba PCM 16 kHz con corte por silencio y se envía en un solo POST.
 */
class DeepgramTranscriber(private val apiKey: String) : Transcriber {

    @Volatile private var stopped = false
    override var onPartial: ((String) -> Unit)? = null
    override var onLevel: ((Float) -> Unit)? = null

    override fun stop() { stopped = true }

    override suspend fun listen(): String = withContext(Dispatchers.IO) {
        stopped = false
        val pcm = record()
        if (pcm.isEmpty()) return@withContext ""
        transcribe(pcm)
    }

    /** Graba hasta 1.6 s de silencio tras oír voz (o 8 s sin voz, o 30 s en total). */
    private fun record(): ByteArray {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        }.getOrElse { LogBus.log("voice", "sin permiso/acceso al micrófono: ${it.message}"); return ByteArray(0) }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            LogBus.log("voice", "AudioRecord no inicializó")
            recorder.release()
            return ByteArray(0)
        }
        val out = ByteArrayOutputStream()
        val buf = ShortArray(rate / 10) // ventanas de 100 ms
        var heardSpeech = false
        var silentMs = 0
        var totalMs = 0
        recorder.startRecording()
        try {
            while (!stopped && totalMs < 30_000) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) break
                totalMs += 100
                var energy = 0.0
                for (i in 0 until n) energy += buf[i].toDouble() * buf[i]
                val rms = sqrt(energy / n)
                onLevel?.let { cb -> val lvl = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat(); cb(lvl) }
                if (rms > 900) { heardSpeech = true; silentMs = 0 } else silentMs += 100
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    bytes[i * 2] = (v and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                }
                out.write(bytes)
                if (heardSpeech && silentMs >= 1600) break
                if (!heardSpeech && totalMs >= 8_000) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (!heardSpeech) {
            LogBus.log("voice", "no se detectó voz (¿mic bloqueado en segundo plano?)")
            return ByteArray(0)
        }
        return out.toByteArray()
    }

    private fun transcribe(pcm: ByteArray): String {
        val url = "https://api.deepgram.com/v1/listen" +
            "?model=nova-3&language=es&punctuate=true&smart_format=true" +
            "&encoding=linear16&sample_rate=16000&channels=1"
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Token $apiKey")
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.doOutput = true
        c.outputStream.use { it.write(pcm) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        if (code >= 300) { LogBus.log("voice", "Deepgram HTTP $code: ${body.take(160)}"); return "" }
        return runCatching {
            Json.parseToJsonElement(body).jsonObject["results"]!!.jsonObject["channels"]!!.jsonArray[0]
                .jsonObject["alternatives"]!!.jsonArray[0].jsonObject["transcript"]!!
                .jsonPrimitive.contentOrNull.orEmpty().trim()
        }.getOrElse { "" }
    }
}

/** Fallback sin API key: el SpeechRecognizer del sistema (mismo motor del 🎤 del chat). */
class SystemTranscriber(private val context: Context) : Transcriber {

    private var recognizer: SpeechRecognizer? = null
    override var onPartial: ((String) -> Unit)? = null
    override var onLevel: ((Float) -> Unit)? = null

    override fun stop() {
        recognizer?.let { r -> android.os.Handler(context.mainLooper).post { runCatching { r.stopListening() } } }
    }

    override suspend fun listen(): String = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext ""
        suspendCancellableCoroutine { cont ->
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = r
            fun finish(text: String) {
                runCatching { r.destroy() }
                recognizer = null
                if (cont.isActive) cont.resume(text)
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) =
                    finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                override fun onError(error: Int) = finish("")
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // rmsdB ~ -2..10; normaliza a 0..1 para el indicador de escucha.
                    onLevel?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { onPartial?.invoke(it) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
            cont.invokeOnCancellation { runCatching { r.destroy() } }
        }
    }
}
