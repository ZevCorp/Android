package com.zevcorp.graph.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import com.zevcorp.graph.platform.LogBus
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * CONVERSACIÓN HABLADA de verdad, con la Live API de Gemini.
 *
 * Lo otro que tiene la app —transcribir con Deepgram y luego leer con TTS— sirve para dictar, no
 * para conversar: hay un silencio entre lo que dices y lo que oyes, y no puedes interrumpir. Aquí
 * la voz va y viene por un mismo WebSocket: el micrófono entra en crudo, la voz del asistente sale
 * en crudo, y si hablas encima, él se calla (barge-in) — que es justo lo que hace falta cuando la
 * carita te explica algo y te pregunta por tu trabajo.
 *
 * Quien decide que ya entendió es el modelo, no un botón ni un temporizador: cuando lo cree, llama
 * a [FINISH_TOOL] y la app resuelve si cierra la conversación o la encadena con el siguiente paso
 * (ver [Handle]). Lo que la persona dijo queda en [Result.userTranscript], que es exactamente lo
 * que el backend necesita para escribir su system prompt.
 */
class GeminiLive(
    private val context: Context,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val voice: String = DEFAULT_VOICE,
    private val languageCode: String = "es-US",
) {

    /**
     * Mando a distancia de una conversación viva: sirve para hacerla seguir (que el asistente diga
     * lo siguiente) o para darla por terminada. Es lo que permite que la configuración sea UNA
     * conversación —te explico, te escucho, te confirmo, te pido ejemplos— y no cuatro sesiones
     * pegadas con silencios en medio.
     */
    interface Handle {
        /** Hace hablar al asistente ahora, sin esperar a que la persona diga nada. */
        fun prompt(text: String)
        /** Cierra la conversación: `converse` devuelve con lo hablado hasta aquí. */
        fun close()
    }

    /** Lo que quedó de la conversación. */
    class Result(
        /** Todo lo que dijo la persona, ya transcrito por la propia Live API. */
        val userTranscript: String,
        /** Todo lo que dijo el asistente (para dejar rastro en el log). */
        val assistantTranscript: String,
        /** Argumentos de la última función que llamó el modelo, si llamó alguna. */
        val finishArgs: JsonObject?,
        /** Se cerró por error/red y no por decisión del modelo. */
        val failed: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = OkHttpClient.Builder()
        // Una conversación puede tener silencios largos mientras la persona piensa.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var micJob: Job? = null
    private var socket: WebSocket? = null
    private var player: AudioTrack? = null
    private var recorder: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null

    @Volatile private var closed = false
    @Volatile private var lastArgs: JsonObject? = null

    private val userSaid = StringBuilder()
    private val assistantSaid = StringBuilder()

    /**
     * OkHttp llama a los métodos de WebSocketListener desde SU propio hilo lector, no el principal.
     * Quien nos pasa `onAssistantText`/`onUserText`/`onSpeaking`/`onFinish` normalmente toca vistas
     * ahí dentro (es lo natural: pintar la transcripción, animar la carita) — así que esas llamadas
     * SIEMPRE se hacen desde el hilo principal, sin que cada consumidor tenga que acordarse. Si un
     * callback tocara vistas desde el hilo de OkHttp, Android lanza CalledFromWrongThreadException;
     * como eso ocurre DENTRO de un método de WebSocketListener, OkHttp lo toma como un fallo del
     * listener y cierra la conexión — es decir, se vería como "se cayó la conexión" justo al hablar.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * Abre la conversación y SUSPENDE hasta que el modelo la cierra (llamando a [FINISH_TOOL]), o
     * hasta que quien llama cancela la corrutina. Nunca lanza: un fallo de red vuelve como
     * `Result(failed = true)` con lo que se alcanzó a oír.
     */
    suspend fun converse(
        systemInstruction: String,
        /** Lo que arranca el primer turno. La persona no tiene que hablar primero. */
        opening: String,
        /**
         * El modelo dice que ya entendió. Quien llama decide qué sigue: cerrar
         * (`handle.close()`) o encadenar el siguiente paso (`handle.prompt(...)`). Si no se pasa
         * nada, la conversación se cierra ahí.
         */
        onFinish: (args: JsonObject?, handle: Handle) -> Unit = { _, handle -> handle.close() },
        /** Transcripción en vivo de lo que dice el asistente (para pintarlo en pantalla). */
        onAssistantText: (String) -> Unit = {},
        /** Transcripción en vivo de lo que dice la persona. */
        onUserText: (String) -> Unit = {},
        /** true mientras suena la voz del asistente: sirve para animar la carita. */
        onSpeaking: (Boolean) -> Unit = {},
    ): Result = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage(systemInstruction).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) =
                handle(webSocket, text, cont, onAssistantText, onUserText, onSpeaking, opening, onFinish)

            // La Live API responde en tramas binarias con JSON dentro.
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                handle(webSocket, bytes.utf8(), cont, onAssistantText, onUserText, onSpeaking, opening, onFinish)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogBus.log("voz", "live: se cayó la conexión (${t.message})")
                finish(cont, null, failed = true)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(cont, null, failed = !closed)
            }
        }

        socket = client.newWebSocket(request, listener)
        cont.invokeOnCancellation { shutdown() }
    }

    /* ---------- Protocolo ---------- */

    private fun setupMessage(systemInstruction: String) = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/$model")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", voice) }
                    }
                    put("languageCode", languageCode)
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemInstruction) })
                }
            }
            // Sin esto no habría texto de lo hablado, y el texto es justamente lo que se manda al
            // backend para escribir el system prompt de la persona.
            putJsonObject("inputAudioTranscription") {}
            putJsonObject("outputAudioTranscription") {}
            putJsonArray("tools") {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        add(buildJsonObject {
                            put("name", FINISH_TOOL)
                            put("description",
                                "Llama a esta función cuando ya entiendas a qué se dedica la persona, " +
                                    "qué información quiere que organices y cómo la quiere organizada. " +
                                    "No la llames antes de tener las tres cosas.")
                            putJsonObject("parameters") {
                                put("type", "OBJECT")
                                putJsonObject("properties") {
                                    putJsonObject("resumen") {
                                        put("type", "STRING")
                                        put("description",
                                            "Resumen completo, en primera persona del usuario, de su oficio, " +
                                                "qué quiere que se organice y con qué formato.")
                                    }
                                }
                                putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("resumen")) }
                            }
                        })
                    }
                })
            }
        }
    }

    private fun handle(
        webSocket: WebSocket,
        raw: String,
        cont: CancellableContinuation<Result>,
        onAssistantText: (String) -> Unit,
        onUserText: (String) -> Unit,
        onSpeaking: (Boolean) -> Unit,
        opening: String,
        onFinish: (JsonObject?, Handle) -> Unit,
    ) {
        val message = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return

        if (message["setupComplete"] != null) {
            startMic(webSocket)
            // El primer turno lo dispara la app: la carita habla sin que la persona diga nada.
            webSocket.send(userTurn(opening))
            return
        }

        message["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray?.forEach { call ->
            val obj = call.jsonObject
            if (obj["name"]?.jsonPrimitive?.contentOrNull != FINISH_TOOL) return@forEach
            // Se responde antes de cerrar para que el modelo no quede esperando.
            webSocket.send(buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        add(buildJsonObject {
                            obj["id"]?.jsonPrimitive?.contentOrNull?.let { put("id", it) }
                            put("name", FINISH_TOOL)
                            putJsonObject("response") { put("ok", true) }
                        })
                    }
                }
            }.toString())
            val args = obj["args"]?.jsonObject
            lastArgs = args
            val handleForFinish = handleFor(webSocket, cont)
            onMain { onFinish(args, handleForFinish) }
        }

        val serverContent = message["serverContent"]?.jsonObject ?: return

        // La persona habló encima: se corta el audio pendiente (barge-in).
        if (serverContent["interrupted"] != null) {
            runCatching { player?.pause(); player?.flush(); player?.play() }
            onMain { onSpeaking(false) }
        }

        serverContent["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
            assistantSaid.append(it)
            onMain { onAssistantText(it) }
        }
        serverContent["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
            userSaid.append(it)
            onMain { onUserText(it) }
        }

        serverContent["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
            val inline = part.jsonObject["inlineData"]?.jsonObject ?: return@forEach
            val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            onMain { onSpeaking(true) }
            play(Base64.decode(data, Base64.DEFAULT))
        }

        if (serverContent["turnComplete"] != null) onMain { onSpeaking(false) }
    }

    private fun handleFor(webSocket: WebSocket, cont: CancellableContinuation<Result>) = object : Handle {
        override fun prompt(text: String) {
            webSocket.send(userTurn(text))
        }

        override fun close() {
            closed = true
            finish(cont, lastArgs, failed = false)
        }
    }

    /** Un turno de "usuario" que la app inyecta: así el asistente habla cuando hace falta. */
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

    /* ---------- Micrófono → socket ---------- */

    private fun startMic(webSocket: WebSocket) {
        if (micJob != null) return
        micJob = scope.launch {
            val minBuffer = AudioRecord.getMinBufferSize(
                IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = runCatching {
                AudioRecord(
                    // VOICE_COMMUNICATION activa la cancelación de eco del teléfono: sin ella el
                    // micrófono se oye a sí mismo por el altavoz y la conversación se realimenta.
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, IN_RATE)
                )
            }.getOrElse {
                LogBus.log("voz", "live: sin acceso al micrófono (${it.message})")
                return@launch
            }
            recorder = record
            if (AcousticEchoCanceler.isAvailable()) {
                aec = runCatching { AcousticEchoCanceler.create(record.audioSessionId) }.getOrNull()
                aec?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                suppressor = runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull()
                suppressor?.enabled = true
            }

            runCatching { record.startRecording() }.onFailure {
                LogBus.log("voz", "live: no arrancó la grabación (${it.message})")
                return@launch
            }
            // ~100 ms por trama: suficientemente pequeño para que interrumpir se sienta inmediato.
            val chunk = ByteArray(IN_RATE / 10 * 2)
            while (!closed) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                val payload = Base64.encodeToString(chunk.copyOf(read), Base64.NO_WRAP)
                val sent = webSocket.send(buildJsonObject {
                    putJsonObject("realtimeInput") {
                        putJsonObject("audio") {
                            put("mimeType", "audio/pcm;rate=$IN_RATE")
                            put("data", payload)
                        }
                    }
                }.toString())
                if (!sent) break // el socket se llenó o se cerró: no tiene sentido seguir leyendo
            }
            runCatching { record.stop() }
        }
    }

    /* ---------- Socket → altavoz ---------- */

    private fun play(pcm: ByteArray) {
        val track = player ?: AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Misma ruta que una llamada: es lo que empareja con VOICE_COMMUNICATION del
                    // micrófono y deja que el AEC del teléfono haga su trabajo.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                player = it
                runCatching {
                    context.getSystemService(AudioManager::class.java)?.mode = AudioManager.MODE_IN_COMMUNICATION
                }
                it.play()
            }
        runCatching { track.write(pcm, 0, pcm.size) }
    }

    /* ---------- Cierre ---------- */

    private fun finish(cont: CancellableContinuation<Result>, args: JsonObject?, failed: Boolean) {
        shutdown()
        if (cont.isActive) {
            cont.resume(Result(userSaid.toString().trim(), assistantSaid.toString().trim(), args, failed))
        }
    }

    /** Corta todo: micrófono, altavoz, efectos y socket. Es idempotente. */
    fun shutdown() {
        closed = true
        micJob?.cancel(); micJob = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }; recorder = null
        runCatching { aec?.release() }; aec = null
        runCatching { suppressor?.release() }; suppressor = null
        runCatching { player?.pause(); player?.flush(); player?.release() }; player = null
        runCatching {
            context.getSystemService(AudioManager::class.java)?.mode = AudioManager.MODE_NORMAL
        }
        runCatching { socket?.close(1000, "fin") }; socket = null
        scope.cancel()
    }

    companion object {
        /** El modelo hablado de Gemini 3.1 Flash: la conversación va y viene por el mismo socket. */
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"
        const val DEFAULT_VOICE = "Orus"
        const val FINISH_TOOL = "configuracion_lista"
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        /** La Live API recibe PCM de 16 kHz y devuelve PCM de 24 kHz. */
        private const val IN_RATE = 16_000
        private const val OUT_RATE = 24_000
    }
}
