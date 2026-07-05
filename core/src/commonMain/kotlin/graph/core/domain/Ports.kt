package graph.core.domain

/* ---------- Superficie del teléfono (una implementación por plataforma) ---------- */

/** Primitivas de computer-use: el modelo mira la pantalla y actúa por coordenadas 0..width/height. */
interface Phone {
    /** Estado de la pantalla. Solo captura el screenshot si `withScreenshot` (para computer-use). */
    suspend fun state(withScreenshot: Boolean = false): ScreenState
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun type(x: Int, y: Int, text: String): Boolean
    suspend fun openApp(query: String): Boolean
    suspend fun scroll(down: Boolean): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean
    suspend fun pressKey(key: String): Boolean
}

/** Gestos semánticos de navegación, expuestos como herramientas MCP (ver `Mcp`). */
interface Gestures {
    suspend fun home(): Boolean
    suspend fun appDrawer(): Boolean
    suspend fun notifications(): Boolean
    suspend fun panHome(right: Boolean): Boolean
    suspend fun scrollMenu(down: Boolean): Boolean
}

/**
 * Acciones "genéricas" del teléfono ejecutadas por Intents/APIs de Android (sin navegar la interfaz).
 * Cada una se expone como herramienta MCP. Devuelven true si se lanzó la acción correctamente.
 */
interface SystemApi {
    suspend fun openApp(name: String): Boolean
    suspend fun setAlarm(hour: Int, minute: Int, message: String): Boolean
    suspend fun setTimer(seconds: Int, message: String): Boolean
    suspend fun showAlarms(): Boolean
    suspend fun createEvent(title: String, startIso: String, location: String): Boolean
    suspend fun dial(number: String): Boolean
    suspend fun call(number: String): Boolean
    suspend fun sendSms(number: String, message: String): Boolean
    suspend fun sendEmail(to: String, subject: String, body: String): Boolean
    suspend fun webSearch(query: String): Boolean
    suspend fun openUrl(url: String): Boolean
    suspend fun maps(query: String): Boolean
    suspend fun directions(destination: String): Boolean
    suspend fun openCamera(): Boolean
    suspend fun openSettings(section: String): Boolean
    suspend fun shareText(text: String): Boolean
    suspend fun setClipboard(text: String): Boolean
}

/* ---------- El cerebro (Gemini 3.5 Flash): computer-use + herramientas MCP ---------- */

/** Una acción que el cerebro decide ejecutar en un turno. */
sealed interface AgentAction {
    class Tap(val x: Int, val y: Int) : AgentAction
    class Type(val x: Int, val y: Int, val text: String) : AgentAction
    class OpenApp(val name: String) : AgentAction
    class Scroll(val down: Boolean) : AgentAction
    class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Long) : AgentAction
    class Key(val key: String) : AgentAction
    class Wait(val ms: Long) : AgentAction

    /** Llamada a una herramienta MCP (gesto declarado). */
    class Mcp(val tool: String, val args: Map<String, String>) : AgentAction
}

/** Lo que el cerebro devuelve en un turno: acciones + narración/voz/pregunta, o fin con texto. */
class BrainTurn(
    val actions: List<AgentAction> = emptyList(),
    val question: String? = null,
    val done: Boolean = false,
    val text: String = "",
    /** El modelo va a usar computer-use: el próximo turno debe adjuntar un screenshot. */
    val needsScreenshot: Boolean = false,
    /** Globo de diálogo con personalidad (narración silenciosa). */
    val narration: String = "",
    /** Frase para decir en voz alta (solo cosas importantes). */
    val speech: String? = null,
    /** Intención por acción, para narrar en tiempo real cada paso. */
    val intents: List<String> = emptyList(),
)

/**
 * El modelo con computer-use nativo (Gemini 3.5 Flash) que además conoce las herramientas MCP y decide,
 * turno a turno, si usar un gesto MCP (rápido y limpio) o computer-use (visual y flexible).
 */
interface Brain {
    fun begin(goal: String)
    suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn
    /** Inyecta una respuesta del usuario u otra instrucción para el siguiente turno. */
    fun inform(message: String)
}

/* ---------- Canales hacia el usuario ---------- */

/** Voz/narración del asistente (TTS + globo de diálogo). Solo se usa para lo importante. */
interface Voice {
    fun narrate(text: String)
    fun speak(text: String)
}

/** El asistente puede preguntar algo al usuario (respuesta por texto o voz). */
interface UserChannel {
    suspend fun ask(question: String): String
}

/** Log estructurado del núcleo; cada plataforma decide dónde mostrarlo. */
fun interface GraphLog {
    fun log(tag: String, message: String)
}
