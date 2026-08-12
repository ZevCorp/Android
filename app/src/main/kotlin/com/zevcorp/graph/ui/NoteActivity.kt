package com.zevcorp.graph.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.platform.MicService
import com.zevcorp.graph.platform.MiracleApi
import com.zevcorp.graph.voice.Transcriber
import com.zevcorp.graph.voice.defaultTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EL MOVIMIENTO DE TODOS LOS DÍAS: hablas, y sale la información organizada.
 *
 * Es la misma experiencia que ya existe en la web para los médicos —audio → transcripción →
 * backend → nota— traída al teléfono, y contra los mismos endpoints del backend Graph. La única
 * diferencia entre un médico y cualquier otra profesión es a QUÉ ruta se manda la transcripción:
 *
 *  · médico          → /api/v1/pipeline           (el motor clínico, igual que la web)
 *  · otra profesión  → /api/v1/organizer/organize  (el system prompt que se hizo a su medida)
 *
 * Lo demás —grabar, ver la transcripción, revisar el reporte, mandarlo— es idéntico, porque el
 * problema es el mismo: alguien habla mientras trabaja y necesita el resultado listo para enviar.
 */
class NoteActivity : Activity() {

    private val app get() = GraphApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var face: FaceView
    private lateinit var status: TextView
    private lateinit var body: TextView
    private lateinit var actions: LinearLayout

    private var transcriber: Transcriber? = null
    private var listenJob: Job? = null
    /** Lo transcrito hasta ahora en esta sesión (se acumula entre tandas de escucha). */
    private val heard = StringBuilder()
    private var report: MiracleApi.Report? = null

    private val isDoctor get() = app.prefs.getString("profession", "otra") == "medico"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setDecorFitsSystemWindows(false)
        setContentView(buildScreen())
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 8)
        idle()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        scope.cancel()
    }

    /* ---------- Pantalla ---------- */

    private fun buildScreen(): View {
        val t = miracleTheme()
        val root = FrameLayout(this).apply { clipChildren = false }
        root.addView(MiracleBgView(this, t), FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val faceBlock = FrameLayout(this).apply { clipChildren = false }
        faceBlock.addView(RadialGlowView(this, t.glowStrong, 0.45f),
            FrameLayout.LayoutParams(dp(180), dp(180), Gravity.CENTER))
        face = FaceView(this).apply { elevation = dp(12).toFloat() }
        faceBlock.addView(face, FrameLayout.LayoutParams(dp(88), dp(88), Gravity.CENTER))
        column.addView(faceBlock, LinearLayout.LayoutParams(dp(180), dp(180)))

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(t.pillText)
            typeface = Typeface.DEFAULT_BOLD
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2))

        body = TextView(this).apply {
            textSize = 15f
            setTextColor(t.pillText)
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        column.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })

        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(actions, LinearLayout.LayoutParams(-1, -2))

        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val sys = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            column.setPadding(dp(24), sys.top + dp(24), dp(24), sys.bottom + dp(20))
            insets
        }
        return root
    }

    /* ---------- Estados ---------- */

    private fun idle() {
        face.thinking = false
        status.text = if (isDoctor) "Toca y dicta la consulta" else "Toca y cuéntame"
        body.text = heard.toString()
        actions.removeAllViews()
        actions.addView(pill("Empezar a escuchar", primary = true) { startListening() })
        if (heard.isNotBlank()) {
            actions.addView(pill("Organizar lo que llevo") { organize() })
            actions.addView(pill("Borrar y empezar de cero") { heard.clear(); report = null; idle() })
        }
    }

    /**
     * Escucha por tandas: el transcriptor corta con el silencio, y mientras siga encendido se
     * vuelve a abrir. Así una persona puede hablar veinte minutos con pausas sin perder nada, que
     * es como se habla de verdad mientras se trabaja.
     */
    private fun startListening() {
        if (listenJob?.isActive == true) return
        face.thinking = true
        status.text = "Te escucho… toca para terminar"
        actions.removeAllViews()
        actions.addView(pill("Terminar y organizar", primary = true) { stopListening(); organize() })
        actions.addView(pill("Pausar") { stopListening(); idle() })

        MicService.start(this)
        listenJob = scope.launch {
            try {
                while (true) {
                    val engine = defaultTranscriber(this@NoteActivity)
                    transcriber = engine
                    engine.onPartial = { partial -> body.text = "${heard}$partial" }
                    val text = withContext(Dispatchers.IO) {
                        runCatching { engine.listen() }.getOrElse { "" }
                    }
                    transcriber = null
                    if (text.isBlank()) {
                        // Sin permiso de micrófono, o silencio total: sin esta pausa el bucle
                        // giraría en vacío quemando batería.
                        delay(250)
                        continue
                    }
                    if (heard.isNotEmpty()) heard.append(' ')
                    heard.append(text.trim())
                    body.text = heard.toString()
                }
            } finally {
                MicService.stop(this@NoteActivity)
            }
        }
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        transcriber?.stop()
        transcriber = null
        MicService.stop(this)
        face.thinking = false
    }

    /** La transcripción se va al backend y vuelve organizada. */
    private fun organize() {
        val transcript = heard.toString().trim()
        if (transcript.isBlank()) {
            Toast.makeText(this, "Todavía no te he oído nada", Toast.LENGTH_SHORT).show()
            idle()
            return
        }
        face.thinking = true
        status.text = "Organizando…"
        actions.removeAllViews()
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    if (isDoctor) MiracleApi.medicalNote(transcript, "android-${System.currentTimeMillis()}")
                    else MiracleApi.organize(transcript)
                }
            }
            face.thinking = false
            outcome.onSuccess { show(it) }.onFailure { error ->
                LogBus.log("nota", "✖ no pude organizar: ${error.message}")
                status.text = error.message ?: "No pude organizar esto."
                actions.removeAllViews()
                actions.addView(pill("Reintentar", primary = true) { organize() })
                actions.addView(pill("Seguir dictando") { idle() })
            }
        }
    }

    private fun show(result: MiracleApi.Report) {
        report = result
        status.text = result.title
        body.text = buildString {
            append(result.text)
            if (result.warnings.isNotEmpty()) {
                append("\n\n⚠ ")
                append(result.warnings.joinToString("\n⚠ "))
            }
        }
        actions.removeAllViews()
        actions.addView(pill("Enviar por WhatsApp", primary = true) { sendToWhatsApp(result.text) })
        actions.addView(pill("Compartir") { share(result.text) })
        actions.addView(pill("Seguir dictando") { status.text = ""; idle() })
    }

    /* ---------- Salidas: el reporte tiene que terminar en manos de alguien ---------- */

    /**
     * WhatsApp con el texto ya puesto: la persona elige el destinatario en la propia app. No se
     * intenta adivinar a quién va — equivocarse ahí es mandar el reporte a quien no era.
     */
    private fun sendToWhatsApp(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage("com.whatsapp")
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "No encontré WhatsApp; te lo comparto por otro lado", Toast.LENGTH_SHORT).show()
            share(text)
        }
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Enviar reporte"))
    }

    /* ---------- Piezas de UI ---------- */

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit): View {
        val t = miracleTheme()
        return TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) t.pill else t.pillText)
            background = rounded(if (primary) t.pillText else t.pill, dp(26).toFloat(), t.border)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
