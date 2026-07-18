package com.zevcorp.graph.voice

import android.content.Context
import android.media.MediaPlayer
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.LogBus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val OA_BASE = "https://api.openai.com"

/**
 * VOZ DE NUEVA GENERACIÓN: síntesis con OpenAI (`/v1/audio/speech`, modelo `gpt-4o-mini-tts`) que
 * suena natural y humana, muy por encima del TextToSpeech on-device. Descarga el MP3 y lo reproduce
 * con MediaPlayer. Es un COMPLEMENTO del `Voice` existente: `speak()` devuelve `false` si no está
 * habilitada o falla (sin key, sin red…), y quien la llama cae al TTS del sistema — nunca queda muda.
 *
 * Se activa/elige desde el mismo lugar que el resto de la voz (pref `voiceEngine`/`openaiVoice`) y
 * reutiliza la key de OpenAI del proveedor de computer-use (pref `openaiKey`).
 */
class OpenAiTts(private val context: Context) {

    @Volatile private var player: MediaPlayer? = null

    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    private fun key() = (GraphApp.instance.prefs.getString("openaiKey", GraphApp.DEFAULT_OPENAI_KEY)
        ?: GraphApp.DEFAULT_OPENAI_KEY).trim()
    private fun voice() = GraphApp.instance.prefs.getString("openaiVoice", "verse") ?: "verse"

    /** ¿El usuario eligió la voz de OpenAI Y hay key para usarla? (si no, quien llama usa el TTS del sistema). */
    fun enabled(): Boolean =
        GraphApp.instance.prefs.getString("voiceEngine", "openai") == "openai" && key().isNotBlank()

    /** Sintetiza y reproduce. Devuelve true si sonó; false para que quien llama caiga al TTS del sistema. */
    suspend fun speak(text: String): Boolean {
        if (!enabled()) return false
        val clean = text.trim()
        if (clean.isBlank()) return false
        val bytes = withContext(Dispatchers.IO) { runCatching { fetch(key(), clean) }.getOrNull() } ?: return false
        if (bytes.isEmpty()) return false
        val f = File(context.cacheDir, "oa_tts_${System.nanoTime()}.mp3")
        if (runCatching { f.writeBytes(bytes) }.isFailure) return false
        return withContext(Dispatchers.Main) {
            runCatching {
                stop()
                val session = GraphApp.instance.voiceSession
                val mp = MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener {
                    session.unbindPlayer(it); session.endSpeaking()
                    it.release(); f.delete(); if (player === it) player = null
                }
                mp.setOnErrorListener { p, _, _ ->
                    session.unbindPlayer(p); session.endSpeaking()
                    runCatching { p.release() }; f.delete(); if (player === p) player = null; true
                }
                mp.prepare()
                // Volumen propio del asistente: la sesión aplica la ganancia elegida al reproductor y
                // publica su barra independiente en el panel de volumen del sistema.
                session.bindPlayer(mp)
                session.beginSpeaking()
                mp.start()
                player = mp
                true
            }.getOrElse { LogBus.log("openai", "TTS play falló: ${it.message?.take(80)}"); false }
        }
    }

    fun stop() {
        runCatching {
            player?.let {
                GraphApp.instance.voiceSession.unbindPlayer(it)
                if (it.isPlaying) it.stop(); it.release()
            }
        }
        if (player != null) runCatching { GraphApp.instance.voiceSession.endSpeaking() }
        player = null
    }

    private fun fetch(key: String, text: String): ByteArray {
        val payload: JsonObject = buildJsonObject {
            put("model", "gpt-4o-mini-tts")
            put("input", text)
            put("voice", voice())
            put("response_format", "mp3")
            put("instructions", "Habla de forma cálida, natural y cercana, con energía amable y muy humana.")
        }
        val c = URL("$OA_BASE/v1/audio/speech").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.use { it.write(Json.encodeToString(JsonObject.serializer(), payload).toByteArray()) }
        val code = c.responseCode
        if (code >= 300) {
            LogBus.log("openai", "TTS HTTP $code: ${c.errorStream?.bufferedReader()?.readText()?.take(160)}")
            c.disconnect(); return ByteArray(0)
        }
        val bytes = c.inputStream.readBytes()
        c.disconnect()
        return bytes
    }
}
