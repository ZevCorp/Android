package graph.core.contrato

import graph.core.voice.RealtimeSession
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONTRATO 008 — VOZ EN VIVO, EL PAYLOAD QUE EVITA LA RESPUESTA FANTASMA
 * (docs/specs/008-voz-en-vivo-realtime-retrofit.md).
 *
 * Retrofit: la funcionalidad (`RealtimeVoiceClient.kt`, `app/…/voice/`) ya estaba implementada
 * cuando esta promesa se escribió — el porqué está en la spec. `RealtimeSession.sessionUpdatePayload()`
 * sí nació para vivir acá: es JSON puro, sin red ni Android, así que esta promesa la juzga de verdad.
 */
class Contrato008VozRealtime {

    companion object {
        val PROMESAS = mapOf(
            801 to ("El `session.update` de la voz en vivo (Realtime) manda " +
                "`turn_detection.create_response:false`, `tools:[]`, `pcm16` en entrada y salida y " +
                "transcripción con `whisper-1`; sin `create_response:false` el servidor podía generar " +
                "una respuesta fantasma que cortaba el audio real a mitad de camino."),
        )
        fun promesa(n: Int) = "promesa $n: ${PROMESAS.getValue(n)}"
    }

    @Test
    fun promesa801() {
        val payload = RealtimeSession.sessionUpdatePayload()
        assertEquals("session.update", payload["type"]?.jsonPrimitive?.content, promesa(801))

        val session = payload["session"]?.jsonObject
            ?: error("${promesa(801)} · el payload no trae \"session\"")
        assertEquals(
            listOf("audio", "text"),
            session["modalities"]?.jsonArray?.map { it.jsonPrimitive.content },
            promesa(801),
        )
        assertEquals("pcm16", session["input_audio_format"]?.jsonPrimitive?.content, promesa(801))
        assertEquals("pcm16", session["output_audio_format"]?.jsonPrimitive?.content, promesa(801))

        val turnDetection = session["turn_detection"]?.jsonObject
            ?: error("${promesa(801)} · el payload no trae \"session.turn_detection\"")
        assertEquals("server_vad", turnDetection["type"]?.jsonPrimitive?.content, promesa(801))
        assertEquals(
            false,
            turnDetection["create_response"]?.jsonPrimitive?.boolean,
            promesa(801) + " · sin esto, el VAD del servidor puede generar una respuesta fantasma",
        )

        assertEquals(emptyList(), session["tools"]?.jsonArray?.toList(), promesa(801) + " · la voz nunca decide qué ejecutar")

        val transcription = session["input_audio_transcription"]?.jsonObject
            ?: error("${promesa(801)} · el payload no trae \"session.input_audio_transcription\"")
        assertEquals("whisper-1", transcription["model"]?.jsonPrimitive?.content, promesa(801))
    }
}
