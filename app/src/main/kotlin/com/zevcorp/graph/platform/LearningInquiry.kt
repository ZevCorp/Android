package com.zevcorp.graph.platform

import graph.core.domain.LearningInquirer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Intervención por voz POR INICIATIVA PROPIA durante la enseñanza pasiva iniciada por el usuario.
 * Mira lo que el usuario acaba de hacer y, tras una cadena de pensamiento muy corta, decide UNA de
 * tres: PROPONER ayuda con algo concreto y pendiente que ve en pantalla ("veo que te escribieron,
 * ¿quieres que responda?"), PREGUNTAR una duda genuinamente útil para aprender, o quedarse callado.
 * Siempre que interviene por voz, el usuario responde con el micrófono sticky bajo la carita.
 */
class LearningInquiry(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val memories: MemoryStore,
    private val scope: CoroutineScope,
    /** true si NO es buen momento para interrumpir (ejecución en curso, mic ocupado…). */
    private val busy: () -> Boolean,
    /** Dice la pregunta en voz alta, muestra el micrófono bajo la carita y devuelve la respuesta. */
    private val askByVoice: suspend (String) -> String,
    /** Ejecuta una tarea aceptada por el usuario (va al Execution Engine). */
    private val runTask: (String) -> Unit,
) : LearningInquirer {

    @Volatile private var working = false

    private class Decision(val action: String, val question: String, val task: String)

    override fun maybeAsk(app: String, screen: String, recentClicks: List<String>, elements: List<String>) {
        if (working || busy()) return
        working = true
        scope.launch(Dispatchers.IO) {
            try {
                val d = decide(app, screen, recentClicks, elements) ?: return@launch
                when (d.action) {
                    "offer" -> propose(app, d)
                    "ask" -> inquire(app, d.question)
                }
            } finally {
                working = false
            }
        }
    }

    /**
     * Cadena de pensamiento CORTÍSIMA + decisión. Prefiere el silencio; jamás preguntas obvias.
     * "offer" solo si ve algo CONCRETO y pendiente con lo que puede ayudar YA.
     */
    private fun decide(app: String, screen: String, clicks: List<String>, elements: List<String>): Decision? {
        val prompt = """
            Eres Ü, un asistente que OBSERVA al usuario usar una app de su Android (él activó tu
            modo aprendizaje). Puedes hacer tareas por él en el teléfono. Esto acaba de pasar:

            App: $app
            Pantalla: $screen
            Lo que tocó (en orden): ${clicks.joinToString(" → ").ifBlank { "(nada)" }}
            Elementos visibles: ${elements.take(30).joinToString(", ").ifBlank { "(ninguno)" }}

            PIENSA PRIMERO (campo "reasoning", UNA frase brevísima) y decide UNA de tres:
            - "offer": ves algo CONCRETO y PENDIENTE en pantalla con lo que podrías ayudar YA (un
              mensaje que él debe contestar, algo a medio hacer, un trámite repetitivo). Propónlo
              corto y natural por voz: "Veo que…, ¿quieres que te ayude con eso?" y define en "task"
              la tarea exacta que harías. Solo si es evidente y útil; nunca por cortesía.
            - "ask": tienes una duda que DE VERDAD te ayudaría a hacer bien esta tarea después y que
              no puedes inferir (un dato personal que solo él sabe). El ORDEN en que hace las cosas
              casi nunca es una regla: NO se pregunta. Jamás preguntes lo obvio.
            - "none": silencio. Ante la duda, siempre "none": es mejor callar que molestar.

            Responde SOLO JSON:
            {"reasoning": "una frase", "action": "offer|ask|none", "question": "lo que dirías por voz o vacío", "task": "instrucción imperativa si action=offer, si no vacío"}
        """.trimIndent()
        return runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "learn")
            val reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val action = o["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val question = o["question"]?.jsonPrimitive?.contentOrNull.orEmpty()
            LogBus.log("learn", "🤔 ${reasoning.take(120)} · $action")
            if (action !in setOf("offer", "ask") || question.isBlank()) null
            else Decision(action, question, o["task"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.getOrElse { LogBus.log("learn", "razonamiento falló: ${it.message}"); null }
    }

    /* ---------- offer: propone ayuda y, si aceptas, ejecuta ---------- */

    private suspend fun propose(app: String, d: Decision) {
        LogBus.log("learn", "🙋 propongo: ${d.question}")
        val answer = askByVoice(d.question)
        if (answer.isBlank()) { LogBus.log("learn", "sin respuesta a la propuesta"); return }
        LogBus.log("learn", "respuesta: \"${answer.take(120)}\"")
        val prompt = """
            Graph le propuso ayuda al usuario y este respondió por voz. Decide si ACEPTÓ.
            Propuesta: "${d.question}"
            Tarea que Graph haría: "${d.task}"
            Respuesta del usuario: "$answer"
            Si aceptó, escribe en "task" la tarea FINAL en imperativo incorporando cualquier detalle
            o cambio que el usuario haya dicho en su respuesta. Si dijo que no o es ambiguo, accept=false.
            Responde SOLO JSON: {"accept": true/false, "task": "tarea final o vacío"}
        """.trimIndent()
        val accepted = runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "learn")
            if (o["accept"]?.jsonPrimitive?.booleanOrNull != true) null
            else o["task"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: d.task.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (accepted == null) { LogBus.log("learn", "propuesta no aceptada"); return }
        LogBus.log("learn", "✅ propuesta aceptada → ejecuto: ${accepted.take(120)}")
        runTask(accepted)
    }

    /* ---------- ask: duda de aprendizaje → memoria durable ---------- */

    private suspend fun inquire(app: String, question: String) {
        LogBus.log("learn", "🙋 pregunto: $question")
        val answer = askByVoice(question)
        if (answer.isBlank()) { LogBus.log("learn", "sin respuesta a la pregunta"); return }
        LogBus.log("learn", "respuesta: \"${answer.take(120)}\"")
        val note = distillNote(app, question, answer) ?: return
        if (memories.add(note)) {
            LogBus.log("learn", "🧠 aprendido de tu respuesta [${note.app}]: ${note.note}")
        }
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
}
