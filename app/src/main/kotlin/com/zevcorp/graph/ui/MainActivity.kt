package com.zevcorp.graph.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.RecorderService
import graph.core.domain.Answer
import graph.core.domain.Lesson
import graph.core.domain.UserChannel
import kotlin.coroutines.resume
import kotlinx.coroutines.*

class MainActivity : Activity(), UserChannel {

    private val app get() = GraphApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var logView: TextView
    private lateinit var lessonsPanel: LinearLayout
    private lateinit var workflowsPanel: LinearLayout
    private lateinit var btnTeach: TextView
    private lateinit var btnEndDemo: View
    private var demoEnd: CompletableDeferred<Unit>? = null
    private var voiceCallback: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.bg
        window.navigationBarColor = Palette.bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bg)
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }

        // Header: carita + nombre
        val header = row()
        header.addView(FaceView(this), LinearLayout.LayoutParams(dp(56), dp(56)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(title("Graph", 22f))
        titles.addView(caption("Te ve enseñar · aprende haciendo · lo hace en automático"))
        header.addView(titles)
        root.addView(header)
        root.gap(dp(16))

        // Card 0: configuración
        val setup = card()
        val keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(app.prefs.getString("apiKey", GraphApp.DEFAULT_API_KEY))
            setTextColor(Palette.text)
            setHintTextColor(Palette.textDim)
            background = rounded(Palette.bg, dp(12).toFloat(), Palette.cardBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        setup.addView(keyInput)
        setup.gap(dp(8))
        val setupRow = row()
        setupRow.addView(button("Guardar key") {
            app.prefs.edit().putString("apiKey", keyInput.text.toString().trim()).apply()
            log("API key guardada")
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setupRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        setupRow.addView(button("Accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setup.addView(setupRow)
        setup.gap(dp(6))
        setup.addView(caption("Al activar accesibilidad aparece la burbuja flotante: Graph te acompaña en cualquier app."))
        root.addView(setup)
        root.gap(dp(14))

        // Card 1: Teaching
        root.addView(stageCard("1", "Teaching", "Graba tu pantalla y nárralo: Graph entiende qué enseñaste.") { c ->
            btnTeach = button("⏺ Grabar tutorial", primary = true) { toggleTeach() }
            c.addView(btnTeach)
        })
        root.gap(dp(14))

        // Card 2: Learning
        root.addView(stageCard("2", "Learning", "Gemini 3.5 Flash lo ejecuta con computer use y graba el workflow. Si duda, te pregunta (texto, voz o demo).") { c ->
            lessonsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(lessonsPanel)
            btnEndDemo = button("✅ Terminar demostración", primary = true) {
                (btnEndDemo as View).visibility = View.GONE
                moveTaskToBack(true)
                scope.launch { delay(400); demoEnd?.complete(Unit) }
            }.apply { visibility = View.GONE }
            c.addView(btnEndDemo)
        })
        root.gap(dp(14))

        // Card 3: Subconsciente
        root.addView(stageCard("3", "Subconsciente", "Workflows aprendidos, sin LLM: rápidos, baratos y confiables. También desde la terminal (CLI.md).") { c ->
            workflowsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(workflowsPanel)
        })
        root.gap(dp(14))

        // Registro
        val logCard = card()
        logCard.addView(caption("REGISTRO"))
        logCard.gap(dp(6))
        logView = TextView(this).apply { textSize = 12f; setTextColor(Palette.textDim) }
        logCard.addView(logView)
        root.addView(logCard)

        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.bg); addView(root) })
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS), 3)
        refresh()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** La burbuja flotante pide iniciar la grabación (el consentimiento de MediaProjection exige una Activity). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "teach" && !app.teachingActive) toggleTeach()
    }

    private fun stageCard(number: String, name: String, description: String, content: (LinearLayout) -> Unit): LinearLayout {
        val c = card()
        val head = row()
        head.addView(pill(" $number "))
        head.addView(title(name).apply { setPadding(dp(8), 0, 0, 0) })
        c.addView(head)
        c.gap(dp(4))
        c.addView(caption(description))
        c.gap(dp(10))
        content(c)
        return c
    }

    private fun log(message: String) = runOnUiThread {
        logView.text = "• $message\n${logView.text}".take(4000)
    }

    private fun refresh() = scope.launch {
        val lessons = withContext(Dispatchers.IO) { app.lessons.list() }
        val workflows = withContext(Dispatchers.IO) { app.workflows.list() }
        lessonsPanel.removeAllViews()
        lessons.reversed().forEach { lesson ->
            lessonsPanel.addView(button("🎓 ${lesson.goal.take(44)}") { learn(lesson) })
            lessonsPanel.gap(dp(8))
        }
        if (lessons.isEmpty()) lessonsPanel.addView(caption("Aún no hay lecciones: graba tu primer tutorial."))
        workflowsPanel.removeAllViews()
        workflows.reversed().forEach { w ->
            workflowsPanel.addView(button("⚡ ${w.name.take(40)} · ${w.steps.size} steps") {
                moveTaskToBack(true)
                scope.launch { log(app.runWorkflow(w.id, emptyMap())) }
            })
            workflowsPanel.gap(dp(8))
        }
        if (workflows.isEmpty()) workflowsPanel.addView(caption("Los workflows aparecen al completar un Learning."))
    }

    /* ---------- Etapa 1 ---------- */

    private fun toggleTeach() {
        if (!app.teachingActive) {
            startActivityForResult(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
        } else {
            app.teachingActive = false
            btnTeach.text = "⏺ Grabar tutorial"
            scope.launch {
                log("Analizando el video con Gemini…")
                runCatching { app.teaching.stopAndAnalyze() }
                    .onSuccess { log("Lección aprendida: ${it.goal}"); refresh() }
                    .onFailure { log("Error al analizar: ${it.message}") }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            1 -> if (resultCode == RESULT_OK && data != null) {
                RecorderService.grantCode = resultCode
                RecorderService.grantData = data
                app.teaching.startRecording()
                app.teachingActive = true
                btnTeach.text = "⏹ Detener y analizar"
                Toast.makeText(this, "Grabando: enseña la tarea y detén desde aquí o desde la burbuja", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
            2 -> {
                val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
                voiceCallback?.invoke(text)
                voiceCallback = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::btnTeach.isInitialized)
            btnTeach.text = if (app.teachingActive) "⏹ Detener y analizar" else "⏺ Grabar tutorial"
    }

    /* ---------- Etapa 2 ---------- */

    private fun learn(lesson: Lesson) {
        if (app.ui == null) {
            log("Primero activa el servicio de accesibilidad de Graph")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        log("Learning: ${lesson.goal}")
        moveTaskToBack(true)
        scope.launch {
            runCatching { app.learning(this@MainActivity).run(lesson) }
                .onSuccess { log("Workflow guardado: ${it.id} · ${it.steps.size} steps · ${it.variables.size} variables"); refresh() }
                .onFailure { log("Error en learning: ${it.message}") }
        }
    }

    /* ---------- UserChannel: dudas del asistente → texto, voz o demostración ---------- */

    override suspend fun ask(question: String): Answer = suspendCancellableCoroutine { cont ->
        runOnUiThread {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            val input = EditText(this).apply { hint = "Tu respuesta…" }
            AlertDialog.Builder(this)
                .setTitle("El asistente tiene una duda")
                .setMessage(question)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("Responder") { _, _ ->
                    moveTaskToBack(true)
                    cont.resume(Answer(input.text.toString()))
                }
                .setNegativeButton("🎤 Voz") { _, _ ->
                    voiceCallback = { text -> moveTaskToBack(true); cont.resume(Answer(text)) }
                    startActivityForResult(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 2)
                }
                .setNeutralButton("👋 Te lo muestro") { _, _ ->
                    demoEnd = CompletableDeferred()
                    (btnEndDemo as View).visibility = View.VISIBLE
                    Toast.makeText(this, "Hazlo tú en el teléfono y vuelve a Graph → Terminar demostración", Toast.LENGTH_LONG).show()
                    cont.resume(Answer(demo = true))
                }
                .show()
        }
    }

    override suspend fun awaitDemoEnd() {
        demoEnd?.await()
        demoEnd = null
    }
}
