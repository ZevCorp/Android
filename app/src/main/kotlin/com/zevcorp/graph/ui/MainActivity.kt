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
    /** La barra de vidrio central; se desplaza hacia arriba cuando aparece el teclado. */
    private var cloudBar: View? = null
    private var imeShown = false
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
            // Íconos claros de la barra de estado sobre el azul del cielo.
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
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
        // La app pasó a primer plano: en la vista principal la carita se asienta pequeña al inicio
        // de la barra (ahí es el micrófono); en las otras vistas, arriba al centro.
        if (mode == MODE_CLOUD) dockFaceToBar() else bubble()?.dockToApp()
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
        // La app pasó a segundo plano (incluye moveTaskToBack al ejecutar algo): la carita
        // regresa a donde estaba y reanuda su reposo normal.
        bubble()?.undockFromApp()
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

    /* ---------- Vista principal: la foto de nubes ---------- */

    /** Pantalla principal: la foto exacta de fondo + la barra de vidrio flotante + toggle. */
    private fun buildCloudScreen(): View {
        val frame = android.widget.FrameLayout(this)
        val bg = android.widget.ImageView(this).apply {
            setImageResource(com.zevcorp.graph.R.drawable.cloud_sky_bg)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(bg, android.widget.FrameLayout.LayoutParams(-1, -1))

        val overlay = android.widget.FrameLayout(this)
        frame.addView(overlay, android.widget.FrameLayout.LayoutParams(-1, -1))

        val bar = buildCloudBar()
        cloudBar = bar

        // Insets: separa del notch/barra de estado y, cuando aparece el teclado, sube la barra "un
        // poco" (la mitad del alto que tapa el teclado) con una animación suave.
        overlay.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            val overlap = (ime.bottom - bars.bottom).coerceAtLeast(0)
            val show = overlap > dp(80)
            if (show != imeShown) {
                imeShown = show
                bar.animate().cancel()
                bar.animate().translationY(if (show) -overlap * 0.5f else 0f).setDuration(230)
                    .withEndAction {
                        // La carita asentada al inicio de la barra la sigue en su nueva altura.
                        val loc = IntArray(2)
                        bar.getLocationOnScreen(loc)
                        bubble()?.trackBar(loc[0] + dp(12), loc[1] + bar.height / 2)
                    }
                    .start()
            }
            insets
        }

        // El panel de Desarrollador queda OCULTO para el usuario final: sin ícono ni fondo, un área
        // reducida arriba a la derecha (misma posición que antes el toggle) que solo reacciona a un
        // TRIPLE toque. El modo Usuario ("Versus") deja de ser alcanzable desde esta pantalla.
        overlay.addView(secretDevArea(), android.widget.FrameLayout.LayoutParams(dp(40), dp(40),
            Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(14) })

        overlay.addView(bar, android.widget.FrameLayout.LayoutParams(-1, -2,
            Gravity.CENTER).apply { leftMargin = dp(22); rightMargin = dp(22) })
        return frame
    }

    /**
     * La barra de escritura "liquid glass", calcada del diseño (LiquidGlassDrawable: mitad superior
     * lechosa, cuerpo transparente y luz de rebote abajo). Sin botón de micrófono: la carita
     * flotante se asienta pequeña al INICIO de la barra (dockToBar) y hace de micrófono al tocarla.
     * Funcional: crece hasta 3 líneas al escribir; el botón enviar (blanco) aparece solo con texto.
     */
    private fun buildCloudBar(): View {
        val container = android.widget.FrameLayout(this).apply {
            background = LiquidGlassDrawable(dp(32).toFloat(), dp(2).toFloat() * 0.75f)
            // Sombra suave bajo la píldora (el contorno sigue al alto real: cápsula hasta 3 líneas).
            elevation = dp(10).toFloat()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val r = minOf(view.height / 2f, dp(32).toFloat())
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            // El padding izquierdo deja el hueco donde se asienta la carita pequeña (el "mic").
            setPadding(dp(48), dp(17), dp(18), dp(17))
        }

        val bar = row()
        val input = EditText(this).apply {
            hint = "¿Qué puedo hacer hoy por ti?"
            setHintTextColor(0xF2FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x33203040)
            textSize = 16f
            background = null
            // Multilínea real: crece hasta 3 líneas y luego hace scroll interno.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            maxLines = 3
            setHorizontallyScrolling(false)
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        fun submit() {
            val p = input.text.toString().trim()
            if (p.isBlank()) return
            input.setText("")
            input.clearFocus()
            runPrompt(p)
        }

        // Enviar (blanco), solo cuando hay texto. El micrófono ya no existe: lo reemplaza la carita.
        val g = dp(40)
        val send = IconView(this, Icon.SEND, tint = Color.WHITE, shadow = true).apply {
            visibility = View.GONE; setLayerType(View.LAYER_TYPE_SOFTWARE, null); setOnClickListener { submit() }
        }
        val slot = android.widget.FrameLayout(this)
        slot.addView(send, android.widget.FrameLayout.LayoutParams(g, g))

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                send.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        })

        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(slot, LinearLayout.LayoutParams(g, g))
        container.addView(bar, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        return container
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
