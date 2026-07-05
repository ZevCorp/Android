package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.delay

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
        log.log("run", "▶ $goal")

        var results = emptyList<String>()
        var summary = ""
        var turns = 0
        while (turns++ < maxTurns) {
            val turn = b.next(phone.state(), results)
            if (turn.narration.isNotBlank()) voice.narrate(turn.narration)
            if (turn.speech != null) voice.speak(turn.speech)
            if (turn.text.isNotBlank()) summary = turn.text
            if (turn.done) break

            val out = mutableListOf<String>()
            turn.actions.forEachIndexed { i, action ->
                turn.intents.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { voice.narrate(it) }
                out += execute(action)
            }
            results = out

            turn.question?.let { q ->
                voice.speak(q)
                b.inform(user?.ask(q)?.ifBlank { "usa tu mejor criterio" } ?: "No hay usuario; usa tu mejor criterio.")
            }
            delay(500)
        }
        log.log("run", "■ $summary")
        voice.narrate("¡Listo! 🎉")
        return summary.ifBlank { "Hecho" }
    }

    private suspend fun execute(action: AgentAction): String {
        val (ok, kind) = when (action) {
            is AgentAction.Mcp -> mcp.call(action.tool, action.args).let { (it == "ok") to "mcp:${action.tool}" }
            is AgentAction.Tap -> phone.tap(action.x, action.y) to "tap"
            is AgentAction.Type -> phone.type(action.x, action.y, action.text) to "type"
            is AgentAction.OpenApp -> phone.openApp(action.name) to "open_app"
            is AgentAction.Scroll -> phone.scroll(action.down) to "scroll"
            is AgentAction.Swipe -> phone.swipe(action.x1, action.y1, action.x2, action.y2, action.ms) to "swipe"
            is AgentAction.Key -> phone.pressKey(action.key) to "key"
            is AgentAction.Wait -> { delay(action.ms); true to "wait" }
        }
        log.log("run", "· $kind → ${if (ok) "ok" else "falló"}")
        return if (ok) "ok" else "no se pudo ejecutar la acción"
    }
}
