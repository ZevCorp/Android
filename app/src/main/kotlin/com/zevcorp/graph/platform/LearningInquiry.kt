package com.zevcorp.graph.platform

import android.content.Context
import com.zevcorp.graph.voice.defaultTranscriber
import graph.core.domain.LearningInquirer
import graph.core.domain.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Interrupción por voz durante la enseñanza pasiva iniciada por el usuario. Mira lo que el usuario
 * acaba de hacer en una app y, si tiene una duda ÚTIL para hacer bien esa tarea en el futuro, la
 * dice en voz alta, escucha la respuesta y la guarda como conocimiento de esa app (memoria durable
 * que el motor de ejecución ya inyecta en su prompt). Aislado: PassiveLearning solo decide "cuándo";
 * esta clase decide "qué preguntar" y hace todo el trabajo async.
 */
class LearningInquiry(
    private val context: Context,
    private val apiKey: () -> String,
    private val model: () -> String,
    private val voice: Voice,
    private val memories: MemoryStore,
    private val scope: CoroutineScope,
    /** true si NO es buen momento para interrumpir (p.ej. hay una ejecución en curso). */
    private val busy: () -> Boolean,
) : LearningInquirer {

    @Volatile private var working = false

    override fun maybeAsk(app: String, screen: String, recentClicks: List<String>, elements: List<String>) {
        if (working || busy()) return
        working = true
        scope.launch(Dispatchers.IO) {
            try {
                val question = generateQuestion(app, screen, recentClicks, elements) ?: return@launch
                voice.speak(question)
                LogBus.log("learn", "🙋 pregunto: $question")
                val answer = listenForAnswer()
                if (answer.isBlank()) { LogBus.log("learn", "sin respuesta a la pregunta"); return@launch }
                LogBus.log("learn", "respuesta: \"${answer.take(120)}\"")
                val note = distillNote(app, question, answer) ?: return@launch
                if (memories.add(note)) {
                    LogBus.log("learn", "🧠 aprendido de tu respuesta [${note.app}]: ${note.note}")
                    voice.narrate("🧠 Anotado, gracias")
                }
            } finally {
                working = false
            }
        }
    }

    /** Pregunta clarificadora (o null si no hay ninguna que valga la pena). Texto, sin imagen. */
    private fun generateQuestion(app: String, screen: String, clicks: List<String>, elements: List<String>): String? {
        val prompt = """
            Eres Graph, un asistente con personalidad que está APRENDIENDO observando al usuario usar
            una app de su teléfono Android (él activó tu modo de aprendizaje). Tu meta es poder hacer
            estas tareas por él en el futuro. Mira lo que acaba de hacer:

            App: $app
            Pantalla: $screen
            Lo que tocó (en orden): ${clicks.joinToString(" → ").ifBlank { "(nada)" }}
            Elementos visibles: ${elements.take(30).joinToString(", ").ifBlank { "(ninguno)" }}

            Si tienes UNA duda que te ayudaría a hacer bien esta tarea después (p.ej. "¿esta es la
            lista que pones siempre?", "¿a quién le escribes normalmente aquí?"), formúlala corta y
            natural, en el idioma del usuario. Si no tienes una duda genuinamente útil, quédate
            callado: NO preguntes por preguntar. Prefiere el silencio a una pregunta obvia o molesta.
            Responde SOLO JSON: {"ask": true/false, "question": "pregunta corta o vacío"}
        """.trimIndent()
        return runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "learn")
            if (o["ask"]?.jsonPrimitive?.booleanOrNull != true) null
            else o["question"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrElse { LogBus.log("learn", "pregunta falló: ${it.message}"); null }
    }

    /** Convierte (pregunta, respuesta) en una regla durable y auto-contenida para esa app. */
    private fun distillNote(app: String, question: String, answer: String): MemoryNote? {
        val prompt = """
            Durante el aprendizaje, Graph preguntó al usuario y este respondió. Destila una regla o
            preferencia DURABLE y auto-contenida que Graph deba recordar para hacer bien las tareas
            en esta app. Escríbela en UNA frase imperativa, con los nombres/detalles concretos, sin
            relleno. Si la respuesta no aporta nada reutilizable, worth=false.

            App: $app
            Pregunta de Graph: "$question"
            Respuesta del usuario: "$answer"
            Responde SOLO JSON: {"worth": true/false, "app": "nombre de la app o ''", "note": "regla en una frase"}
        """.trimIndent()
        return runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "learn")
            if (o["worth"]?.jsonPrimitive?.booleanOrNull != true) null
            else MemoryNote(
                app = o["app"]?.jsonPrimitive?.contentOrNull?.ifBlank { app } ?: app,
                note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ).takeIf { it.note.isNotBlank() }
        }.getOrElse { LogBus.log("learn", "destilar respuesta falló: ${it.message}"); null }
    }

    /** Escucha la respuesta hablada del usuario con el mismo pipeline de voz (mic en foreground). */
    private suspend fun listenForAnswer(): String {
        MicService.start(context)
        return try {
            val t = defaultTranscriber(context)
            withContext(Dispatchers.IO) { runCatching { t.listen() }.getOrElse { "" } }
        } finally {
            MicService.stop(context)
        }
    }
}
