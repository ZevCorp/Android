package graph.core.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * El payload `session.update` que abre cada sesión de voz en vivo (Realtime API de OpenAI,
 * `RealtimeVoiceClient` en `app/…/voice/`). Esa clase no puede vivir en `commonMain`: abre el
 * WebSocket con OkHttp y graba/reproduce con `AudioRecord`/`AudioTrack`, dos cosas que no existen
 * fuera de Android. Pero la FORMA de este payload es JSON puro, sin red ni Android — y es
 * justamente la pieza que un revisor encontró rota (ver [sessionUpdatePayload]), así que vale la
 * pena que viva acá, portable y con su propia promesa, en vez de enterrada sin juez en `app`.
 */
object RealtimeSession {

    /**
     * `turn_detection.create_response = false` es la corrección de una regresión real (commit
     * `14e2197`): sin ese campo, el VAD del servidor generaba una respuesta de audio por su cuenta
     * apenas detectaba fin de turno, en paralelo a la que dispara `speakFinal()` con el texto que
     * ya decidió el cerebro de tareas — si los tiempos coincidían, esa respuesta fantasma le
     * cortaba el audio real a mitad de camino. `tools: []` es a propósito: el modelo de voz nunca
     * decide qué ejecutar, solo oye y habla.
     */
    fun sessionUpdatePayload(): JsonObject = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("modalities", buildJsonArray { add(JsonPrimitive("audio")); add(JsonPrimitive("text")) })
            put("input_audio_format", "pcm16")
            put("output_audio_format", "pcm16")
            put("turn_detection", buildJsonObject {
                put("type", "server_vad")
                put("create_response", false)
            })
            put("tools", buildJsonArray { })
            put("input_audio_transcription", buildJsonObject { put("model", "whisper-1") })
        })
    }
}
