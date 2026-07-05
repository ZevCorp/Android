package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.delay

private val NO_LOG = GraphLog { _, _ -> }

/**
 * SESIÓN DE ENSEÑANZA. El asistente ve TODO el árbol de UI; la voz y los clics del usuario son
 * SEÑALES para generalizar (tocas un par de números y él entiende todos). Conversa: pregunta
 * iluminando elementos ("¿este es el de borrar?"), demuestra secuencias y las prueba con permiso.
 * Al cerrar, estructura el mapa completo de la pantalla como herramienta MCP documentada.
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
        voice.speak("Te escucho. Muéstrame y explícame; tus toques también me sirven de pista.")
        log.log("learn", "▶ sesión de enseñanza iniciada")
        surface.setCapturing(true)
        val transcript = StringBuilder()
        try {
            while (true) {
                val heard = teacher.listen() ?: break // ✅ = terminar
                val clicks = surface.drainClicks()
                if (heard.isBlank() && clicks.isEmpty()) continue
                if (heard.isNotBlank()) transcript.append("Usuario dijo: \"$heard\". ")
                if (clicks.isNotEmpty()) transcript.append("Usuario tocó: ${clicks.joinToString(", ")}. ")
                log.log("learn", "señal · voz=\"${heard.take(60)}\" · clics=${clicks.joinToString(",")}")

                val turn = brain.step(transcript.toString(), surface.screen(), surface.elements())
                if (turn.highlight.isNotEmpty()) {
                    log.log("learn", "ilumino: ${turn.highlight.joinToString(" · ")}")
                    if (turn.say.isNotBlank()) voice.speak(turn.say)
                    surface.highlight(turn.highlight)
                } else if (turn.say.isNotBlank()) voice.speak(turn.say)

                turn.question?.takeIf { it.isNotBlank() }?.let { q ->
                    val yes = teacher.confirm(q)
                    transcript.append("Pregunté \"$q\" y el usuario respondió ${if (yes) "SÍ" else "NO"}. ")
                    log.log("learn", "❓ $q → ${if (yes) "sí" else "no"}")
                }

                if (turn.test.isNotEmpty() && teacher.confirm("¿Lo hago para probar?")) {
                    var ok = true
                    for (label in turn.test) {
                        if (!surface.tapLabel(label)) ok = false
                        delay(450)
                    }
                    transcript.append("Probé la secuencia ${turn.test.joinToString(",")} y ${if (ok) "salió bien" else "algo no encontré"}. ")
                    voice.speak(if (ok) "¡Salió! ✨" else "Mmm, algo no encontré; sigo aprendiendo.")
                    log.log("learn", "prueba ${turn.test.joinToString(",")} · ok=$ok")
                }
            }
        } finally {
            surface.setCapturing(false)
        }

        if (transcript.isBlank()) {
            voice.speak("No alcancé a aprender nada esta vez.")
            return "Sesión sin señales"
        }
        val tool = brain.consolidate(transcript.toString(), surface.screen(), surface.elements())
        repo.save(tool)
        voice.speak("Aprendí \"${tool.name}\" con ${tool.elements.size} elementos. Pídemelo cuando quieras.")
        log.log("learn", "■ aprendido: ${tool.name} · ${tool.elements.size} elementos")
        return "Aprendí: ${tool.name} (${tool.elements.size} elementos)"
    }
}
