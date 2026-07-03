package graph.core.domain

import kotlinx.coroutines.flow.Flow

/* ---------- Etapa 1 · Teaching ---------- */

interface ScreenRecorder {
    fun start()
    /** Detiene la grabación y devuelve la ruta del video. */
    suspend fun stop(): String
}

interface TutorialAnalyzer {
    suspend fun analyze(videoPath: String): Lesson
}

/**
 * Capa de inteligencia previa al guardado: con el contexto del video (incluida la narración del
 * usuario sobre qué es opcional) organiza los steps capturados en TRONCO y RAMAS situacionales.
 */
interface WorkflowCurator {
    suspend fun curate(lesson: Lesson, workflow: Workflow): Workflow
}

/* ---------- Superficie de UI (una implementación por plataforma) ---------- */

class ScreenState(
    val screen: String,
    val width: Int,
    val height: Int,
    val screenshotPng: ByteArray? = null,
)

interface UiSurface {
    suspend fun state(): ScreenState

    /** Ejecución semántica de un step grabado (etapa subconsciente). */
    suspend fun perform(step: Step, value: String): Boolean

    /** Ejecutan la acción y devuelven el step semántico resuelto desde el árbol de UI (para grabar el workflow). */
    suspend fun tapAt(x: Int, y: Int): Step?
    suspend fun typeAt(x: Int, y: Int, text: String): Step?
    suspend fun launch(query: String): Step?
    suspend fun scroll(down: Boolean): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean
    fun pressKey(key: String): Boolean

    /** Acciones del usuario capturadas del árbol de UI (demos durante Learning). */
    val userActions: Flow<Step>
    fun setCapturing(enabled: Boolean)
}

/* ---------- Etapa 2 · Learning (computer use) ---------- */

sealed interface AgentAction {
    class ClickAt(val x: Int, val y: Int) : AgentAction
    class TypeAt(val x: Int, val y: Int, val text: String) : AgentAction
    class Scroll(val down: Boolean) : AgentAction
    class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Long) : AgentAction
    class OpenApp(val name: String) : AgentAction
    class Key(val key: String) : AgentAction
    class Wait(val ms: Long) : AgentAction
}

class BrainTurn(
    val actions: List<AgentAction> = emptyList(),
    val question: String? = null,
    val done: Boolean = false,
    val text: String = "",
    /** Narración con personalidad de lo que hará/piensa (globo de diálogo de la carita). */
    val narration: String = "",
    /** Frase que el asistente quiere DECIR en voz alta (solo cosas importantes). */
    val speech: String? = null,
    /** intent por acción, para narrar en tiempo real cada paso. */
    val intents: List<String> = emptyList(),
)

class Verdict(val valid: Boolean, val reason: String = "", val applicable: Boolean = true)

/** El modelo con computer use (Gemini 3.5 Flash en Android). Mantiene su propia conversación. */
interface ComputerUseBrain {
    fun begin(lesson: Lesson, instructions: String = "")
    suspend fun next(state: ScreenState, actionResults: List<String>): BrainTurn
    /** Respuesta del usuario (texto/voz) o resumen de su demostración. */
    fun inform(message: String)
    /** Supervisor del learning: juzga si un step reproducido por árbol de UI quedó bien aplicado. */
    suspend fun judge(goal: String, step: Step, performed: Boolean, state: ScreenState): Verdict
    /**
     * Observador en vivo durante el Teaching: mira la pantalla mientras el usuario enseña y puede
     * hablar (speak) o preguntar algo importante (ask_user). No ejecuta acciones. Devuelve null si no
     * tiene nada que decir en este momento.
     */
    suspend fun observe(lesson: Lesson, state: ScreenState, recentActions: List<String>): BrainTurn?
}

/**
 * Canal de voz/narración del asistente hacia el usuario (TTS + globo de diálogo).
 * Disponible en cualquier etapa; la voz se usa solo para lo importante.
 */
interface Voice {
    /** Globo de diálogo silencioso (narración con personalidad del paso actual). */
    fun narrate(text: String)
    /** Habla en voz alta (y también muestra el globo). */
    fun speak(text: String)
}

/** Reporta el estado de la ejecución en vivo (dashboard en tiempo real + narración). */
fun interface RunReporter {
    fun report(workflow: Workflow, activeOrder: Int, note: String)
}

/* ---------- Feedback del usuario durante Learning ---------- */

class Answer(val text: String = "", val demo: Boolean = false)

interface UserChannel {
    suspend fun ask(question: String): Answer
    suspend fun awaitDemoEnd()
}

/** Log estructurado del núcleo; cada plataforma decide dónde mostrarlo (panel de desarrollador en Android). */
fun interface GraphLog {
    fun log(tag: String, message: String)
}

/* ---------- Persistencia ---------- */

interface WorkflowRepository {
    fun save(workflow: Workflow)
    fun get(id: String): Workflow?
    fun list(): List<Workflow>
}

interface LessonRepository {
    fun save(lesson: Lesson)
    fun list(): List<Lesson>
}
