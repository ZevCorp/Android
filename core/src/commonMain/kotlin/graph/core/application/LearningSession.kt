package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.delay

private val NO_LOG = GraphLog { _, _ -> }

/**
 * SESIÓN DE ENSEÑANZA. El usuario muestra y explica algo; el asistente lee el árbol de UI en vivo y,
 * cuando entiende una secuencia concreta, INTERRUMPE: la ilumina ("mira cómo lo haría"), pide
 * confirmación, se ofrece a probarla y, al terminar, la organiza en una herramienta MCP.
 *
 * Mantenido simple a propósito (v1): guarda la última secuencia confirmada y la consolida al cierre.
 */
class LearningSession(
    private val brain: LearningBrain,
    private val surface: LearningSurface,
    private val teacher: Teacher,
    private val voice: Voice,
    private val repo: LearnedToolRepository,
    private val log: GraphLog = NO_LOG,
) {
    suspend fun run(): String {
        voice.speak("Te escucho. Muéstrame y explícame lo que quieres enseñarme.")
        log.log("learn", "▶ sesión de enseñanza iniciada")
        val explanation = StringBuilder()
        var confirmed: List<String> = emptyList()

        while (true) {
            val chunk = teacher.listen() ?: break // el usuario terminó
            if (chunk.isBlank()) continue
            explanation.append(chunk).append(". ")
            log.log("learn", "usuario: $chunk")

            val p = brain.propose(explanation.toString(), surface.screen(), surface.elements())
            if (!p.understood || p.sequence.isEmpty()) {
                if (p.say.isNotBlank()) voice.speak(p.say) // aún no entiende: sigue escuchando
                continue
            }

            log.log("learn", "entiendo \"${p.name}\": ${p.sequence.joinToString(" · ")}")
            voice.speak(p.say.ifBlank { "Ok, ya entendí. Mira cómo lo haría." })
            surface.highlight(p.sequence) // tin · tin · tin

            if (!teacher.confirm("¿Es así?")) { voice.speak("Ok, cuéntame mejor 🙂"); continue }
            confirmed = p.sequence
            if (teacher.confirm("¿Lo puedo hacer para probar?")) {
                var ok = true
                for (label in p.sequence) {
                    if (!surface.tapLabel(label)) ok = false
                    delay(450)
                }
                voice.speak(if (ok) "¡Listo! ✨" else "Casi; algo no encontré, pero lo anoté.")
                log.log("learn", "prueba ejecutada · ok=$ok")
            }
        }

        if (confirmed.isEmpty()) {
            voice.speak("No alcancé a aprender nada esta vez.")
            return "Sesión sin resultado"
        }
        val tool = brain.consolidate(explanation.toString(), confirmed)
        repo.save(tool)
        voice.speak("Aprendí \"${tool.name}\". Ya puedo hacerlo cuando me lo pidas.")
        log.log("learn", "■ aprendido: ${tool.name} (${tool.steps.size} pasos)")
        return "Aprendí: ${tool.name}"
    }
}
