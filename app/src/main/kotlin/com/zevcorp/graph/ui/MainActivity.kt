package com.zevcorp.graph.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.GraphAccessibilityService
import com.zevcorp.graph.platform.KnowledgeGraph
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.platform.UiBugBus
import com.zevcorp.graph.platform.Release
import com.zevcorp.graph.platform.Updater
import com.zevcorp.graph.platform.UsageU
import graph.core.domain.LearnedTool
import graph.core.domain.UserChannel
import graph.core.domain.Workflow
import kotlin.coroutines.resume
import kotlinx.coroutines.*

private const val MAX_STEP_DELAY = 1050 // ms cuando la barra está al mínimo de velocidad

class MainActivity : Activity(), UserChannel {

    private val app get() = GraphApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var logView: TextView
    private var bugView: TextView? = null
    private lateinit var mcpPanel: LinearLayout
    private lateinit var workflowPanel: LinearLayout
    private lateinit var accountBody: LinearLayout
    private var voiceCallback: ((String) -> Unit)? = null
    /** Reconocedor de voz en-app (sin Activity), como el de la burbuja flotante. */
    private var recognizer: android.speech.SpeechRecognizer? = null
    /** La barra de búsqueda (píldora) de la portada Miracle. */
    private var cloudBar: View? = null
    /** La cara heroica arrastrable (portada) y el botón de activar permisos: solo sin accesibilidad. */
    private var heroFace: View? = null
    private var accessBtn: View? = null
    /** Tema con el que se construyó esta pantalla: si cambia (desde la burbuja), se recrea. */
    private var builtWithMode = Palette.mode
    /** Vista actual: nube (principal), usuario (gráfica "versus") o desarrollador (todo). */
    private var mode = MODE_CLOUD
    /** Toques recientes sobre el área secreta (arriba a la derecha): 3 seguidos abren el panel dev. */
    private val secretTapTimes = mutableListOf<Long>()
    /** Acceso a estadísticas de uso con el que se dibujó la gráfica (para recrear al concederlo). */
    private var builtWithAccess = false

    /* Actualizaciones */
    private var updStatus: TextView? = null
    private var updButton: Button? = null
    private var updProgress: ProgressBar? = null
    private var latest: Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        builtWithMode = Palette.mode
        mode = app.prefs.getString(KEY_UI_MODE, MODE_CLOUD) ?: MODE_CLOUD

        // Vista principal: la textura de nubes viva (cielo animado + barra de nube). Pantalla propia,
        // a pantalla completa detrás de las barras del sistema para que el cielo llegue a los bordes.
        if (mode == MODE_CLOUD) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            window.setDecorFitsSystemWindows(false)
            // Que lleguen los insets del teclado (IME) para subir la barra de vidrio al enfocar.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setContentView(buildCloudScreen())
            // Fondo claro (Miracle): íconos oscuros de la barra de estado/navegación.
            applyBarIcons()
            requestPermissions(arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.CALL_PHONE,
            ), 3)
            return
        }

        window.statusBarColor = Palette.bg
        window.navigationBarColor = Palette.bg
        window.setDecorFitsSystemWindows(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }

        // Header: carita + nombre + botón de modo (arriba a la derecha)
        val header = row()
        header.addView(FaceView(this), LinearLayout.LayoutParams(dp(56), dp(56)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(title("Ü", 22f))
        titles.addView(caption("Pídeme algo · lo hago con gestos MCP y computer-use"))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(modeToggle())
        root.addView(header)
        root.gap(dp(16))

        // Modo usuario: una sola gráfica central. El resto (modo desarrollador) sigue abajo intacto.
        if (mode == MODE_USER) {
            buildUserMode(root)
            setContentView(withStaticSky(ScrollView(this).apply { addView(root) }))
            applyBarIcons()
            requestPermissions(arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.CALL_PHONE,
            ), 3)
            return
        }

        // Actualizaciones: versión instalada, buscar/instalar la nueva. Mantén oprimido el título
        // para el panel de administrador (publicar una versión y notificar a todos).
        val upd = card()
        val updHead = row()
        updHead.addView(iconChip(Icon.BOLT, sizeDp = 34, tint = Palette.accent))
        val updTitle = title("Actualizaciones").apply { setPadding(dp(8), 0, 0, 0) }
        updTitle.setOnLongClickListener { showAdminDialog(); true }
        updHead.addView(updTitle, LinearLayout.LayoutParams(0, -2, 1f))
        upd.addView(updHead)
        upd.gap(dp(4))
        updStatus = caption("Versión actual: ${appVersionName()} · buscando…")
        upd.addView(updStatus)
        upd.gap(dp(10))
        updProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        upd.addView(updProgress)
        updButton = button("Buscar actualización") { checkNow() }
        upd.addView(updButton)
        root.addView(upd)
        root.gap(dp(14))

        // Tu cuenta: separa las dos capas de conocimiento. Lo que Ü aprende de la UI de las
        // apps (calculadora, WhatsApp…) es de TODOS los usuarios; tu knowledge-base personal
        // (recuerdos, preferencias, "mi mamá está guardada como…") pertenece SOLO a tu cuenta.
        val account = card()
        val accHead = row()
        accHead.addView(iconChip(Icon.ASSISTANT, sizeDp = 34, tint = Palette.accent))
        accHead.addView(title("Tu cuenta").apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        account.addView(accHead)
        account.gap(dp(4))
        accountBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        account.addView(accountBody)
        paintAccount()
        root.addView(account)
        root.gap(dp(14))

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
        val deepgramInput = EditText(this).apply {
            hint = "Deepgram API key (opcional: dictado nova-3 en las esquinas)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(app.prefs.getString("deepgramKey", GraphApp.DEFAULT_DEEPGRAM_KEY))
            setTextColor(Palette.text)
            setHintTextColor(Palette.textDim)
            background = rounded(Palette.bg, dp(12).toFloat(), Palette.cardBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        setup.addView(deepgramInput)
        setup.gap(dp(8))
        val openaiInput = EditText(this).apply {
            hint = "OpenAI API key (GPT-5.6 computer-use + voz natural)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(app.prefs.getString("openaiKey", GraphApp.DEFAULT_OPENAI_KEY))
            setTextColor(Palette.text)
            setHintTextColor(Palette.textDim)
            background = rounded(Palette.bg, dp(12).toFloat(), Palette.cardBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        setup.addView(openaiInput)
        setup.gap(dp(8))
        // SELECTOR DE MODELO (un clic): elige el cerebro de computer-use — Sol/Terra/Luna (OpenAI
        // GPT-5.6) o Gemini 3.5 Flash (Google). Aplica en la próxima ejecución.
        lateinit var modelBtn: TextView
        modelBtn = button(modelLabel(), primary = true) { openModelChooser { modelBtn.text = modelLabel() } }
        setup.addView(modelBtn)
        setup.gap(dp(6))
        setup.addView(caption("Elige qué cerebro ejecuta las tareas. Sol/Terra/Luna usan tu OpenAI API key; " +
            "Gemini usa la key de Google. La voz natural también usa la OpenAI API key."))
        setup.gap(dp(8))
        // Neo4j Aura (opcional): el grafo de conocimiento donde se proyectan aprendizajes y workflows.
        fun neoField(hintText: String, prefKey: String, password: Boolean = false) = EditText(this).apply {
            hint = hintText
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(app.prefs.getString(prefKey, ""))
            setTextColor(Palette.text)
            setHintTextColor(Palette.textDim)
            background = rounded(Palette.bg, dp(12).toFloat(), Palette.cardBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val neoUriInput = neoField("Neo4j URI (opcional: grafo de conocimiento, p.ej. neo4j+s://xxxx.databases.neo4j.io)", "neo4jUri")
        val neoUserInput = neoField("Neo4j usuario (normalmente neo4j)", "neo4jUser")
        val neoPassInput = neoField("Neo4j contraseña", "neo4jPass", password = true)
        setup.addView(neoUriInput); setup.gap(dp(8))
        setup.addView(neoUserInput); setup.gap(dp(8))
        setup.addView(neoPassInput); setup.gap(dp(8))
        val setupRow = row()
        setupRow.addView(button("Guardar keys") {
            app.prefs.edit()
                .putString("apiKey", keyInput.text.toString().trim())
                .putString("deepgramKey", deepgramInput.text.toString().trim())
                .putString("openaiKey", openaiInput.text.toString().trim())
                .putString("neo4jUri", neoUriInput.text.toString().trim())
                .putString("neo4jUser", neoUserInput.text.toString().trim())
                .putString("neo4jPass", neoPassInput.text.toString().trim())
                .apply()
            log("API keys guardadas")
            // Si acaban de configurar el grafo, proyecta todo el conocimiento actual de una vez.
            scope.launch(Dispatchers.IO) {
                KnowledgeGraph.syncAll(app.learnedTools.list(), app.workflows.list())
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setupRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        setupRow.addView(button("Accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        setup.addView(setupRow)
        setup.gap(dp(8))
        setup.addView(button("Hacer de Ü tu asistente (botón de encendido)") {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        })
        setup.gap(dp(6))
        setup.addView(caption("Al activar accesibilidad aparece la burbuja flotante. En Apps predeterminadas → " +
            "App de asistente digital elige Ü: lo invocas manteniendo el botón de encendido."))
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
        bar.addView(iconChip(Icon.MIC, sizeDp = 44) { startVoice() })
        bar.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        bar.addView(iconChip(Icon.SEND, sizeDp = 44, primary = true) { submitPrompt() })
        ask.addView(bar)
        root.addView(ask)
        root.gap(dp(14))

        // Aprendizaje pasivo: se activa/desactiva AQUÍ (antes estaba en la burbuja). Ü observa
        // el uso normal del teléfono y estructura el mapa MCP de cada app al salir de ella.
        val learn = card()
        val learnHead = row()
        learnHead.addView(iconChip(Icon.TEACH, sizeDp = 34, tint = Palette.accent))
        learnHead.addView(title("Aprendizaje pasivo").apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        learn.addView(learnHead)
        learn.gap(dp(4))
        learn.addView(caption("Ü observa cómo usas tus apps (sin interrumpir) y estructura su mapa " +
            "MCP al salir de cada una. Para ver lo aprendido, mantén oprimido el 🎓 de la burbuja. " +
            "El 🎓 de la burbuja, al tocarlo, es el aprendizaje activo (compartir pantalla)."))
        learn.gap(dp(10))
        var learnBtn: Button? = null
        fun paintLearn() {
            learnBtn?.text = if (app.passive.active) "Desactivar aprendizaje pasivo" else "Activar aprendizaje pasivo"
        }
        val learnButton = button("Activar aprendizaje pasivo") { togglePassive { paintLearn() } }
        learnBtn = learnButton
        learn.addView(learnButton)
        paintLearn()
        root.addView(learn)
        root.gap(dp(14))

        // Velocidad de ejecución: pausa entre los steps de un MCP que se envían juntos
        val speed = card()
        val speedHead = row()
        speedHead.addView(iconChip(Icon.BOLT, sizeDp = 34, tint = Palette.accent))
        speedHead.addView(title("Velocidad de ejecución").apply { setPadding(dp(8), 0, 0, 0) })
        speed.addView(speedHead)
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
        devHead.addView(iconChip(Icon.CODE, sizeDp = 34, tint = Palette.accent))
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
            // Dentro de un ScrollView padre: reclama el gesto para poder scrollear los logs.
            setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
        }
        dev.addView(logScroll, LinearLayout.LayoutParams(-1, dp(260)))
        root.addView(dev)
        root.gap(dp(14))

        // Bugs de UI: fallos de clic por ID ambiguo detectados en tiempo real + diagnóstico del LLM
        // (ID único correcto + cómo endurecer la detección nativa). Se llena solo usando el teléfono.
        val bugs = card()
        val bugsHead = row()
        bugsHead.addView(iconChip(Icon.BOLT, sizeDp = 34, tint = Palette.accent))
        bugsHead.addView(title("Bugs de UI").apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        bugsHead.addView(button("Copiar") {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("graph-ui-bugs", UiBugBus.dump()))
            Toast.makeText(this, "Bugs copiados", Toast.LENGTH_SHORT).show()
        })
        bugsHead.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        bugsHead.addView(button("Limpiar") { UiBugBus.clear() })
        bugs.addView(bugsHead)
        bugs.gap(dp(4))
        bugs.addView(caption("Fallos de clic por ID ambiguo (un ID que resuelve a otro elemento, p.ej. " +
            "siempre el primer chat de una lista) detectados solos con el aprendizaje pasivo, con el ID " +
            "correcto y el fix nativo que propone el modelo."))
        bugs.gap(dp(8))
        bugView = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Palette.textDim)
            setTextIsSelectable(true)
        }
        val bugScroll = ScrollView(this).apply {
            addView(bugView)
            background = rounded(Palette.bg, dp(12).toFloat(), Palette.cardBorder)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
        }
        bugs.addView(bugScroll, LinearLayout.LayoutParams(-1, dp(200)))
        root.addView(bugs)
        root.gap(dp(14))

        // Panel de MCPs: lo que el asistente ha aprendido (enseñanza) + lo incorporado de fábrica
        val mcp = card()
        val mcpHead = row()
        mcpHead.addView(iconChip(Icon.TOOLS, sizeDp = 34, tint = Palette.accent))
        mcpHead.addView(title("Herramientas MCP").apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        mcpHead.addView(button("Actualizar") { refreshMcpPanel() })
        mcp.addView(mcpHead)
        mcp.gap(dp(4))
        mcp.addView(caption("Aprendidas con el modo enseñanza + gestos y acciones de sistema incorporados."))
        mcp.gap(dp(10))
        mcpPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mcp.addView(mcpPanel)
        root.addView(mcp)
        root.gap(dp(14))

        // Panel de WORKFLOWS: el paso a paso de tareas aprendido en la enseñanza (el puente
        // consciente ↔ subconsciente). Aquí SE VE qué se creó, cuándo mejora y qué vía lleva cada step.
        val wfCard = card()
        val wfHead = row()
        wfHead.addView(iconChip(Icon.BOLT, sizeDp = 34, tint = Palette.accent))
        wfHead.addView(title("Workflows").apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        wfHead.addView(button("Actualizar") { refreshWorkflowPanel() })
        wfCard.addView(wfHead)
        wfCard.gap(dp(4))
        wfCard.addView(caption("Tareas paso a paso aprendidas en la enseñanza. 🧩 = step subconsciente " +
            "(clic por árbol de UI) · 👁 = step consciente (Gemini mira la pantalla)."))
        wfCard.gap(dp(10))
        workflowPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wfCard.addView(workflowPanel)
        root.addView(wfCard)

        setContentView(withStaticSky(ScrollView(this).apply { addView(root) }))
        applyBarIcons() // tras setContentView: ya existe el decorView (antes daba NPE)
        requestPermissions(arrayOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.CALL_PHONE,
        ), 3)

        logView.text = LogBus.dump()
        scope.launch {
            LogBus.lines.collect {
                // Solo sigue el "vivo" si ya estabas al fondo: si subiste a leer, no te arrastra.
                val atBottom = logScroll.scrollY + logScroll.height >= logView.height - dp(28)
                logView.text = LogBus.dump()
                if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        bugView?.let { bv ->
            bv.text = UiBugBus.dump().ifBlank { "Sin fallos de clic detectados aún." }
            scope.launch {
                UiBugBus.events.collect {
                    bv.text = UiBugBus.dump().ifBlank { "Sin fallos de clic detectados aún." }
                }
            }
        }
        refreshMcpPanel()
        refreshWorkflowPanel()
        checkNow()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkNow() // llegó desde la notificación de actualización
    }

    /* ---------- Auto-actualización ---------- */

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun checkNow() {
        updStatus?.text = "Buscando actualización…"
        scope.launch {
            latest = Updater.fetchLatest()
            refreshUpdateUi()
        }
    }

    private fun refreshUpdateUi() {
        val btn = updButton ?: return
        val r = latest
        if (r != null && Updater.isNewer(this, r)) {
            updStatus?.text = "Nueva versión disponible: ${r.versionName}" +
                if (r.notes.isNotBlank()) "\n${r.notes}" else ""
            btn.text = "Actualizar ahora (${r.versionName})"
            btn.setOnClickListener { startUpdate() }
        } else {
            updStatus?.text = "Versión actual: ${appVersionName()} · estás al día ✅"
            btn.text = "Buscar actualización"
            btn.setOnClickListener { checkNow() }
        }
    }

    private fun startUpdate() {
        val r = latest ?: return
        val btn = updButton ?: return
        Updater.clearNotification(this)
        btn.isEnabled = false
        updProgress?.apply { progress = 0; visibility = View.VISIBLE }
        updStatus?.text = "Descargando ${r.versionName}…"
        scope.launch {
            runCatching {
                Updater.downloadAndInstall(this@MainActivity, r) { p -> runOnUiThread { updProgress?.progress = p } }
            }.onSuccess {
                updStatus?.text = "Descargado. Confirma la instalación en el diálogo del sistema."
            }.onFailure {
                updStatus?.text = "No se pudo actualizar: ${it.message}"
                btn.isEnabled = true
                updProgress?.visibility = View.GONE
            }
        }
    }

    /* ---------- Admin: publicar una versión y notificar a todos ---------- */

    private fun showAdminDialog() {
        val ctx = this
        val nextCode = Updater.currentVersionCode(ctx) + 1
        val bucket = "https://zyvfamlhlmztliexvmej.supabase.co/storage/v1/object/public/apks/"
        fun field(hint: String, value: String, numeric: Boolean = false) = EditText(ctx).apply {
            this.hint = hint; setText(value)
            setHintTextColor(Palette.textDim); setTextColor(Palette.text)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            background = rounded(Palette.bg, dp(10).toFloat(), Palette.cardBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val tokenF = field("Token de administrador", app.prefs.getString("adminToken", "") ?: "")
        val codeF = field("versionCode (número)", nextCode.toString(), numeric = true)
        val nameF = field("versionName", "0.$nextCode")
        val urlF = field("URL del APK", "${bucket}graph-$nextCode.apk")
        val notesF = field("Notas (qué cambió)", "")
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(caption("Sube el APK al bucket 'apks' de Supabase y pega su URL pública. Al publicar, todos los usuarios verán el aviso de actualización."))
            gap(dp(10)); addView(tokenF)
            gap(dp(8)); addView(codeF)
            gap(dp(8)); addView(nameF)
            gap(dp(8)); addView(urlF)
            gap(dp(8)); addView(notesF)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Admin · publicar versión")
            .setView(ScrollView(ctx).apply { addView(body) })
            .setPositiveButton("Publicar y notificar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = tokenF.text.toString().trim()
                val code = codeF.text.toString().trim().toIntOrNull()
                val url = urlF.text.toString().trim()
                if (token.isBlank() || code == null || url.isBlank()) {
                    Toast.makeText(ctx, "Completa token, versionCode y URL", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Toast.makeText(ctx, "Publicando…", Toast.LENGTH_SHORT).show()
                scope.launch {
                    val err = Updater.publish(token, code, nameF.text.toString().trim(), url, notesF.text.toString().trim())
                    if (err == null) {
                        app.prefs.edit().putString("adminToken", token).apply()
                        Toast.makeText(ctx, "✅ Publicado y notificado (v$code)", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        checkNow()
                    } else {
                        Toast.makeText(ctx, "Error: $err", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    /** La burbuja flotante de la accesibilidad (null si el servicio no está activo). */
    private fun bubble() = (app.ui as? GraphAccessibilityService)?.bubble

    /** Punto donde se asienta la carita pequeña: el inicio de la barra de texto (sobre su borde). */
    private fun dockFaceToBar() {
        val bar = cloudBar ?: return
        bar.post {
            val loc = IntArray(2)
            bar.getLocationOnScreen(loc)
            bubble()?.dockToBar(loc[0] + dp(12), loc[1] + bar.height / 2)
        }
    }

    override fun onResume() {
        super.onResume()
        // La app pasó a primer plano. En la portada Miracle: si la accesibilidad está activa, la
        // carita overlay se asienta en la barra (la heroica se oculta); si no, la cara heroica
        // arrastrable es la protagonista. En las otras vistas, la overlay va arriba al centro.
        if (mode == MODE_CLOUD) {
            // La cara overlay se oculta aquí: en la portada manda la cara heroica in-app.
            applyHeroVisibility()
            bubble()?.setHiddenForApp(true)
        } else bubble()?.dockToApp()
        // Si el tema cambió desde la burbuja mientras esto estaba en segundo plano, recrear con los
        // colores nuevos (blanco/negro coherente con la carita).
        if (builtWithMode != Palette.mode) { recreate(); return }
        if (mode == MODE_USER) {
            // Al volver de conceder el acceso a uso, redibuja la gráfica con el tiempo real.
            if (builtWithAccess != UsageU.hasUsageAccess(this)) recreate()
            return
        }
        if (::mcpPanel.isInitialized) refreshMcpPanel() // recién llegado de enseñar en la burbuja
        if (::workflowPanel.isInitialized) refreshWorkflowPanel()
    }

    override fun onPause() {
        super.onPause()
        // La app pasó a segundo plano (incluye moveTaskToBack al ejecutar algo): la carita overlay
        // reaparece (estaba oculta en la portada) y/o regresa a donde estaba y reanuda su reposo.
        bubble()?.let { if (mode == MODE_CLOUD) it.setHiddenForApp(false) else it.undockFromApp() }
    }

    /** Íconos de la barra de estado/navegación: oscuros sobre fondo claro, claros sobre fondo oscuro. */
    private fun applyBarIcons() {
        // Llamar SOLO tras setContentView: antes, window.insetsController lanza NPE (decorView aún nulo).
        runCatching {
            val lightBg = Palette.mode != ThemeMode.DARK
            val controller = window.decorView.windowInsetsController ?: return
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(if (lightBg) mask else 0, mask)
        }
    }

    /** Herramientas aprendidas por enseñanza: nombre, cuántos elementos y tocable para ver todo. */
    private fun refreshMcpPanel() = scope.launch {
        val tools = withContext(Dispatchers.IO) { app.learnedTools.list() }
        mcpPanel.removeAllViews()
        if (tools.isEmpty()) {
            mcpPanel.addView(caption("Aún no he aprendido ninguna app. Activa el modo enseñanza en la burbuja y usa el teléfono normal."))
            return@launch
        }
        tools.forEach { t ->
            mcpPanel.addView(button("${t.name} · ${t.elements.size} elementos") { showMcpDetail(t) })
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

    /** Workflows aprendidos: nombre, cuántos steps por vía, y tocable para ver el paso a paso. */
    private fun refreshWorkflowPanel() = scope.launch {
        val wfs = withContext(Dispatchers.IO) { app.workflows.list() }
        workflowPanel.removeAllViews()
        if (wfs.isEmpty()) {
            workflowPanel.addView(caption("Aún no hay workflows. Enséñame una tarea (enseñanza pasiva " +
                "o activa) y al terminar la estructuro como workflow."))
            return@launch
        }
        wfs.forEach { w ->
            val sub = w.steps.count { it.subconscious }
            workflowPanel.addView(button("${w.name} · ${w.steps.size} steps (🧩$sub 👁${w.steps.size - sub})") {
                showWorkflowDetail(w)
            })
            workflowPanel.gap(dp(8))
        }
    }

    private fun showWorkflowDetail(w: Workflow) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }
        body.addView(title(w.name, 17f))
        body.addView(caption("Fuente: enseñanza ${if (w.source == "active") "activa (grabación)" else "pasiva (observación)"}"))
        body.gap(dp(8))
        body.addView(TextView(this).apply { text = w.description; textSize = 13f; setTextColor(Palette.text) })
        body.gap(dp(10))
        body.addView(caption("Paso a paso (${w.steps.size} steps):"))
        w.steps.forEachIndexed { i, s ->
            val via = if (s.subconscious) "🧩" else "👁"
            val target = if (s.subconscious && s.target.isNotBlank()) " → \"${s.target}\"" else ""
            val note = if (s.note.isNotBlank()) "\n      📝 ${s.note}" else ""
            body.addView(TextView(this).apply {
                text = "${i + 1}. $via ${s.action}$target$note"
                textSize = 13f
                setTextColor(Palette.text)
                setPadding(0, dp(4), 0, 0)
                setTextIsSelectable(true)
            })
        }
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Cerrar", null)
            .setNegativeButton("Borrar") { _, _ ->
                scope.launch(Dispatchers.IO) { app.workflows.delete(w.name) }
                log("Workflow \"${w.name}\" borrado")
                refreshWorkflowPanel()
            }
            .show()
    }

    /* ---------- Tu cuenta: login/registro (el conocimiento personal es solo tuyo) ---------- */

    /** Pinta el contenido de la tarjeta de cuenta según haya sesión o no. */
    private fun paintAccount() {
        val auth = app.auth
        accountBody.removeAllViews()

        if (auth.loggedIn) {
            accountBody.addView(caption("Sesión iniciada: ${auth.email}"))
            accountBody.gap(dp(4))
            accountBody.addView(caption("Tus recuerdos y preferencias se guardan en tu cuenta (solo tú los ves) " +
                "y te siguen si cambias de teléfono. Lo que aprendo de la UI de las apps se comparte " +
                "de forma anónima con todos los usuarios."))
            accountBody.gap(dp(10))
            accountBody.addView(button("Cerrar sesión") {
                scope.launch {
                    withContext(Dispatchers.IO) { app.auth.signOut() }
                    log("Sesión cerrada")
                    paintAccount()
                }
            })
            return
        }

        accountBody.addView(caption("Con cuenta, tu conocimiento personal (recuerdos como \"mi mamá está " +
            "guardada como 'Ale'\" o \"soy desarrollador\") pertenece solo a ti y sobrevive a reinstalaciones. " +
            "Sin cuenta, esos recuerdos se quedan solo en este teléfono. El mapa de UI de las apps siempre " +
            "es compartido: lo que cualquiera me enseña nos sirve a todos."))
        accountBody.gap(dp(10))
        val emailF = EditText(this).apply {
            hint = "Correo"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(app.prefs.getString("authLastEmail", ""))
            setTextColor(Palette.text); setHintTextColor(Palette.textDim)
            background = rounded(Palette.bg, dp(12).toFloat(), Palette.cardBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        accountBody.addView(emailF)
        accountBody.gap(dp(8))
        val passF = EditText(this).apply {
            hint = "Contraseña (mínimo 8 caracteres)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Palette.text); setHintTextColor(Palette.textDim)
            background = rounded(Palette.bg, dp(12).toFloat(), Palette.cardBorder)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        accountBody.addView(passF)
        accountBody.gap(dp(8))
        val status = caption("")
        fun submit(newAccount: Boolean) {
            val email = emailF.text.toString().trim()
            val pass = passF.text.toString()
            if (email.isBlank() || pass.isBlank()) { status.text = "Escribe correo y contraseña"; return }
            status.text = if (newAccount) "Creando tu cuenta…" else "Entrando…"
            scope.launch {
                val err = withContext(Dispatchers.IO) {
                    if (!newAccount) app.auth.signIn(email, pass)
                    else when (val signUpErr = app.auth.signUp(email, pass)) {
                        null -> app.auth.signIn(email, pass)
                        else -> {
                            // El correo puede ya tener cuenta en este proyecto (p.ej. de Miracle,
                            // que comparte el backend): intenta entrar con esas credenciales en
                            // vez de estrellarse contra "ya existe".
                            if (!signUpErr.contains("Ya existe", ignoreCase = true)) signUpErr
                            else app.auth.signIn(email, pass)?.let {
                                "Ese correo ya tiene una cuenta (quizá de Miracle) y la contraseña " +
                                    "no coincide. Entra con su contraseña o usa otro correo."
                            }
                        }
                    }
                }
                if (err != null) { status.text = err; return@launch }
                app.prefs.edit().putString("authLastEmail", email).apply()
                log("Sesión iniciada: ${app.auth.email}")
                app.sessionChanged() // adopta las notas anónimas y sincroniza la memoria de la cuenta
                paintAccount()
            }
        }
        val actions = row()
        actions.addView(button("Crear cuenta") { submit(newAccount = true) },
            LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(button("Iniciar sesión", primary = true) { submit(newAccount = false) },
            LinearLayout.LayoutParams(0, -2, 1f))
        accountBody.addView(actions)
        accountBody.gap(dp(6))
        accountBody.addView(status)
    }

    private fun log(message: String) = LogBus.log("app", message)

    /* ---------- Nube ⇄ usuario ("versus") ⇄ desarrollador ---------- */

    /** Siguiente vista del ciclo nube → usuario → desarrollador → nube. */
    private fun nextMode(): String = when (mode) {
        MODE_CLOUD -> MODE_USER
        MODE_USER -> MODE_DEV
        else -> MODE_CLOUD
    }

    private fun iconFor(m: String): Icon = when (m) {
        MODE_USER -> Icon.EYE   // la gráfica "versus"
        MODE_DEV -> Icon.CODE   // el panel de desarrollador
        else -> Icon.CLOUD      // la textura de nubes
    }

    private fun labelFor(m: String): String = when (m) {
        MODE_USER -> "Versus"
        MODE_DEV -> "Desarrollador"
        else -> "Nube"
    }

    private fun cycleMode() {
        val next = nextMode()
        app.prefs.edit().putString(KEY_UI_MODE, next).apply()
        Toast.makeText(this, labelFor(next), Toast.LENGTH_SHORT).show()
        recreate()
    }

    /** Botón tipo nube arriba a la derecha que cicla entre las tres vistas (muestra la siguiente). */
    private fun modeToggle(): View = cloudChip(iconFor(nextMode()), sizeDp = 40, subtle = true) { cycleMode() }

    /**
     * Área invisible (sin ícono, sin fondo) que reemplaza al toggle en la pantalla principal: solo
     * reacciona a un TRIPLE toque rápido, y abre directo el panel de Desarrollador (salta el modo
     * Usuario). Pensada para quedar indetectable a un usuario final.
     */
    private fun secretDevArea(): View = View(this).apply {
        setOnClickListener {
            val now = System.currentTimeMillis()
            secretTapTimes.removeAll { now - it > 600 }
            secretTapTimes.add(now)
            if (secretTapTimes.size >= 3) {
                secretTapTimes.clear()
                app.prefs.edit().putString(KEY_UI_MODE, MODE_DEV).apply()
                recreate()
            }
        }
    }

    /** Envuelve un contenido con la foto de nubes (estática) de fondo (vistas usuario/desarrollador). */
    private fun withStaticSky(content: View): View {
        val frame = android.widget.FrameLayout(this)
        val bg = android.widget.ImageView(this).apply {
            setImageResource(com.zevcorp.graph.R.drawable.cloud_sky_full)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(bg, android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(content, android.widget.FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    /**
     * Dictado por voz SIN Activity: el mismo camino que el micrófono de la burbuja flotante
     * (SpeechRecognizer directo, sin abrir el pop-up de voz de Google).
     */
    private fun recognize(onResult: (String?) -> Unit) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { onResult(null); return }
        recognizer?.destroy()
        val rec = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle) {
                onResult(results.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                rec.destroy(); recognizer = null
            }
            override fun onError(error: Int) { onResult(null); rec.destroy(); recognizer = null }
            override fun onReadyForSpeech(params: android.os.Bundle?) {
                Toast.makeText(this@MainActivity, "Te escucho…", Toast.LENGTH_SHORT).show()
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }

    /** Escucha por voz y, si oye algo, ejecuta el prompt (mic de la barra de nube y de las cards). */
    private fun startVoice() = recognize { heard ->
        if (!heard.isNullOrBlank()) runPrompt(heard) else Toast.makeText(this, "No te escuché", Toast.LENGTH_SHORT).show()
    }

    /* ---------- Portada "Miracle": fondo radial, cara heroica y barra de búsqueda ---------- */

    private fun fp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) =
        android.widget.FrameLayout.LayoutParams(w, h, gravity)

    /**
     * Pantalla principal (diseño "Miracle"): fondo con degradado radial suave, streams de luz
     * decorativos, título con halo, la CARA heroica (nuestra FaceView, con glow y sombra del diseño,
     * arrastrable) sobre un botón para activar los permisos, y abajo la barra de búsqueda funcional.
     */
    private fun buildCloudScreen(): View {
        val t = miracleTheme()
        val root = android.widget.FrameLayout(this).apply { clipChildren = false; clipToPadding = false }

        // 1) Fondo radial + streams decorativos.
        root.addView(MiracleBgView(this, t), fp(-1, -1))
        root.addView(StreamsView(this, t), fp(-1, -1))

        // 2) Título "Miracle" con halo pulsante, arriba-centro.
        val titleBlock = android.widget.FrameLayout(this).apply { clipChildren = false }
        val halo = RadialGlowView(this, t.glow, 0.55f)
        titleBlock.addView(halo, fp(dp(240), dp(240), Gravity.CENTER))
        titleBlock.addView(TextView(this).apply {
            text = "Miracle"; textSize = 26f
            setTextColor(t.pillText)
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.28f
            gravity = Gravity.CENTER
        }, fp(-2, -2, Gravity.CENTER))
        val titleLp = fp(-2, dp(240), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        root.addView(titleBlock, titleLp)
        breathe(halo)

        // 3) La cara heroica (arrastrable) y el botón de permisos, al centro.
        val hero = buildHeroFace(t)
        heroFace = hero
        root.addView(hero, fp(dp(200), dp(200), Gravity.CENTER))

        val access = buildAccessButton(t).apply { translationY = dp(150).toFloat() }
        accessBtn = access
        root.addView(access, fp(-2, -2, Gravity.CENTER))

        // 4) Barra de búsqueda funcional, abajo.
        val bar = buildCloudBar(t)
        cloudBar = bar
        val barLp = fp(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(24)
        }
        root.addView(bar, barLp)

        // 5) Botón minimalista de configuración de VOZ, arriba-izquierda.
        val voiceChip = buildVoiceChip(t)
        val voiceLp = fp(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(10); leftMargin = dp(16) }
        root.addView(voiceChip, voiceLp)

        // 6) Panel de Desarrollador OCULTO: área invisible arriba-derecha, triple toque.
        val devArea = secretDevArea()
        val devLp = fp(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(14) }
        root.addView(devArea, devLp)

        // Insets: chips superiores bajo el notch; título centrado; barra sobre la navegación / teclado.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            titleLp.topMargin = sys.top + dp(26); titleBlock.layoutParams = titleLp
            voiceLp.topMargin = sys.top + dp(10); voiceChip.layoutParams = voiceLp
            devLp.topMargin = sys.top + dp(8); devArea.layoutParams = devLp
            barLp.bottomMargin = maxOf(sys.bottom, ime.bottom) + dp(18); bar.layoutParams = barLp
            insets
        }

        // La cara heroica y el botón de permisos solo tienen sentido SIN accesibilidad (onboarding).
        applyHeroVisibility()
        return root
    }

    /**
     * La cara heroica es SIEMPRE la protagonista de la portada: grande y al centro (nunca pequeña en
     * la barra). El botón de permisos solo aparece si la accesibilidad aún no está activa.
     */
    private fun applyHeroVisibility() {
        heroFace?.visibility = View.VISIBLE
        accessBtn?.visibility = if (app.ui == null) View.VISIBLE else View.GONE
    }

    /**
     * La CARA heroica: nuestra FaceView (elemento existente) con el aura y la sombra del diseño
     * entregado, un leve flotar, y el reflejo del suelo. Arrastrable por toda la pantalla; un toque
     * (sin arrastre) activa el micrófono.
     */
    private fun buildHeroFace(t: MiracleTheme): View {
        val block = android.widget.FrameLayout(this).apply { clipChildren = false }
        block.addView(RadialGlowView(this, t.glowStrong, 0.5f), fp(dp(240), dp(240), Gravity.CENTER))
        block.addView(RadialGlowView(this, t.glowStrong, 0.32f).apply { translationY = dp(84).toFloat() },
            fp(dp(150), dp(56), Gravity.CENTER))
        val face = FaceView(this).apply {
            elevation = dp(16).toFloat()
            outlineAmbientShadowColor = t.glowStrong
            outlineSpotShadowColor = t.glowStrong
        }
        block.addView(face, fp(dp(112), dp(112), Gravity.CENTER))
        floatY(face)
        attachDrag(block) { heroListen(face) }
        return block
    }

    /** Toque en la cara heroica: CRECE (feedback de que está escuchando) y activa el micrófono. */
    private fun heroListen(face: View) {
        face.animate().scaleX(1.18f).scaleY(1.18f).setDuration(180)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        recognize { heard ->
            face.animate().scaleX(1f).scaleY(1f).setDuration(320).start()
            if (!heard.isNullOrBlank()) runPrompt(heard)
            else Toast.makeText(this, "No te escuché", Toast.LENGTH_SHORT).show()
        }
    }

    /** Arrastre libre: mueve la vista siguiendo el dedo; un toque limpio (sin desplazamiento) es "click". */
    private fun attachDrag(v: View, onTap: () -> Unit) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var offX = 0f; var offY = 0f; var downX = 0f; var downY = 0f; var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    offX = e.rawX - view.translationX; offY = e.rawY - view.translationY
                    downX = e.rawX; downY = e.rawY; moved = false; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    view.translationX = e.rawX - offX; view.translationY = e.rawY - offY
                    if (kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > slop) moved = true
                    true
                }
                android.view.MotionEvent.ACTION_UP -> { if (!moved) onTap(); true }
                else -> false
            }
        }
    }

    /** Arranca un ValueAnimator en bucle atado al ciclo de vida de la vista (se cancela al desacoplarse,
     *  evitando fugas al recrear la Activity). */
    private fun loopWith(v: View, anim: android.animation.ValueAnimator) {
        anim.repeatCount = android.animation.ValueAnimator.INFINITE
        anim.repeatMode = android.animation.ValueAnimator.REVERSE
        anim.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { if (!anim.isStarted) anim.start() }
            override fun onViewDetachedFromWindow(view: View) { anim.cancel() }
        })
        if (v.isAttachedToWindow) anim.start()
    }

    /** Leve flotar vertical (miracle-float del diseño): ±6dp en bucle suave. */
    private fun floatY(v: View) {
        loopWith(v, android.animation.ValueAnimator.ofFloat(0f, -dp(6).toFloat()).apply {
            duration = 2500
            addUpdateListener { v.translationY = it.animatedValue as Float }
        })
    }

    /** Halo que "respira" (miracle-pulse): escala/alpha muy sutil en bucle. */
    private fun breathe(v: View) {
        loopWith(v, android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            addUpdateListener {
                val f = it.animatedValue as Float
                v.scaleX = 1f + 0.05f * f; v.scaleY = 1f + 0.05f * f
                v.alpha = 0.85f + 0.15f * f
            }
        })
    }

    /** Botón para activar TODOS los permisos (accesibilidad + runtime). Píldora oscura, estética limpia. */
    private fun buildAccessButton(t: MiracleTheme): View = TextView(this).apply {
        text = "Activar accesibilidad"
        textSize = 14f
        setTextColor(t.pill) // texto claro sobre píldora oscura
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = rounded(t.pillText, dp(26).toFloat())
        setPadding(dp(22), dp(13), dp(22), dp(13))
        elevation = dp(6).toFloat()
        setOnClickListener { activateAll() }
    }

    /** Botón minimalista "Voz" (arriba-izquierda): misma píldora limpia que el resto de la portada. */
    private fun buildVoiceChip(t: MiracleTheme): View = TextView(this).apply {
        text = "Voz"
        textSize = 13f
        setTextColor(t.pillText)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = rounded(t.pill, dp(20).toFloat(), t.border)
        elevation = dp(4).toFloat()
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { openVoiceSettings() }
    }

    /** Etiqueta del botón de modelo según lo elegido (proveedor + nivel GPT-5.6). */
    private fun modelLabel(): String =
        if (app.prefs.getString("provider", "GEMINI") == "OPENAI")
            "Modelo: " + when (app.prefs.getString("openaiModel", "gpt-5.6-terra")) {
                "gpt-5.6-sol" -> "Sol ☀️"
                "gpt-5.6-luna" -> "Luna 🌙"
                else -> "Terra 🌍"
            } + " (GPT-5.6)  ⚙"
        else "Modelo: Gemini 3.5 Flash  ⚙"

    /**
     * Selector de MODELO del cerebro de computer-use. Sol/Terra/Luna conmutan el proveedor a OpenAI
     * (GPT-5.6) y fijan el nivel; Gemini 3.5 Flash vuelve a Google. Aplica en la próxima ejecución.
     */
    private fun openModelChooser(onChange: () -> Unit) {
        val openai = app.prefs.getString("provider", "GEMINI") == "OPENAI"
        val oaModel = app.prefs.getString("openaiModel", "gpt-5.6-terra")
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.bg, dp(24).toFloat(), Palette.cardBorder)
            setPadding(dp(22), dp(20), dp(22), dp(18))
        }
        body.addView(title("Modelo de IA", 18f))
        body.gap(dp(4))
        body.addView(caption("Elige qué cerebro ejecuta las tareas."))
        body.gap(dp(16))
        lateinit var dialog: AlertDialog
        fun chooseOpenAi(m: String) {
            app.prefs.edit().putString("provider", "OPENAI").putString("openaiModel", m).apply()
            onChange(); log("Modelo → OpenAI $m (aplica en la próxima ejecución)"); dialog.dismiss()
        }
        fun chooseGemini() {
            app.prefs.edit().putString("provider", "GEMINI").putString("model", "gemini-3.5-flash").apply()
            onChange(); log("Modelo → Gemini 3.5 Flash (aplica en la próxima ejecución)"); dialog.dismiss()
        }
        fun mark(active: Boolean, label: String) = if (active) "$label  ✓" else label
        listOf(
            Triple("gpt-5.6-sol", "Sol ☀️", "el más potente, mejor computer-use"),
            Triple("gpt-5.6-terra", "Terra 🌍", "generalista equilibrado"),
            Triple("gpt-5.6-luna", "Luna 🌙", "rápido y económico"),
        ).forEach { (id, name, desc) ->
            val on = openai && oaModel == id
            body.addView(button(mark(on, "$name — $desc"), primary = on) { chooseOpenAi(id) })
            body.gap(dp(10))
        }
        body.addView(button(mark(!openai, "Gemini 3.5 Flash — Google"), primary = !openai) { chooseGemini() })
        body.gap(dp(12))
        body.addView(caption("Sol/Terra/Luna requieren tu OpenAI API key en la configuración."))
        // Velocidad de razonamiento (solo OpenAI): menor esfuerzo = menos latencia por turno.
        body.gap(dp(14))
        val efforts = listOf(
            "minimal" to "Mínimo (más rápido)", "low" to "Bajo (recomendado)",
            "medium" to "Medio", "high" to "Alto (más preciso)")
        lateinit var effortBtn: TextView
        fun effortLabel() = "Razonamiento: " +
            (efforts.firstOrNull { it.first == app.prefs.getString("openaiEffort", "low") }?.second ?: "Bajo (recomendado)")
        effortBtn = button(effortLabel()) {
            val cur = app.prefs.getString("openaiEffort", "low")
            val idx = efforts.indexOfFirst { it.first == cur }.let { if (it < 0) 1 else it }
            val next = efforts[(idx + 1) % efforts.size].first
            app.prefs.edit().putString("openaiEffort", next).apply()
            effortBtn.text = effortLabel()
            log("Razonamiento OpenAI → $next (aplica en la próxima ejecución)")
        }
        body.addView(effortBtn)
        body.gap(dp(6))
        body.addView(caption("Menos razonamiento = respuestas más rápidas por paso; más = más preciso pero lento."))
        val scroller = ScrollView(this).apply { addView(body) }
        dialog = AlertDialog.Builder(this).setView(scroller).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /**
     * Configuración de voz: voces NATURALES de OpenAI (nueva generación, muy humanas) o las del sistema
     * (masculina/femenina). Aplica al instante y da una muestra hablada. Las voces de OpenAI usan la key
     * del panel de Desarrollador; sin key, la muestra cae automáticamente a la voz del sistema.
     */
    private fun openVoiceSettings() {
        val engine = app.prefs.getString("voiceEngine", "openai")
        val oaVoice = app.prefs.getString("openaiVoice", "verse")
        val gender = app.prefs.getString("voiceGender", "male")
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.bg, dp(24).toFloat(), Palette.cardBorder)
            setPadding(dp(22), dp(20), dp(22), dp(18))
        }
        body.addView(title("Voz", 18f))
        body.gap(dp(4))
        body.addView(caption("Elige cómo suena Miracle."))
        body.gap(dp(16))
        lateinit var dialog: AlertDialog
        fun preview() { bubble()?.reapplyVoice(); bubble()?.speak("Hola, soy Miracle. Así sueno.") }
        fun chooseOpenAi(v: String) {
            app.prefs.edit().putString("voiceEngine", "openai").putString("openaiVoice", v).apply()
            preview(); dialog.dismiss()
        }
        fun chooseSystem(g: String) {
            app.prefs.edit().putString("voiceEngine", "system").putString("voiceGender", g).apply()
            preview(); dialog.dismiss()
        }
        fun mark(active: Boolean, label: String) = if (active) "$label  ✓" else label
        val oa = engine == "openai"
        // Voces naturales de nueva generación (OpenAI).
        listOf("verse" to "Verse", "coral" to "Coral", "sage" to "Sage").forEach { (id, name) ->
            val on = oa && oaVoice == id
            body.addView(button(mark(on, "Natural · $name"), primary = on) { chooseOpenAi(id) })
            body.gap(dp(10))
        }
        // Voces del sistema (fallback sin key).
        body.addView(button(mark(!oa && gender != "female", "Sistema · Masculina"), primary = !oa && gender != "female") { chooseSystem("male") })
        body.gap(dp(10))
        body.addView(button(mark(!oa && gender == "female", "Sistema · Femenina"), primary = !oa && gender == "female") { chooseSystem("female") })
        body.gap(dp(12))
        body.addView(caption("Las voces Natural usan tu key de OpenAI (panel de Desarrollador). Sin key, suena la del sistema."))
        dialog = AlertDialog.Builder(this).setView(body).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    /** Pide los permisos runtime y abre los Ajustes de Accesibilidad para encender el servicio. */
    private fun activateAll() {
        runCatching {
            requestPermissions(arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.CALL_PHONE,
            ), 3)
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Activa el servicio de accesibilidad de Miracle", Toast.LENGTH_LONG).show()
    }

    /**
     * La barra de búsqueda de la portada: píldora blanca con sombra azulada del diseño, 100% funcional.
     * Micrófono (voz) cuando está vacía; flecha de enviar cuando hay texto; Enter/enviar ejecuta.
     */
    private fun buildCloudBar(t: MiracleTheme): View {
        val g = dp(44)
        val pill = android.widget.FrameLayout(this).apply {
            background = rounded(t.pill, dp(30).toFloat(), t.border)
            elevation = dp(14).toFloat()
            outlineAmbientShadowColor = t.glowStrong
            outlineSpotShadowColor = t.glowStrong
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, minOf(view.height / 2f, dp(30).toFloat()))
                }
            }
            setPadding(dp(22), dp(4), dp(6), dp(4))
        }

        val bar = row()
        val input = EditText(this).apply {
            hint = "¿Qué puedo hacer hoy por ti?"
            setHintTextColor(t.placeholder)
            setTextColor(t.pillText)
            textSize = 16f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            isSingleLine = false
            maxLines = 3
            setHorizontallyScrolling(false)
            setPadding(0, dp(14), 0, dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        fun submit() {
            val p = input.text.toString().trim()
            if (p.isBlank()) return
            input.setText("")
            input.clearFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(input.windowToken, 0)
            runPrompt(p)
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }

        // Micrófono (voz) cuando está vacía · flecha de enviar (círculo oscuro) cuando hay texto.
        val mic = IconView(this, Icon.MIC, tint = t.pillText).apply {
            val pad = dp(11); setPadding(pad, pad, pad, pad); setOnClickListener { startVoice() }
        }
        val send = IconView(this, Icon.SEND, tint = t.pill).apply {
            val pad = dp(11); setPadding(pad, pad, pad, pad)
            background = rounded(t.pillText, (g / 2).toFloat())
            visibility = View.GONE; setOnClickListener { submit() }
        }
        val slot = android.widget.FrameLayout(this)
        slot.addView(mic, fp(g, g))
        slot.addView(send, fp(g, g))

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val has = !s.isNullOrBlank()
                send.visibility = if (has) View.VISIBLE else View.GONE
                mic.visibility = if (has) View.GONE else View.VISIBLE
            }
        })

        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(slot, LinearLayout.LayoutParams(g, g))
        pill.addView(bar, fp(-1, -2, Gravity.CENTER))
        return pill
    }

    /** La única pantalla del modo usuario: la gráfica "versus" y una narrativa elegante. */
    private fun buildUserMode(root: LinearLayout) {
        builtWithAccess = UsageU.hasUsageAccess(this)
        val chart = card()
        chart.addView(
            UsageVersusView(this, UsageU.uActiveMs(this), UsageU.userScreenMs(this)),
            LinearLayout.LayoutParams(-1, dp(360)))
        root.addView(chart)
        root.gap(dp(20))
        root.addView(TextView(this).apply {
            text = "Mientras más tiempo Ü usa tu dispositivo, menos tiempo estás tú frente a la pantalla — y más puedes disfrutar tu vida."
            textSize = 15f
            setTextColor(Palette.text)
            gravity = Gravity.CENTER
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(dp(10), 0, dp(10), 0)
        })
        if (!builtWithAccess) {
            root.gap(dp(18))
            root.addView(button("Permitir medir tu tiempo de pantalla") {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })
            root.gap(dp(6))
            root.addView(caption("Concede acceso a estadísticas de uso para ver tu tiempo real en la pantalla."))
        }
    }

    /** Activa/desactiva el aprendizaje pasivo (movido aquí desde la burbuja). */
    private fun togglePassive(after: () -> Unit) {
        if (app.ui == null) {
            log("Activa el servicio de accesibilidad de Ü")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        val passive = app.passive
        if (!passive.active) { scope.launch { passive.start(); after() } }
        else scope.launch {
            runCatching { withContext(Dispatchers.Default) { passive.stop() } }
                .onFailure { log("Aprendizaje pasivo: ${it.message}") }
            after()
        }
    }

    /** Ejecuta un prompt con el motor mixto (Gemini computer-use + herramientas MCP). */
    private fun runPrompt(prompt: String) {
        if (app.ui == null) {
            log("Activa el servicio de accesibilidad de Ü")
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

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy(); recognizer = null
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
                .setNegativeButton("Responder con voz") { _, _ ->
                    voiceCallback = { text -> moveTaskToBack(true); if (cont.isActive) cont.resume(text) }
                    startActivityForResult(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), 2)
                }
                .show()
        }
    }

    private companion object {
        const val KEY_UI_MODE = "ui_mode"
        const val MODE_CLOUD = "cloud"
        const val MODE_USER = "user"
        const val MODE_DEV = "dev"
    }
}
