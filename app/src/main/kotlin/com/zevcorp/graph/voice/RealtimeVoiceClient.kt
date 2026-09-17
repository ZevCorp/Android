package com.zevcorp.graph.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import graph.core.voice.RealtimeSession
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.sqrt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * VOZ EN VIVO: oído y boca del asistente con `gpt-realtime` de OpenAI (Realtime API, WebSocket).
 * Reemplazo OPCIONAL del pipeline clásico (Deepgram nova-3 + OpenAI `gpt-4o-mini-tts`) cuando el
 * usuario elige "Live" en el panel de Desarrollador (`voiceEngine == "realtime"`). Implementa
 * [Transcriber] para la mitad de ESCUCHA (mismo contrato que `DeepgramTranscriber`/`SystemTranscriber`)
 * y agrega [speakFinal] para que el cerebro de tareas (Sol/Terra/Luna vía `GraphApp`) diga su
 * respuesta EXACTA con la misma sesión que acaba de escuchar. Realtime nunca decide qué ejecutar:
 * `session.update` manda `tools: []` a propósito — es oído+boca, no un segundo cerebro.
 *
 * SEGURIDAD (pedido explícito: cero keys de Realtime en el APK): antes de conectar pide un TOKEN
 * EFÍMERO al backend Graph (`POST {graphBaseUrl}/api/android/realtime/session`, body `device_id`).
 * Sin ese token no hay sesión — el turno cae al pipeline clásico. El token nunca se cachea en
 * SharedPreferences ni se loguea (ni completo ni parcial); vive solo en memoria de esta instancia.
 */
class RealtimeVoiceClient : Transcriber {

    override var onPartial: ((String) -> Unit)? = null
    override var onLevel: ((Float) -> Unit)? = null

    /** true solo tras `session.updated` confirmado; falso apenas se cae la conexión. */
    @Volatile var connected = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureJob: Job? = null
    private var watchdog: Job? = null
    @Volatile private var stopped = true
    @Volatile private var updateSent = false

    private var transcriptCont: CancellableContinuation<String>? = null
    private var speakCont: CancellableContinuation<Boolean>? = null

    // readTimeout=0: el socket vive lo que dure la sesión de voz, no una request corta.
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    /* ---------- Token efímero: nunca una key horneada ---------- */

    /** Pide el token de un solo uso al backend Graph. null si el endpoint falla o aún no existe. */
    private fun fetchClientSecret(): String? {
        val app = GraphApp.instance
        val url = "${app.resolvedGraphBaseUrl()}/api/android/realtime/session"
        val body = Json.encodeToString(JsonObject.serializer(),
            buildJsonObject { put("device_id", app.resolvedDeviceId()) })
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json")
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            if (code != 200) {
                LogBus.log("voice", "Realtime: sesión del backend HTTP $code: ${text.take(160)}")
                return@runCatching null
            }
            Json.parseToJsonElement(text).jsonObject["client_secret"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrElse { LogBus.log("voice", "Realtime: token efímero falló: ${it.message}"); null }
    }

    /* ---------- Conexión ---------- */

    /** Token → WebSocket → `session.update`. false si algo falla (el llamador cae al pipeline clásico). */
    private suspend fun ensureConnected(): Boolean {
        if (connected) return true
        val secret = withContext(Dispatchers.IO) { fetchClientSecret() } ?: return false
        val ok = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val req = Request.Builder()
                    .url("wss://api.openai.com/v1/realtime?model=gpt-realtime")
                    .addHeader("Authorization", "Bearer $secret")
                    .build()
                val socket = http.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        LogBus.log("voice", "Realtime: WebSocket conectado, esperando session.created")
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleEvent(text) { if (cont.isActive) cont.resume(true) }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        LogBus.log("voice", "Realtime: WebSocket falló: ${t.message}")
                        connected = false
                        if (cont.isActive) cont.resume(false)
                        failPending()
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connected = false
                    }
                })
                ws = socket
                cont.invokeOnCancellation { runCatching { socket.close(1000, "cancel") } }
            }
        } ?: false
        if (ok) {
            connected = true
            // Protección básica: nunca dejar un socket colgado facturando de fondo si algo se
            // olvida de cerrar la sesión (no es política de presupuesto, es el cinturón de seguridad).
            watchdog = scope.launch {
                delay(90_000)
                LogBus.log("voice", "Realtime: 90s de sesión, corto")
                close()
            }
        } else {
            runCatching { ws?.close(1000, "handshake falló") }
            ws = null
        }
        return ok
    }

    private fun handleEvent(text: String, onReady: () -> Unit) {
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (o["type"]?.jsonPrimitive?.contentOrNull) {
            "session.created" -> if (!updateSent) {
                updateSent = true
                ws?.send(Json.encodeToString(JsonObject.serializer(), RealtimeSession.sessionUpdatePayload()))
            }
            "session.updated" -> onReady()
            "input_audio_buffer.speech_started" -> onLevel?.invoke(1f)
            "input_audio_buffer.speech_stopped" -> onLevel?.invoke(0f)
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = o["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                LogBus.log("voice", "Realtime transcribió: \"${transcript.take(120)}\"")
                resumeTranscript(transcript)
            }
            "response.audio.delta" -> {
                val b64 = o["delta"]?.jsonPrimitive?.contentOrNull ?: return
                val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return
                runCatching { track?.write(bytes, 0, bytes.size) }
            }
            "response.done" -> {
                runCatching { track?.stop(); track?.release() }
                track = null
                resumeSpeak(true)
            }
            "error" -> {
                // El body de error de OpenAI no trae el secret, pero por las dudas se recorta corto.
                val msg = runCatching { o["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }
                    .getOrNull() ?: "error desconocido"
                LogBus.log("voice", "Realtime error: ${msg.take(160)}")
                resumeTranscript("")
                resumeSpeak(false)
            }
        }
    }

    /* ---------- Escucha (Transcriber) ---------- */

    /**
     * Escucha hasta que el VAD del servidor detecte fin de turno y llegue la transcripción, o hasta
     * 30 s (mismo tope que `DeepgramTranscriber`). Vacío si no conectó o no se detectó voz.
     */
    override suspend fun listen(): String = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext ""
        stopped = false
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                transcriptCont = cont
                cont.invokeOnCancellation { stopped = true }
                startCapture()
            }
        } ?: run { stopped = true; "" }
    }

    override fun stop() {
        if (transcriptCont != null) resumeTranscript("") else stopped = true
    }

    private fun startCapture() {
        val rate = SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        }.getOrElse { LogBus.log("voice", "Realtime: sin permiso/acceso al micrófono: ${it.message}"); resumeTranscript(""); return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            LogBus.log("voice", "Realtime: AudioRecord no inicializó")
            rec.release(); resumeTranscript(""); return
        }
        recorder = rec
        rec.startRecording()
        captureJob = scope.launch {
            val buf = ShortArray(rate / 10) // ventanas de 100 ms, igual que DeepgramTranscriber
            try {
                while (!stopped) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    var energy = 0.0
                    for (i in 0 until n) energy += buf[i].toDouble() * buf[i]
                    val rms = sqrt(energy / n)
                    onLevel?.invoke((rms / 6000.0).coerceIn(0.0, 1.0).toFloat())
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    ws?.send(Json.encodeToString(JsonObject.serializer(),
                        buildJsonObject { put("type", "input_audio_buffer.append"); put("audio", b64) }))
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                if (recorder === rec) recorder = null
            }
        }
    }

    private fun resumeTranscript(text: String) {
        stopped = true
        transcriptCont?.let { if (it.isActive) it.resume(text) }
        transcriptCont = null
    }

    /* ---------- Habla la respuesta final del cerebro (texto exacto, sin razonar) ---------- */

    /**
     * Le pide a Realtime que DIGA el texto exacto que ya decidió el cerebro de tareas — no que
     * razone ni agregue nada (riesgo conocido: el modelo puede parafrasear en vez de leer literal;
     * si en la prueba real se desvía mucho, es un hallazgo a documentar, no algo resuelto acá).
     * Reproduce los `response.audio.delta` con AudioTrack en streaming. false si falla (el llamador
     * cae al TTS clásico para ese turno).
     */
    suspend fun speakFinal(text: String): Boolean {
        if (!connected) return false
        val clean = text.trim()
        if (clean.isBlank()) return false
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                speakCont = cont
                cont.invokeOnCancellation { }
                startPlayback()
                val payload = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                    put("type", "response.create")
                    put("response", buildJsonObject {
                        put("modalities", buildJsonArray {
                            add(JsonPrimitive("audio"))
                            add(JsonPrimitive("text"))
                        })
                        // SOLO leerlo tal cual; nada de razonar ni ejecutar (session.update ya puso tools: []).
                        put("instructions", "Di EXACTAMENTE y sin agregar ni quitar nada este texto, " +
                            "en tono natural, como si lo dijeras tú mismo: \"$clean\"")
                    })
                })
                val sent = ws?.send(payload) ?: false
                if (!sent && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    private fun resumeSpeak(ok: Boolean) {
        speakCont?.let { if (it.isActive) it.resume(ok) }
        speakCont = null
    }

    private fun startPlayback() {
        val rate = SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minBuf, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
    }

    private fun failPending() {
        resumeTranscript("")
        resumeSpeak(false)
    }

    /** Cierra todo: mic, altavoz, socket. Segura de llamar más de una vez (por ejemplo, tras el watchdog). */
    fun close() {
        stopped = true
        watchdog?.cancel()
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        runCatching { ws?.close(1000, "fin de sesión") }; ws = null
        connected = false
        failPending()
        scope.cancel()
    }

    private companion object {
        // La Realtime API de OpenAI trabaja `pcm16` a 24 kHz mono — no es configurable, es el
        // formato fijo del protocolo (a diferencia del REST de Deepgram, que sí acepta 16 kHz).
        const val SAMPLE_RATE = 24_000
    }
}
