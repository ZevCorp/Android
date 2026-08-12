package com.zevcorp.graph.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.platform.RemoteConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * LA VOZ DE Ü, en todas partes: la misma que se oye configurando el perfil (Live API de Gemini,
 * `gemini-3.1-flash-live-preview`) en vez del TTS on-device o el de OpenAI. Un usuario que acaba de
 * oír esa voz en la configuración y luego oye otra distinta al tocar dos veces la carita nota la
 * inconsistencia de inmediato — así que es la MISMA clase de motor la que habla siempre, solo que
 * aquí en modo "solo lectura": sin escuchar, sin herramientas, sin ida y vuelta. Un socket por
 * frase, se manda el texto, se reproduce el audio que llega, se cierra.
 *
 * Mismo contrato que el TTS que reemplaza: `speak()` devuelve `false` si no hay key o falla la red,
 * y quien llama cae al TextToSpeech del sistema — nunca se queda muda.
 */
class GeminiLiveVoice(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var totalFrames = 0
    @Volatile private var playing = false

    val isPlaying: Boolean get() = playing

    private fun apiKey() = RemoteConfig.resolve(
        GraphApp.instance.prefs, "apiKey", "remoteGeminiKey", GraphApp.DEFAULT_API_KEY)

    /** ¿El usuario dejó esta voz activa Y hay key para usarla? (si no, quien llama usa el TTS del sistema). */
    fun enabled(): Boolean =
        GraphApp.instance.prefs.getString("voiceEngine", "gemini") == "gemini" && apiKey().isNotBlank()

    /** Sintetiza y reproduce. Devuelve true en cuanto empieza a sonar; false para caer al TTS del sistema. */
    suspend fun speak(text: String): Boolean {
        if (!enabled()) return false
        val clean = text.trim()
        if (clean.isBlank()) return false
        stop()

        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                var settled = false
                fun settle(started: Boolean) {
                    if (settled) return
                    settled = true
                    if (cont.isActive) cont.resume(started)
                }

                val request = Request.Builder().url("${GeminiLive.ENDPOINT}?key=${apiKey()}").build()
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(setupMessage().toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = onFrame(webSocket, text)
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onFrame(webSocket, bytes.utf8())

                    private fun onFrame(webSocket: WebSocket, raw: String) {
                        val message = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return

                        if (message["setupComplete"] != null) {
                            webSocket.send(userTurn(clean))
                            return
                        }

                        val serverContent = message["serverContent"]?.jsonObject ?: return
                        serverContent["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                            val inline = part.jsonObject["inlineData"]?.jsonObject ?: return@forEach
                            val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                            playing = true
                            play(Base64.decode(data, Base64.DEFAULT))
                            settle(true)
                        }
                        // Ya no llega más audio: se marca dónde termina lo escrito en el buffer y se
                        // cierra el socket (la reproducción sigue sola hasta llegar ahí).
                        if (serverContent["turnComplete"] != null) {
                            markEnd()
                            webSocket.close(1000, "listo")
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        LogBus.log("voz", "voz de Ü falló: ${t.message}")
                        settle(false)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        settle(false) // si cerró sin sonar nada, es un fallo: quien llama cae al TTS del sistema
                    }
                }

                socket = client.newWebSocket(request, listener)
                cont.invokeOnCancellation { runCatching { socket?.close(1000, "cancelado") } }
            }
        }
    }

    /** Corta lo que esté sonando (nueva frase, o el usuario tocó la carita para callarla). */
    fun stop() {
        playing = false
        totalFrames = 0
        runCatching { socket?.close(1000, "detenido") }
        socket = null
        runCatching { player?.pause(); player?.flush(); player?.release() }
        player = null
    }

    /* ---------- Protocolo: mismo formato que GeminiLive, sin escucha ni herramientas ---------- */

    private fun setupMessage() = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/${model()}")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", GeminiLive.DEFAULT_VOICE) }
                    }
                    put("languageCode", "es-US")
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put("text",
                            "Tu única tarea es leer en voz alta, con calidez y naturalidad, EXACTAMENTE " +
                                "el texto que te llega en cada turno. No agregues nada, no comentes, no " +
                                "saludes, no hagas preguntas, no cambies ni una palabra: solo léelo tal cual.")
                    })
                }
            }
        }
    }

    private fun userTurn(text: String) = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                })
            }
            put("turnComplete", true)
        }
    }.toString()

    private fun model() = GraphApp.instance.prefs.getString("liveModel", GeminiLive.DEFAULT_MODEL)
        ?: GeminiLive.DEFAULT_MODEL

    /* ---------- Socket → altavoz ---------- */

    private fun play(pcm: ByteArray) {
        val track = player ?: newTrack().also { player = it; it.play() }
        runCatching { track.write(pcm, 0, pcm.size) }
        totalFrames += pcm.size / 2 // PCM 16-bit mono: 2 bytes por frame
    }

    private fun newTrack(): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(GeminiLive.OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    GeminiLive.OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                ) * 4
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Marca cuándo el audio YA escrito termina de sonar de verdad (no cuando se terminó de
        // escribir en el buffer): sin esto, `isPlaying` se quedaría en true para siempre y un toque
        // en la carita después de que ya calló intentaría "silenciar" algo que no suena.
        runCatching {
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { playing = false }
                override fun onPeriodicNotification(t: AudioTrack) {}
            }, mainHandler)
        }
        return track
    }

    /** Ya se mandó todo el texto: pone la marca en el frame final para saber cuándo de verdad calla. */
    private fun markEnd() {
        val track = player ?: return
        runCatching { track.setNotificationMarkerPosition(totalFrames) }
    }
}
