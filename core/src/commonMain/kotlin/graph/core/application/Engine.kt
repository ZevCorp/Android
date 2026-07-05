package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

private val NO_LOG = GraphLog { _, _ -> }
private val NO_VOICE = object : Voice {
    override fun narrate(text: String) {}
    override fun speak(text: String) {}
}

/**
 * MOTOR DE EJECUCIÓN ÚNICO Y MIXTO.
 *
 * El usuario pide algo (texto o voz) y Gemini 3.5 Flash lo ejecuta eligiendo, en cada turno, la vía
 * más adecuada: un gesto MCP (herramienta declarada, limpia y rápida) o computer-use (mirar la
 * pantalla y tocar por coordenadas, flexible). No hay modos separados: es un solo bucle.
 */
class ExecutionEngine(
    private val brain: () -> Brain,
    private val phone: Phone,
    private val mcp: Mcp,
    private val user: UserChannel? = null,
    private val voice: Voice = NO_VOICE,
    private val log: GraphLog = NO_LOG,
    private val maxTurns: Int = 40,
) {
    /** Ejecuta un objetivo hasta que el modelo devuelve el control con texto. Devuelve ese resumen. */
    suspend fun run(goal: String): String {
        val b = brain()
        b.begin(goal)
        voice.narrate("¡Vamos! $goal")
        log.log("run", "▶ \"$goal\"")
        val started = TimeSource.Monotonic.markNow()

        var results = emptyList<String>()
        var summary = ""
        var turns = 0
        var actions = 0
        while (turns < maxTurns) {
            turns++
            val turnStart = TimeSource.Monotonic.markNow()
            val state = phone.state()
            val turn = b.next(state, results)
            val ms = turnStart.elapsedNow().inWholeMilliseconds
            val decided = when {
                turn.actions.isNotEmpty() -> turn.actions.joinToString(", ") { describe(it) }
                turn.question != null -> "pregunta"
                else -> "fin"
            }
            log.log("run", "turno $turns · ${ms}ms · pantalla \"${state.screen.take(40)}\" · decide: $decided")

            if (turn.narration.isNotBlank()) voice.narrate(turn.narration)
            if (turn.speech != null) { voice.speak(turn.speech); log.log("run", "🗣 ${turn.speech}") }
            if (turn.text.isNotBlank()) summary = turn.text
            if (turn.done) break

            val out = mutableListOf<String>()
            turn.actions.forEachIndexed { i, action ->
                turn.intents.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { voice.narrate(it) }
                out += execute(action)
                actions++
            }
            results = out

            turn.question?.let { q ->
                log.log("run", "❓ $q")
                voice.speak(q)
                b.inform(user?.ask(q)?.ifBlank { "usa tu mejor criterio" } ?: "No hay usuario; usa tu mejor criterio.")
            }
            delay(400)
        }

        val secs = started.elapsedNow().inWholeSeconds
        // Terminó con un mensaje (p.ej. "¿qué puedes hacer?"): dilo en voz alta.
        if (summary.isNotBlank()) {
            voice.speak(summary)
        } else if (actions == 0) {
            // No dijo nada y no hizo nada: al menos enumera sus capacidades.
            summary = "Puedo hacer estos gestos: " + mcp.tools.joinToString(", ") { it.name } +
                "; y además tocar y escribir en la pantalla."
            voice.speak(summary)
        }
        log.log("run", "■ ${turns} turnos · $actions acciones · ${secs}s · ${summary.take(120)}")
        voice.narrate("¡Listo! 🎉")
        return summary.ifBlank { "Hecho" }
    }

    private suspend fun execute(action: AgentAction): String {
        val ok = when (action) {
            is AgentAction.Mcp -> mcp.call(action.tool, action.args) == "ok"
            is AgentAction.Tap -> phone.tap(action.x, action.y)
            is AgentAction.Type -> phone.type(action.x, action.y, action.text)
            is AgentAction.OpenApp -> phone.openApp(action.name)
            is AgentAction.Scroll -> phone.scroll(action.down)
            is AgentAction.Swipe -> phone.swipe(action.x1, action.y1, action.x2, action.y2, action.ms)
            is AgentAction.Key -> phone.pressKey(action.key)
            is AgentAction.Wait -> { delay(action.ms); true }
        }
        log.log("run", "  ▪ ${describe(action)} → ${if (ok) "ok" else "falló"}")
        return if (ok) "ok" else "no se pudo ejecutar la acción"
    }

    /** Etiqueta legible de una acción, distinguiendo la VÍA usada (MCP vs computer-use). */
    private fun describe(a: AgentAction): String = when (a) {
        is AgentAction.Mcp -> "MCP ${a.tool}" + if (a.args.isNotEmpty()) " ${a.args}" else ""
        is AgentAction.Tap -> "computer-use tap(${a.x},${a.y})"
        is AgentAction.Type -> "computer-use type(${a.x},${a.y})=\"${a.text.take(24)}\""
        is AgentAction.OpenApp -> "computer-use open_app \"${a.name}\""
        is AgentAction.Scroll -> "computer-use scroll ${if (a.down) "down" else "up"}"
        is AgentAction.Swipe -> "computer-use swipe(${a.x1},${a.y1}→${a.x2},${a.y2})"
        is AgentAction.Key -> "computer-use key ${a.key}"
        is AgentAction.Wait -> "wait ${a.ms}ms"
    }
}
