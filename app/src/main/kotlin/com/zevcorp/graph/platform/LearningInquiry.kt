package com.zevcorp.graph.platform

import graph.core.domain.LearningInquirer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Intervención por voz POR INICIATIVA PROPIA durante la enseñanza pasiva iniciada por el usuario.
 * Mira lo que el usuario acaba de hacer y, tras una cadena de pensamiento muy corta, decide UNA de
 * tres: PROPONER ayuda con algo concreto y pendiente que ve en pantalla ("veo que te escribieron,
 * ¿quieres que responda?"), PREGUNTAR una duda genuinamente útil para aprender, o quedarse callado.
 *
 * La decisión está CONECTADA a la knowledge-base personal del usuario: sus preferencias e
 * instrucciones (p.ej. "cada vez que estemos en WhatsApp y un usuario me pida una mejora, propón
 * hacerla tú") entran al razonamiento, y cuando una instrucción suya aplica al escenario que se
 * está viendo en vivo, proponer deja de ser opcional: es lo que él pidió. Es un mindset distinto
 * al del "amigo prevenido" (Anticipation), que protege una acción recién ejecutada: aquí el
 * asistente observa en tiempo real y detecta oportunidades de hacer él algo que al usuario le
 * consumiría tiempo.
 *
 * CÓMO RESPONDE EL USUARIO: no hay micrófono especial. El asistente habla y registra lo dicho como
 * contexto pendiente del hilo unificado (GraphApp.notePendingVoice); el usuario contesta por
 * CUALQUIER vía de siempre (tocar la carita → panel, texto o micrófono, esquinas…) y ese "sí,
 * hazlo" llega al motor CON el contexto de la propuesta. La propuesta SIEMPRE se dice — aunque el
 * usuario ya haya cambiado de pantalla — porque nombra su referente concreto ("en ese chat con
 * Julián…"), así se entiende de qué habla sin estar viéndolo. Y la llamada corre SIN thinking del
 * modelo (el campo "reasoning" del JSON es la única cadena de pensamiento): latencia mínima.
 */
class LearningInquiry(
    private val apiKey: () -> String,
    private val model: () -> String,
    private val memories: MemoryStore,
    private val scope: CoroutineScope,
    /** true si NO es buen momento para interrumpir (ejecución en curso, mic ocupado…). */
    private val busy: () -> Boolean,
    /** Dice la propuesta/pregunta en voz alta (TTS + globo de la burbuja). */
    private val speak: (String) -> Unit,
    /** Registra lo dicho como contexto pendiente: la respuesta llega por cualquier vía de input. */
    private val pending: (kind: String, app: String, question: String, task: String) -> Unit,
) : LearningInquirer {

    @Volatile private var working = false

    private class Decision(val action: String, val question: String, val task: String)

    override fun maybeAsk(app: String, screen: String, recentClicks: List<String>, elements: List<String>) {
        if (working || busy()) return
        working = true
        scope.launch(Dispatchers.IO) {
            try {
                val d = decide(app, screen, recentClicks, elements) ?: return@launch
                LogBus.log("learn", if (d.action == "offer") "🙋 propongo: ${d.question}" else "🙋 pregunto: ${d.question}")
                speak(d.question)
                pending(d.action, app, d.question, d.task)
            } finally {
                working = false
            }
        }
    }

    /**
     * Cadena de pensamiento CORTÍSIMA + decisión, con el conocimiento personal del usuario a la
     * vista. Prefiere el silencio; jamás preguntas obvias. "offer" si ve algo CONCRETO y pendiente
     * con lo que puede ayudar YA — y SIEMPRE que una instrucción del usuario aplique al escenario.
     */
    private fun decide(app: String, screen: String, clicks: List<String>, elements: List<String>): Decision? {
        val known = memories.promptBlock()
        val prompt = """
            Eres Ü, un asistente que OBSERVA al usuario usar una app de su Android (él activó tu
            modo aprendizaje). Puedes hacer tareas por él en el teléfono.

            LO QUE SABES DE ESTE USUARIO (su knowledge-base personal: datos, preferencias e
            instrucciones que él mismo te dio; las notas por app pueden usar el nombre comercial
            de la app aunque abajo veas el paquete Android):
            ${known.ifBlank { "(aún no sabes nada de él)" }}

            Esto acaba de pasar HACE SEGUNDOS:
            App en primer plano (paquete Android): $app
            Pantalla: $screen
            Lo que tocó (en orden): ${clicks.joinToString(" → ").ifBlank { "(nada)" }}
            Elementos visibles: ${elements.take(30).joinToString(", ").ifBlank { "(ninguno)" }}

            PIENSA PRIMERO (campo "reasoning", UNA frase brevísima) y decide UNA de tres:
            - "offer" — MINDSET PROPOSITIVO: estás viendo EN VIVO lo que él hace; detecta una
              oportunidad real de hacer TÚ algo que a él le consumiría tiempo y propón hacerlo por
              él. Dos fuentes, en este orden:
              1) SUS INSTRUCCIONES de arriba: si alguna pide que seas propositivo en un escenario y
                 lo que ves en pantalla ES ese escenario (p.ej. "si en WhatsApp un usuario me pide
                 una mejora, propón hacerla tú"), esa instrucción MANDA: propónlo tal como él lo
                 pidió, sin timidez.
              2) Lo evidente y pendiente en pantalla (un mensaje que debe contestar, algo a medio
                 hacer, un trámite repetitivo) — solo si es claramente útil; nunca por cortesía.
              La propuesta ("question") debe NOMBRAR SU REFERENTE CONCRETO — el chat, el correo, la
              persona, la app: "En ese chat con Julián veo que te pidió X, ¿quieres que lo haga yo?"
              — porque el usuario puede haber cambiado de pantalla cuando la oiga y debe entender de
              qué le hablas SIN estar viéndolo. Define en "task" la tarea exacta e imperativa que
              harías, con los detalles concretos que ves (nombres, textos, chat exacto).
            - "ask": tienes una duda que DE VERDAD te ayudaría a hacer bien esta tarea después y que
              no puedes inferir (un dato personal que solo él sabe). JAMÁS preguntes algo que ya
              esté en lo que sabes de él. El ORDEN en que hace las cosas casi nunca es una regla:
              NO se pregunta. Jamás preguntes lo obvio.
            - "none": silencio. Ante la duda, "none": es mejor callar que molestar — SALVO que una
              instrucción suya aplique al escenario actual: ahí callar es fallarle.

            Responde SOLO JSON:
            {"reasoning": "una frase", "action": "offer|ask|none", "question": "lo que dirías por voz o vacío", "task": "instrucción imperativa si action=offer, si no vacío"}
        """.trimIndent()
        return runCatching {
            // thinkingBudget = 0: sin razonamiento interno del modelo — la decisión debe salir en
            // ~1-2 s para que la propuesta llegue mientras el momento sigue vivo.
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "learn", thinkingBudget = 0)
            val reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val action = o["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val question = o["question"]?.jsonPrimitive?.contentOrNull.orEmpty()
            LogBus.log("learn", "🤔 ${reasoning.take(120)} · $action")
            if (action !in setOf("offer", "ask") || question.isBlank()) null
            else Decision(action, question, o["task"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.getOrElse { LogBus.log("learn", "razonamiento falló: ${it.message}"); null }
    }
}
