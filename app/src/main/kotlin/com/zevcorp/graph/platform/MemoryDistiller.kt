package com.zevcorp.graph.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Escucha CUALQUIER input del usuario (prompts de texto o voz) y decide si contiene conocimiento
 * durable que valga la pena recordar: reglas ("cada vez que…"), preferencias, cómo usa sus apps.
 * Corre en paralelo a la ejecución, nunca la bloquea, y solo guarda con certeza alta.
 */
class MemoryDistiller(
    private val apiKey: () -> String,
    private val model: () -> String,
) {

    /** Devuelve la nota a recordar, o null si el input era solo una orden puntual. */
    suspend fun capture(input: String): MemoryNote? = withContext(Dispatchers.IO) {
        val prompt = """
            Eres la memoria de Graph, un asistente que controla el teléfono Android del usuario.
            Analiza este input del usuario y decide si contiene CONOCIMIENTO DURABLE que Graph deba
            recordar para el futuro: reglas ("cada vez que te pida X haz Y"), preferencias, rutinas,
            o cómo usa una app concreta ("cuando uses Spotify, conéctate a mi parlante").

            Input del usuario: "$input"

            Criterio ESTRICTO:
            - Una orden puntual ("abre Spotify", "pon una alarma a las 7") NO es memoria: remember=false.
            - Solo recuerda si el usuario está ENSEÑANDO algo reutilizable, con certeza alta.
            - note: la regla destilada en UNA frase imperativa, completa y auto-contenida (con apps,
              nombres y condiciones). Sin relleno ni contexto conversacional.
            - app: el nombre de la app a la que aplica (p.ej. "Spotify"), o "" si es general.
            Responde SOLO JSON: {"remember": true/false, "app": "", "note": ""}
        """.trimIndent()
        runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "memory")
            if (o["remember"]?.jsonPrimitive?.booleanOrNull != true) null
            else MemoryNote(
                app = o["app"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ).takeIf { it.note.isNotBlank() }
        }.getOrElse { LogBus.log("memory", "destilador falló: ${it.message}"); null }
    }
}
