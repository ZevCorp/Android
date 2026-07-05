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
    /** Señal de vía (consciente vs subconsciente) para la plataforma; solo importa cuando cambia. */
    private val mode: ExecutionMode? = null,
    /** Pausa entre steps enviados juntos en un mismo turno (ajustable desde la app). */
    private val stepDelay: () -> Long = { 350 },
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
        var wantShot = false // el screenshot solo se adjunta cuando el modelo va a usar computer-use
        while (turns < maxTurns) {
            turns++
            val turnStart = TimeSource.Monotonic.markNow()
            val state = phone.state(withScreenshot = wantShot)
            val turn = b.next(state, results)
            wantShot = turn.needsScreenshot
            val ms = turnStart.elapsedNow().inWholeMilliseconds
            val via = if (state.screenshotPng != null) "👁 imagen" else "📝 texto"
            val decided = when {
                turn.actions.isNotEmpty() -> turn.actions.joinToString(", ") { describe(it) }
                turn.question != null -> "pregunta"
                else -> "fin"
            }
            log.log("run", "turno $turns · ${ms}ms · $via · \"${state.screen.take(36)}\" · decide: $decided")

            if (turn.narration.isNotBlank()) voice.narrate(turn.narration)
            if (turn.speech != null) { voice.speak(turn.speech); log.log("run", "🗣 ${turn.speech}") }
            if (turn.text.isNotBlank()) summary = turn.text
            if (turn.done) break

            val out = mutableListOf<String>()
            turn.actions.forEachIndexed { i, action ->
                turn.intents.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { voice.narrate(it) }
                out += execute(action)
                actions++
                if (turn.actions.size > 1) delay(stepDelay()) // deja asentar la UI entre gestos encadenados
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
        // Aviso de vía: MCP = subconsciente, computer-use = consciente (Wait no cambia de vía).
        if (action !is AgentAction.Wait) mode?.executing(action is AgentAction.Mcp)
        // Las MCP devuelven su propio detalle de fallo (p.ej. qué taps de una aprendida no salieron):
        // se conserva tal cual para el log y para que el modelo pueda corregir en el siguiente turno.
        val result = when (action) {
            is AgentAction.Mcp -> mcp.call(action.tool, action.args)
            is AgentAction.Tap -> phone.tap(action.x, action.y).asResult()
            is AgentAction.Type -> phone.type(action.x, action.y, action.text).asResult()
            is AgentAction.OpenApp -> phone.openApp(action.name).asResult()
            is AgentAction.Scroll -> phone.scroll(action.down).asResult()
            is AgentAction.Swipe -> phone.swipe(action.x1, action.y1, action.x2, action.y2, action.ms).asResult()
            is AgentAction.Key -> phone.pressKey(action.key).asResult()
            is AgentAction.Wait -> { delay(action.ms); "ok" }
        }
        log.log("run", "  ▪ ${describe(action)} → $result")
        return result
    }

    private fun Boolean.asResult() = if (this) "ok" else "no se pudo ejecutar la acción"

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
