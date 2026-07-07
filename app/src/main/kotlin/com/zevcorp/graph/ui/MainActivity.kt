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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.platform.Release
import com.zevcorp.graph.platform.Updater
import com.zevcorp.graph.platform.UsageU
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
    private lateinit var accountBody: LinearLayout
    private var voiceCallback: ((String) -> Unit)? = null
    /** Reconocedor de voz en-app (sin Activity), como el de la burbuja flotante. */
    private var recognizer: android.speech.SpeechRecognizer? = null
    /** Tema con el que se construyó esta pantalla: si cambia (desde la burbuja), se recrea. */
    private var builtWithMode = Palette.mode
    /** Vista actual: nube (principal), usuario (gráfica "versus") o desarrollador (todo). */
    private var mode = MODE_CLOUD
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
        val setupRow = row()
        setupRow.addView(button("Guardar keys") {
            app.prefs.edit()
                .putString("apiKey", keyInput.text.toString().trim())
                .putString("deepgramKey", deepgramInput.text.toString().trim())
                .apply()
            log("API keys guardadas")
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
        refreshMcpPanel()
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

    override fun onResume() {
        super.onResume()
        // Si el tema cambió desde la burbuja mientras esto estaba en segundo plano, recrear con los
        // colores nuevos (blanco/negro coherente con la carita).
        if (builtWithMode != Palette.mode) { recreate(); return }
        if (mode == MODE_USER) {
            // Al volver de conceder el acceso a uso, redibuja la gráfica con el tiempo real.
            if (builtWithAccess != UsageU.hasUsageAccess(this)) recreate()
            return
        }
        if (::mcpPanel.isInitialized) refreshMcpPanel() // recién llegado de enseñar en la burbuja
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

    /** Pantalla principal: la foto exacta de fondo + la barra (recortada de la propia foto) + toggle. */
    private fun buildCloudScreen(): View {
        val frame = android.widget.FrameLayout(this)
        val bg = android.widget.ImageView(this).apply {
            setImageResource(com.zevcorp.graph.R.drawable.cloud_sky_bg)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(bg, android.widget.FrameLayout.LayoutParams(-1, -1))

        val overlay = android.widget.FrameLayout(this)
        frame.addView(overlay, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Respeta el notch/barra de estado: separa el contenido de los bordes del sistema.
        overlay.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // Toggle (cicla las 3 vistas), arriba a la derecha, sutil.
        overlay.addView(modeToggle(), android.widget.FrameLayout.LayoutParams(-2, -2,
            Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(14) })

        // La barra de nube central (la nube real de la foto) con la frase y los botones mic/enviar.
        overlay.addView(buildCloudBar(), android.widget.FrameLayout.LayoutParams(-1, -2,
            Gravity.CENTER).apply { leftMargin = dp(18); rightMargin = dp(18) })
        return frame
    }

    /**
     * La barra de escritura: la nube real recortada de la foto (idéntica a la imagen, sin lupa) como
     * fondo; encima, la frase de bienvenida y, a la derecha, el micrófono permanente y sutil (⇄ enviar
     * cuando se escribe). Solo esta barra se dibuja/coloca con código; la textura es la de la foto.
     */
    private fun buildCloudBar(): View {
        val container = android.widget.FrameLayout(this)
        val cloud = android.widget.ImageView(this).apply {
            setImageResource(com.zevcorp.graph.R.drawable.cloud_bar)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
        }
        container.addView(cloud, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        val bar = row().apply { setPadding(dp(34), dp(6), dp(16), dp(6)) }
        val input = EditText(this).apply {
            hint = "¿Qué puedo hacer hoy por ti?"
            setHintTextColor(0xFF6B7E8C.toInt())
            setTextColor(0xFF33454F.toInt())
            textSize = 15f
            background = null
            maxLines = 2
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        fun submit() {
            val p = input.text.toString().trim()
            if (p.isBlank()) return
            input.setText("")
            runPrompt(p)
        }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }

        // Íconos sobrios sobre la nube (como la lupa original): mic permanente y sutil a la derecha;
        // enviar solo aparece al escribir. Sin abrir ningún pop-up de voz.
        val g = dp(40)
        val mic = IconView(this, Icon.MIC, tint = 0xFF5E6E7A.toInt()).apply {
            alpha = 0.55f; setOnClickListener { startVoice() }
        }
        val send = IconView(this, Icon.SEND, tint = 0xFF2E80D8.toInt()).apply {
            visibility = View.GONE; setOnClickListener { submit() }
        }
        val slot = android.widget.FrameLayout(this)
        slot.addView(mic, android.widget.FrameLayout.LayoutParams(g, g))
        slot.addView(send, android.widget.FrameLayout.LayoutParams(g, g))

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val typing = !s.isNullOrBlank()
                send.visibility = if (typing) View.VISIBLE else View.GONE
                mic.visibility = if (typing) View.GONE else View.VISIBLE
            }
        })

        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(slot, LinearLayout.LayoutParams(g, g))
        container.addView(bar, android.widget.FrameLayout.LayoutParams(-1, -1))
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
        if (!passive.active) { passive.start(); after() }
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
