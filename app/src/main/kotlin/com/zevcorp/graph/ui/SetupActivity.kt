package com.zevcorp.graph.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.platform.MicService
import com.zevcorp.graph.platform.MiracleApi
import com.zevcorp.graph.platform.RemoteConfig
import com.zevcorp.graph.voice.GeminiLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * LA CONFIGURACIÓN INICIAL de quien NO es médico, hablada.
 *
 * En el sistema médico la nota se organiza con un system prompt que escribimos nosotros. Un
 * supervisor de planta o un asesor comercial necesita lo mismo con otra estructura, y nadie va a
 * escribir un prompt: se lo cuenta a la carita. Esta pantalla ES esa conversación —la carita
 * explica qué sabe hacer, pone un ejemplo, escucha el oficio de la persona y, cuando ya entendió,
 * el backend convierte lo dicho en SU system prompt.
 *
 * Al final se le ofrece mandar capturas de cómo organiza hoy esa información: de ahí se copia el
 * formato real (los mismos títulos, el mismo orden), que es lo que hace que el reporte salga listo
 * para pegar donde siempre lo manda.
 */
class SetupActivity : Activity() {

    private val app get() = GraphApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var face: FaceView
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var actions: LinearLayout
    private var live: GeminiLive? = null
    /** Lo que la persona contó, ya cerrado por el modelo. Con esto se genera su system prompt. */
    private var description = ""

    /* ---------- El guion: qué sabe hacer y qué necesita saber ---------- */

    private val systemInstruction = """
        Eres Ü, un asistente que vive en el teléfono de una persona y habla con ella en español, en
        voz alta, de forma cálida y directa. Tuteas. Frases cortas: esto se ESCUCHA, no se lee.

        Estás haciendo la configuración inicial de alguien que NO es médico. Tu conversación tiene
        exactamente dos partes, en este orden:

        1. EXPLÍCALE QUÉ SABES HACER, en pocas frases:
           - Que puedes escuchar en tiempo real por el micrófono mientras trabaja, y organizar
             sola la información de lo que va diciendo.
           - Dale un ejemplo concreto y cotidiano: "si en tu trabajo tienes que pasar un reporte
             cada hora, solo activas el micrófono, hablas, y yo te lo dejo organizado; después te
             lo mando por WhatsApp a quien necesites".

        2. PÍDELE LO QUE NECESITAS SABER, y escúchalo:
           - A qué se dedica, qué tipo de trabajo hace.
           - Qué información quiere que le organices.
           - Cómo la quiere organizada: con qué partes, en qué orden, qué tan detallado, a quién se
             la manda.
           Pregunta esas tres cosas de forma natural, no como un formulario. Si te contesta las tres
           de una, no se las vuelvas a preguntar. Si algo queda flojo, repregunta UNA vez, corta y
           concreta.

        Cuando ya tengas las tres cosas, llama a la función configuracion_lista con un resumen
        completo y fiel de lo que te contó, escrito en primera persona de la persona (como si lo
        hubiera escrito ella). No anuncies que vas a llamar a la función: solo llámala.

        Nunca inventes datos de su trabajo. Nunca hables de temas médicos ni clínicos. Si te
        pregunta otra cosa, respóndele en una frase y vuelve a lo que estás haciendo.
    """.trimIndent()

    /* ---------- Ciclo de vida ---------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setDecorFitsSystemWindows(false)
        setContentView(buildScreen())
        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7)
        startConversation()
    }

    override fun onDestroy() {
        super.onDestroy()
        live?.shutdown()
        MicService.stop(this)
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
            setPadding(dp(24), dp(72), dp(24), dp(28))
        }

        val faceBlock = FrameLayout(this).apply { clipChildren = false }
        faceBlock.addView(RadialGlowView(this, t.glowStrong, 0.5f),
            FrameLayout.LayoutParams(dp(220), dp(220), Gravity.CENTER))
        face = FaceView(this).apply { elevation = dp(16).toFloat() }
        faceBlock.addView(face, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
        column.addView(faceBlock, LinearLayout.LayoutParams(dp(220), dp(220)))

        status = TextView(this).apply {
            text = "Conectando…"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(t.pillText)
            typeface = Typeface.DEFAULT_BOLD
        }
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        transcript = TextView(this).apply {
            textSize = 15f
            setTextColor(t.placeholder)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val scroll = ScrollView(this).apply { addView(transcript) }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(18) })

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

    /**
     * La transcripción llega en pedacitos, no en frases: se pegan en la misma línea mientras habla
     * el mismo, y solo se abre línea nueva cuando cambia quién está hablando.
     */
    private var lastSpeaker = ""

    private fun say(who: String, text: String) {
        if (text.isBlank()) return
        if (who != lastSpeaker) {
            transcript.append(if (transcript.text.isBlank()) "$who " else "\n\n$who ")
            lastSpeaker = who
        }
        transcript.append(text)
    }

    private fun setStatus(text: String) { status.text = text }

    /* ---------- La conversación ---------- */

    private fun startConversation() {
        val key = RemoteConfig.resolve(app.prefs, "apiKey", "remoteGeminiKey", GraphApp.DEFAULT_API_KEY)
        if (key.isBlank()) {
            setStatus("Me falta la clave de voz para poder hablarte.")
            offerManualExit()
            return
        }
        val session = GeminiLive(
            context = this,
            apiKey = key,
            model = app.prefs.getString("liveModel", GeminiLive.DEFAULT_MODEL) ?: GeminiLive.DEFAULT_MODEL,
        )
        live = session
        // La escucha vive en primer plano: el micrófono no se corta si la pantalla se apaga a
        // media conversación.
        MicService.start(this)

        scope.launch {
            setStatus("Escúchame…")
            val result = session.converse(
                systemInstruction = systemInstruction,
                // La persona no tiene que hablar primero: la carita arranca sola.
                opening = "Preséntate y empieza la configuración ahora.",
                onFinish = { args, handle ->
                    description = args?.get("resumen")?.jsonPrimitive?.contentOrNull.orEmpty()
                    // Se cierra aquí a propósito: generar el system prompt tarda unos segundos y
                    // mantener el micrófono abierto mientras tanto solo capta ruido.
                    handle.close()
                },
                onAssistantText = { setStatus(""); say("Ü:", it) },
                onUserText = { say("Tú:", it) },
                onSpeaking = { speaking -> face.thinking = speaking },
            )
            MicService.stop(this@SetupActivity)
            live = null
            face.thinking = false

            val spoken = description.ifBlank { result.userTranscript }
            when {
                spoken.isBlank() && result.failed -> {
                    setStatus("Se me cayó la conexión y no alcancé a oírte.")
                    offerRetry()
                }
                spoken.isBlank() -> {
                    setStatus("No alcancé a oírte. ¿Lo intentamos otra vez?")
                    offerRetry()
                }
                else -> generateProfile(spoken)
            }
        }
    }

    /** Lo dicho se convierte en el system prompt de esta persona, guardado en el backend. */
    private fun generateProfile(spoken: String) {
        setStatus("Dame un segundo, estoy armando tu formato…")
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { MiracleApi.createOrganizerProfile(spoken) }
            }
            outcome.onSuccess { (profile, confirmation) ->
                app.prefs.edit()
                    .putString("profession", "otra")
                    .putString("occupation", profile.occupation)
                    .putBoolean("organizerReady", true)
                    .apply()
                LogBus.log("setup", "✔ configuración lista: ${profile.occupation}")
                setStatus(confirmation.ifBlank { "Listo. Ya sé cómo organizar tu información." })
                say("Ü:", "Te voy a organizar: ${profile.sections.joinToString(" · ")}")
                offerScreenshots()
            }.onFailure { error ->
                LogBus.log("setup", "✖ no pude configurar: ${error.message}")
                setStatus(error.message ?: "No pude guardar tu configuración.")
                offerRetry()
            }
        }
    }

    /* ---------- Ejemplos: las capturas de cómo organiza hoy ---------- */

    private fun offerScreenshots() {
        actions.removeAllViews()
        actions.addView(TextView(this).apply {
            text = "¿Tienes ejemplos de cómo organizas hoy esa información? Mándame una o varias " +
                "capturas y copio ese mismo formato."
            textSize = 14f
            setTextColor(miracleTheme().placeholder)
            setPadding(0, 0, 0, dp(12))
        })
        actions.addView(pill("Enviar capturas de ejemplo", primary = true) { pickScreenshots() })
        actions.addView(pill("Ahora no, empecemos") { finishSetup() })
    }

    private fun pickScreenshots() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            type = "image/*"
        }
        startActivityForResult(Intent.createChooser(intent, "Elige tus ejemplos"), PICK_SCREENSHOTS)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_SCREENSHOTS || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { clip -> repeat(clip.itemCount) { uris += clip.getItemAt(it).uri } }
        data.data?.let { uris += it }
        if (uris.isEmpty()) return

        actions.removeAllViews()
        setStatus("Estoy mirando tus ejemplos…")
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val images = uris.take(MAX_SCREENSHOTS).mapNotNull { readAsDataUrl(it) }
                    require(images.isNotEmpty()) { "No pude leer esas imágenes." }
                    MiracleApi.addOrganizerSamples(images)
                }
            }
            outcome.onSuccess { (profile, confirmation) ->
                setStatus(confirmation.ifBlank { "Listo, ya copié tu formato." })
                say("Ü:", "Ahora organizo así: ${profile.sections.joinToString(" · ")}")
                actions.removeAllViews()
                actions.addView(pill("Empezar", primary = true) { finishSetup() })
                actions.addView(pill("Mandar más capturas") { pickScreenshots() })
            }.onFailure { error ->
                setStatus(error.message ?: "No pude leer esas capturas.")
                offerScreenshots()
            }
        }
    }

    /** Una captura como data URL, reescalada para no mandar 12 megapíxeles por la red. */
    private fun readAsDataUrl(uri: android.net.Uri): String? = runCatching {
        val source = android.graphics.ImageDecoder.createSource(contentResolver, uri)
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_IMAGE_SIDE) {
                val factor = MAX_IMAGE_SIDE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * factor).toInt().coerceAtLeast(1),
                    (info.size.height * factor).toInt().coerceAtLeast(1)
                )
            }
        }
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrElse {
        LogBus.log("setup", "no pude leer una captura: ${it.message}")
        null
    }

    /* ---------- Salidas ---------- */

    private fun finishSetup() {
        startActivity(Intent(this, NoteActivity::class.java))
        finish()
    }

    private fun offerRetry() {
        actions.removeAllViews()
        actions.addView(pill("Intentar de nuevo", primary = true) {
            actions.removeAllViews()
            transcript.text = ""
            lastSpeaker = ""
            startConversation()
        })
        actions.addView(pill("Configurar después") { finish() })
    }

    private fun offerManualExit() {
        actions.removeAllViews()
        actions.addView(pill("Volver") { finish() })
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

    private companion object {
        const val PICK_SCREENSHOTS = 91
        /** El backend acepta hasta 8 capturas por llamada. */
        const val MAX_SCREENSHOTS = 8
        const val MAX_IMAGE_SIDE = 1600
    }
}
