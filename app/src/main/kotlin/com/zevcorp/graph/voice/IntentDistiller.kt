package com.zevcorp.graph.voice

import com.zevcorp.graph.platform.GeminiJson
import com.zevcorp.graph.platform.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * El "primer LLM" del pipeline de voz del repo Graph: NO resume la transcripción — DESTILA solo la
 * intención relevante del usuario en una nota estructurada, eliminando conversación de fondo,
 * muletillas, ruido y duplicados del STT. Allá esa nota alimentaba el Autofill; aquí alimenta el
 * Execution Engine, cuyo LLM interpreta la nota y ejecuta las acciones en el teléfono.
 */
class IntentDistiller(
    private val apiKey: () -> String,
    private val model: () -> String,
) {

    /** Devuelve la intención destilada lista para el motor, o null si no había nada accionable. */
    suspend fun distill(transcript: String): String? = withContext(Dispatchers.IO) {
        val prompt = """
            Eres el destilador de intención de Graph, un asistente que controla un teléfono Android.
            Recibes la TRANSCRIPCIÓN CRUDA de lo que se habló cerca del micrófono. Puede contener
            conversación de fondo, saludos, muletillas, correcciones a medias, ruido y fragmentos
            duplicados del reconocedor de voz.

            TU TRABAJO NO ES RESUMIR: es DESTILAR únicamente lo que el usuario quiere que el
            asistente HAGA en el teléfono, como una instrucción limpia y accionable.
            - Elimina todo lo que no aporte al objetivo (charla de fondo, comentarios, relleno).
            - Conserva TODOS los detalles operativos: apps, nombres, destinatarios, cantidades,
              horas, textos a escribir/buscar, condiciones y el orden de los pasos.
            - Si el usuario se corrigió ("no, mejor a las 8"), queda la versión final.
            - No inventes nada que no esté en la transcripción.
            - Si no hay ninguna intención accionable para el teléfono, worth=false.

            Transcripción: "$transcript"

            Responde SOLO JSON:
            {"worth": true/false, "intent": "instrucción imperativa, completa y auto-contenida (varios pasos si los hay)"}
        """.trimIndent()
        runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "voice")
            if (o["worth"]?.jsonPrimitive?.booleanOrNull != true) null
            else o["intent"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrElse { LogBus.log("voice", "destilador falló: ${it.message}"); null }
    }
}
