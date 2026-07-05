package com.zevcorp.graph.platform

import graph.core.domain.LearningInquirer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Interrupción por voz durante la enseñanza pasiva iniciada por el usuario. Mira lo que el usuario
 * acaba de hacer en una app y, SOLO si tras una cadena de pensamiento muy corta concluye que hay un
 * motivo real (no una duda obvia que él mismo puede inferir), la pregunta por voz, escucha la
 * respuesta y la guarda como conocimiento durable de esa app. Aislado: PassiveLearning decide
 * "cuándo"; esta clase decide "si vale la pena" y "qué preguntar" y hace todo el trabajo async.
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
) : LearningInquirer {

    @Volatile private var working = false

    override fun maybeAsk(app: String, screen: String, recentClicks: List<String>, elements: List<String>) {
        if (working || busy()) return
        working = true
        scope.launch(Dispatchers.IO) {
            try {
                val question = decideQuestion(app, screen, recentClicks, elements) ?: return@launch
                LogBus.log("learn", "🙋 pregunto: $question")
                val answer = askByVoice(question)
                if (answer.isBlank()) { LogBus.log("learn", "sin respuesta a la pregunta"); return@launch }
                LogBus.log("learn", "respuesta: \"${answer.take(120)}\"")
                val note = distillNote(app, question, answer) ?: return@launch
                if (memories.add(note)) {
                    LogBus.log("learn", "🧠 aprendido de tu respuesta [${note.app}]: ${note.note}")
                }
            } finally {
                working = false
            }
        }
    }

    /**
     * Cadena de pensamiento CORTÍSIMA + decisión. El modelo razona en una frase si la duda es obvia
     * o inferible por él mismo; solo si concluye que NO lo es y que la respuesta cambiaría cómo hará
     * la tarea, formula la pregunta. Texto, sin imagen.
     */
    private fun decideQuestion(app: String, screen: String, clicks: List<String>, elements: List<String>): String? {
        val prompt = """
            Eres Graph, un asistente que aprende observando al usuario usar una app de su Android para
            luego hacer esas tareas por él. Acaba de hacer esto:

            App: $app
            Pantalla: $screen
            Lo que tocó (en orden): ${clicks.joinToString(" → ").ifBlank { "(nada)" }}
            Elementos visibles: ${elements.take(30).joinToString(", ").ifBlank { "(ninguno)" }}

            PIENSA PRIMERO (campo "reasoning", UNA sola frase brevísima): ¿de verdad necesitas
            preguntarle algo, o la respuesta es OBVIA / puedes inferirla tú mismo?
            Reglas de oro para tu razonamiento:
            - La gente hace las cosas de forma variable y según el contexto. El ORDEN en que revisa
              cuentas, toca botones o abre secciones casi NUNCA es una regla fija: NO se pregunta.
            - Ejemplo de pregunta TONTA que jamás debes hacer: "¿siempre revisas estas cuentas en el
              mismo orden?" — es obvio que no. No hagas preguntas cuya respuesta ya conoces.
            - Solo vale preguntar si la respuesta cambiaría DE VERDAD cómo harías la tarea después y
              no la puedes inferir del contexto (p.ej. un dato personal que solo el usuario sabe).
            - Ante la duda, quédate callado. Es mejor no preguntar que hacer una pregunta obvia.

            Devuelve SOLO JSON:
            {"reasoning": "una frase", "worth_asking": true/false, "question": "pregunta corta y natural, o vacío"}
        """.trimIndent()
        return runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "learn")
            val reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val worth = o["worth_asking"]?.jsonPrimitive?.booleanOrNull == true
            LogBus.log("learn", "🤔 ${reasoning.take(120)} · ${if (worth) "pregunto" else "me lo respondo solo"}")
            if (!worth) null
            else o["question"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrElse { LogBus.log("learn", "razonamiento falló: ${it.message}"); null }
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
