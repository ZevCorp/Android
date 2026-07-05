package com.zevcorp.graph.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import graph.core.domain.LearnedTool
import graph.core.domain.UserChannel
import kotlin.coroutines.resume
import kotlinx.coroutines.*

private const val MAX_STEP_DELAY = 1050 // ms cuando la barra está al mínimo de velocidad

class MainActivity : Activity(), UserChannel {

    private val app get() = GraphApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var logView: TextView
    private lateinit var mcpPanel: LinearLayout
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

        // Header
        val header = row()
        header.addView(FaceView(this), LinearLayout.LayoutParams(dp(56), dp(56)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(title("Graph", 22f))
        titles.addView(caption("Pídeme algo · lo hago con gestos MCP y computer-use"))
        header.addView(titles)
        root.addView(header)
        root.gap(dp(16))

        // Config: API key + accesibilidad
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

        // Pídeme algo (texto o voz)
        val ask = card()
        ask.addView(title("Pídeme algo"))
        ask.gap(dp(4))
        ask.addView(caption("Escribe o dicta lo que quieres que haga en el teléfono."))
        ask.gap(dp(10))
        val promptInput = EditText(this).apply {
            hint = "Pídeme algo…"
            setHintTextColor(Palette.textDim); setTextColor(Palette.text); textSize = 14f
            background = rounded(Palette.bg, dp(22).toFloat(), Palette.cardBorder)
            setPadding(dp(14), dp(10), dp(14), dp(10)); maxLines = 4
        }
        fun submitPrompt() {
            val p = promptInput.text.toString().trim()
            if (p.isBlank()) return
            promptInput.setText("")
            runPrompt(p)
        }
        promptInput.setOnEditorActionListener { _, _, _ -> submitPrompt(); true }
        val bar = row()
        bar.addView(promptInput, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        bar.addView(TextView(this).apply {
            text = "🎤"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(Palette.text)
            background = rounded(Palette.card, dp(21).toFloat(), Palette.cardBorder)
            minWidth = dp(44); minHeight = dp(44)
            setOnClickListener {
                voiceCallback = { t -> if (t.isNotBlank()) runPrompt(t) }
                startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 2)
            }
        })
        bar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        bar.addView(button("➤", primary = true) { submitPrompt() })
        ask.addView(bar)
        root.addView(ask)
        root.gap(dp(14))

        // Velocidad de ejecución: pausa entre los steps de un MCP que se envían juntos
        val speed = card()
        speed.addView(title("⚡ Velocidad de ejecución"))
        speed.gap(dp(4))
        val savedDelay = app.prefs.getInt("stepDelayMs", 350)
        val speedCaption = caption("Vuelo del asistente y pausa entre clics MCP: $savedDelay ms · se aplica al instante")
        speed.addView(speedCaption)
        speed.gap(dp(8))
        val speedBar = SeekBar(this).apply {
            max = 100
            progress = ((MAX_STEP_DELAY - savedDelay) / 10).coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(Palette.accent)
            thumbTintList = ColorStateList.valueOf(Palette.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    val delay = MAX_STEP_DELAY - value * 10 // 1050..50 ms
                    app.prefs.edit().putInt("stepDelayMs", delay).apply()
                    speedCaption.text = "Vuelo del asistente y pausa entre clics MCP: $delay ms · se aplica al instante"
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        speed.addView(speedBar)
        root.addView(speed)
        root.gap(dp(14))

        // Panel de desarrollador: logs en vivo
        val dev = card()
        val devHead = row()
        devHead.addView(pill(" 🐞 "))
        devHead.addView(title("Desarrollador").apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        devHead.addView(button("Copiar") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("graph-logs", LogBus.dump()))
            Toast.makeText(this, "Logs copiados", Toast.LENGTH_SHORT).show()
        })
        devHead.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        devHead.addView(button("Limpiar") { LogBus.clear(); logView.text = "" })
        dev.addView(devHead)
        dev.gap(dp(8))
        logView = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Palette.textDim)
            setTextIsSelectable(true)
        }
        val logScroll = ScrollView(this).apply {
            addView(logView)
            background = rounded(Palette.bg, dp(12).toFloat(), Palette.cardBorder)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        dev.addView(logScroll, LinearLayout.LayoutParams(-1, dp(260)))
        root.addView(dev)
        root.gap(dp(14))

        // Panel de MCPs: lo que el asistente ha aprendido (enseñanza) + lo incorporado de fábrica
        val mcp = card()
        val mcpHead = row()
        mcpHead.addView(pill(" 🧩 "))
        mcpHead.addView(title("Herramientas MCP").apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        mcpHead.addView(button("Actualizar") { refreshMcpPanel() })
        mcp.addView(mcpHead)
        mcp.gap(dp(4))
        mcp.addView(caption("Aprendidas enseñándole (🎓) + gestos y acciones de sistema incorporados."))
        mcp.gap(dp(10))
        mcpPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mcp.addView(mcpPanel)
        root.addView(mcp)

        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.bg); addView(root) })
        requestPermissions(arrayOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.CALL_PHONE,
        ), 3)

        logView.text = LogBus.dump()
        scope.launch {
            LogBus.lines.collect {
                logView.text = LogBus.dump()
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        refreshMcpPanel()
    }

    override fun onResume() {
        super.onResume()
        if (::mcpPanel.isInitialized) refreshMcpPanel() // recién llegado de enseñar en la burbuja
    }

    /** Herramientas aprendidas por enseñanza: nombre, cuántos elementos y tocable para ver todo. */
    private fun refreshMcpPanel() = scope.launch {
        val tools = withContext(Dispatchers.IO) { app.learnedTools.list() }
        mcpPanel.removeAllViews()
        if (tools.isEmpty()) {
            mcpPanel.addView(caption("Aún no he aprendido ninguna app. Activa 🎓 en la burbuja y usa el teléfono normal."))
            return@launch
        }
        tools.forEach { t ->
            mcpPanel.addView(button("🧩 ${t.name} · ${t.elements.size} elementos") { showMcpDetail(t) })
            mcpPanel.gap(dp(8))
        }
    }

    private fun showMcpDetail(t: LearnedTool) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }
        body.addView(title(t.name, 17f))
        if (t.app.isNotBlank()) body.addView(caption("App: ${t.app}"))
        body.gap(dp(8))
        body.addView(caption("Descripción (documentación que usa el modelo):"))
        body.addView(TextView(this).apply { text = t.description; textSize = 13f; setTextColor(Palette.text) })
        body.gap(dp(10))
        body.addView(caption("Elementos (${t.elements.size}):"))
        body.addView(TextView(this).apply {
            text = t.elements.joinToString(" · ")
            textSize = 12f
            setTextColor(Palette.textDim)
            setTextIsSelectable(true)
        })
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun log(message: String) = LogBus.log("app", message)

    /** Ejecuta un prompt con el motor mixto (Gemini computer-use + herramientas MCP). */
    private fun runPrompt(prompt: String) {
        if (app.ui == null) {
            log("Activa el servicio de accesibilidad de Graph")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        log("Pídeme: $prompt")
        moveTaskToBack(true)
        scope.launch {
            runCatching { app.run(prompt, this@MainActivity) }
                .onSuccess { log(it) }
                .onFailure { log(if (it is CancellationException) "Ejecución detenida ✋" else "Error: ${it.message}") }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 2) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            voiceCallback?.invoke(text)
            voiceCallback = null
        }
    }

    /* ---------- UserChannel: dudas del asistente → texto o voz ---------- */

    override suspend fun ask(question: String): String = suspendCancellableCoroutine { cont ->
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
                    if (cont.isActive) cont.resume(input.text.toString())
                }
                .setNegativeButton("🎤 Voz") { _, _ ->
                    voiceCallback = { text -> moveTaskToBack(true); if (cont.isActive) cont.resume(text) }
                    startActivityForResult(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 2)
                }
                .show()
        }
    }
}
