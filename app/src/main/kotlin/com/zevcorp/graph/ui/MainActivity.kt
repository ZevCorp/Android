package com.zevcorp.graph.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.View
import android.widget.*
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
    private lateinit var btnTeach: Button
    private lateinit var btnEndDemo: Button
    private var teaching = false
    private var demoEnd: CompletableDeferred<Unit>? = null
    private var voiceCallback: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun section(text: String) = root.addView(TextView(this).apply {
            this.text = text; textSize = 16f; setTypeface(null, Typeface.BOLD); setPadding(0, pad, 0, 8)
        })

        val keyInput = EditText(this).apply {
            hint = "Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(app.prefs.getString("apiKey", ""))
        }
        root.addView(keyInput)
        root.addView(Button(this).apply {
            text = "Guardar API key"
            setOnClickListener {
                app.prefs.edit().putString("apiKey", keyInput.text.toString().trim()).apply()
                log("API key guardada")
            }
        })
        root.addView(Button(this).apply {
            text = "Activar servicio de accesibilidad"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        section("1 · Teaching — enséñale grabando tu pantalla")
        btnTeach = Button(this).apply { text = "⏺ Grabar tutorial"; setOnClickListener { toggleTeach() } }
        root.addView(btnTeach)

        section("2 · Learning — Gemini lo ejecuta y aprende el workflow")
        lessonsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(lessonsPanel)
        btnEndDemo = Button(this).apply {
            text = "✅ Terminar demostración"
            visibility = View.GONE
            setOnClickListener {
                visibility = View.GONE
                moveTaskToBack(true)
                scope.launch { delay(400); demoEnd?.complete(Unit) }
            }
        }
        root.addView(btnEndDemo)

        section("3 · Subconsciente — ejecuta workflows sin LLM")
        workflowsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(workflowsPanel)
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "Desde la terminal: cli/graph run <wf_id> --input_N=\"valor\" (ver CLI.md)"
        })

        section("Registro")
        logView = TextView(this).apply { textSize = 12f }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS), 3)
        refresh()
    }

    private fun log(message: String) = runOnUiThread {
        logView.text = "• $message\n${logView.text}".take(4000)
    }

    private fun refresh() = scope.launch {
        val lessons = withContext(Dispatchers.IO) { app.lessons.list() }
        val workflows = withContext(Dispatchers.IO) { app.workflows.list() }
        lessonsPanel.removeAllViews()
        lessons.forEach { lesson ->
            lessonsPanel.addView(Button(this@MainActivity).apply {
                text = "🎓 Aprender: ${lesson.goal.take(48)}"
                setOnClickListener { learn(lesson) }
            })
        }
        workflowsPanel.removeAllViews()
        workflows.forEach { w ->
            workflowsPanel.addView(Button(this@MainActivity).apply {
                text = "⚡ ${w.id} · ${w.name.take(38)}"
                setOnClickListener {
                    moveTaskToBack(true)
                    scope.launch { log(app.runWorkflow(w.id, emptyMap())) }
                }
            })
        }
    }

    /* ---------- Etapa 1 ---------- */

    private fun toggleTeach() {
        if (!teaching) {
            startActivityForResult(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
        } else {
            teaching = false
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
                teaching = true
                btnTeach.text = "⏹ Detener y analizar"
                log("Grabando. Enseña la tarea y vuelve aquí para detener.")
                moveTaskToBack(true)
            }
            2 -> {
                val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
                voiceCallback?.invoke(text)
                voiceCallback = null
            }
        }
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
                    btnEndDemo.visibility = View.VISIBLE
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
