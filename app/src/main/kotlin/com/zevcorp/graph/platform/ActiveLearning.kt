package com.zevcorp.graph.platform

import android.content.Intent
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.ui.ScreenTeachActivity
import graph.core.domain.Voice
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * APRENDIZAJE ACTIVO (el 🎓 de la burbuja, al TOCARLO). El usuario comparte su pantalla y le enseña
 * al asistente, con su voz, cómo usa sus apps. A diferencia del pasivo, aquí SÍ se comparte pantalla:
 * se graba video + audio (ScreenTeachService) y al terminar TODO el video se procesa con Gemini
 * (GeminiVideo) y se estructura como conocimiento textual por app en la capa MCP (MemoryStore).
 *
 * Fase 1: no estructura el árbol de UI, solo texto. Como el micrófono está ocupado grabando, las
 * preguntas del asistente se hacen por voz AL FINAL (mic ya libre), tal como pidió el usuario.
 */
class ActiveLearning(
    private val app: GraphApp,
    private val video: GeminiVideo,
    private val memories: MemoryStore,
    private val voice: Voice,
    private val askAloud: suspend (String) -> String,
    private val apiKey: () -> String,
    private val model: () -> String,
) {
    /** El usuario pidió aprender y aún estamos grabando (o pidiendo el permiso de captura). */
    @Volatile var active = false
        private set

    /** Terminó la grabación y estamos estructurando el video con Gemini. */
    @Volatile var processing = false
        private set

    /** El 🎓 se pinta activo y el aprendizaje pasivo por voz se calla mientras esto sea true. */
    val busy get() = active || processing

    /** Toque del 🎓: arranca el aprendizaje activo o lo detona si ya estaba grabando/procesando. */
    fun toggle() {
        if (busy) stop() else start()
    }

    private fun start() {
        if (app.ui == null) { voice.narrate("Activa la accesibilidad de Graph primero"); return }
        active = true
        LogBus.log("teach", "🎬 aprendizaje activo: pidiendo compartir pantalla")
        runCatching {
            app.startActivity(Intent(app, ScreenTeachActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { active = false; voice.narrate("No pude iniciar el compartir pantalla") }
    }

    private fun stop() {
        if (active) {
            active = false
            app.startService(Intent(app, ScreenTeachService::class.java).setAction(ScreenTeachService.ACTION_STOP))
        }
    }

    /** ScreenTeachActivity avisa que el usuario aceptó compartir y la grabación arrancó. */
    fun onStarted() {
        active = true
        LogBus.log("teach", "▶ aprendizaje activo en curso")
        voice.speak("🎬 Enséñame lo que quieras; vuelve a tocar el 🎓 cuando termines.")
    }

    /** El usuario canceló el diálogo de compartir pantalla. */
    fun onCancelled() {
        active = false
        LogBus.log("teach", "aprendizaje activo cancelado por el usuario")
    }

    /** El servicio terminó de grabar: `file` = el mp4 (o null si no se grabó nada). */
    fun onRecorded(file: File?) {
        active = false
        if (processing) return
        processing = true
        app.scope.launch(Dispatchers.IO) {
            try {
                if (file == null) { voice.speak("No pude grabar la pantalla, ¿lo intentamos de nuevo?"); return@launch }
                voice.narrate("🧠 Estoy estudiando lo que me enseñaste…")
                val result = video.structure(file)
                runCatching { file.delete() }
                var saved = 0
                result.notes.forEach { note ->
                    if (memories.add(note)) {
                        saved++
                        LogBus.log("teach", "🧩 aprendido${if (note.app.isNotBlank()) " [${note.app}]" else ""}: ${note.note}")
                    }
                }
                // EXPLICA en voz alta lo que entendió del video (no un genérico "aprendí 1 cosa"):
                // usa el resumen del modelo o, si no vino, arma uno con las notas guardadas.
                val explanation = result.summary.ifBlank {
                    when {
                        result.notes.isNotEmpty() -> "Esto entendí: " + result.notes.joinToString(". ") { it.note }
                        else -> "No logré sacar nada seguro esta vez; muéstramelo con más calma cuando quieras."
                    }
                }
                voice.speak(explanation)
                // Preguntas de seguimiento por voz (el micrófono ya está libre tras la grabación).
                for (q in result.questions.take(3)) {
                    val answer = runCatching { askAloud(q) }.getOrElse { "" }
                    if (answer.isBlank()) continue
                    distill(q, answer)?.let { if (memories.add(it)) saved++ }
                }
                if (saved > 0) LogBus.log("teach", "✅ ${saved} aprendizaje(s) nuevos guardados")
            } finally {
                processing = false
            }
        }
    }

    /** Convierte una pregunta de seguimiento y su respuesta en una nota durable por app. */
    private fun distill(question: String, answer: String): MemoryNote? {
        val prompt = """
            Durante el aprendizaje activo, Graph le preguntó al usuario y este respondió por voz.
            Destila una regla o preferencia DURABLE y auto-contenida que Graph deba recordar para operar
            bien esa app después. UNA frase imperativa, con los nombres/datos concretos, sin relleno.
            Si la respuesta no aporta nada reutilizable, worth=false.

            Pregunta de Graph: "$question"
            Respuesta del usuario: "$answer"
            Responde SOLO JSON: {"worth": true/false, "app": "nombre de la app o ''", "note": "regla en una frase"}
        """.trimIndent()
        return runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "teach")
            if (o["worth"]?.jsonPrimitive?.booleanOrNull != true) null
            else MemoryNote(
                app = o["app"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                note = o["note"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ).takeIf { it.note.isNotBlank() }
        }.getOrElse { LogBus.log("teach", "destilar respuesta falló: ${it.message}"); null }
    }
}
