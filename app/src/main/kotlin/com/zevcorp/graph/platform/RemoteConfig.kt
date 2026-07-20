package com.zevcorp.graph.platform

import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * CONFIGURACIÓN DISTRIBUIDA DESDE EL BACKEND (tabla graph_client_config en Supabase).
 *
 * El usuario final ya no digita API keys a mano: al instalar la app, las keys de OpenAI/Gemini/
 * Deepgram y los defaults (proveedor y modelo) bajan solos del backend y quedan cacheados en prefs.
 * El Provider Studio (backend Graph) es quien edita esa fila con service-role.
 *
 * Orden de resolución de cada key (lo respeta GraphApp):
 *   1. Lo que el usuario puso a mano en la UI (prefs) — siempre manda.
 *   2. Lo distribuido por el backend (este caché, prefs "remote*").
 *   3. Lo horneado al compilar (BuildConfig, apikey.properties) — último recurso.
 *
 * Offline-first: si no hay red se usa el último caché; nunca lanza.
 */
object RemoteConfig {

    private const val URL_CONFIG =
        "https://zyvfamlhlmztliexvmej.supabase.co/rest/v1/graph_client_config" +
            "?id=eq.1&select=openai_key,gemini_key,deepgram_key,default_provider,default_openai_model,default_gemini_model"
    private const val KEY = "sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI" // publishable (cliente)

    private val json = Json { ignoreUnknownKeys = true }

    /** Baja la config del backend y la cachea en prefs. Silencioso: si falla, queda el caché previo. */
    fun refresh(prefs: SharedPreferences) {
        runCatching {
            val c = URL(URL_CONFIG).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Authorization", "Bearer $KEY")
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            c.disconnect()
            if (code >= 300) error("HTTP $code: ${text.take(160)}")
            val row = json.parseToJsonElement(text).jsonArray.firstOrNull()?.jsonObject ?: return
            fun str(k: String) = row[k]?.jsonPrimitive?.content?.trim() ?: ""
            prefs.edit()
                .putString("remoteOpenaiKey", str("openai_key"))
                .putString("remoteGeminiKey", str("gemini_key"))
                .putString("remoteDeepgramKey", str("deepgram_key"))
                .putString("remoteProvider", str("default_provider"))
                .putString("remoteOpenaiModel", str("default_openai_model"))
                .putString("remoteGeminiModel", str("default_gemini_model"))
                .apply()
            LogBus.log("config", "⚙ config del backend aplicada (proveedor ${str("default_provider")})")
        }.onFailure { LogBus.log("config", "⚙ sin config remota (uso el caché): ${it.message}") }
    }

    /** Resuelve un valor con la cadena usuario → backend → BuildConfig. Blanco nunca gana. */
    fun resolve(prefs: SharedPreferences, userKey: String, remoteKey: String, compiled: String): String {
        val user = prefs.getString(userKey, "")?.sanitized().orEmpty()
        if (user.isNotBlank() && user != compiled) return user
        val remote = prefs.getString(remoteKey, "")?.sanitized().orEmpty()
        if (remote.isNotBlank()) return remote
        return user.ifBlank { compiled.sanitized() }
    }

    // Una key nunca lleva espacios/saltos de línea válidos: un \n pegado por error tumbaba
    // TODAS las llamadas con "Unexpected char 0x0a in header value".
    private fun String.sanitized() = filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()
}
