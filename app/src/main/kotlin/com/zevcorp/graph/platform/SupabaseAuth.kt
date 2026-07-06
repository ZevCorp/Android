package com.zevcorp.graph.platform

import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * La cuenta del usuario (Supabase Auth, email + contraseña). Es lo que separa las dos capas de
 * conocimiento del asistente:
 *
 *  · El mapa de UI de las apps (graph_learned_tools) es TRANSVERSAL: lo que un usuario le enseña
 *    de la calculadora o de WhatsApp les sirve a todos. Se lee sin cuenta; aportar requiere sesión.
 *  · La knowledge-base PERSONAL (graph_memory: "mi mamá está guardada como 'Ale'", "soy
 *    desarrollador…") pertenece SOLO a la cuenta del usuario (RLS por user_id en el servidor).
 *
 * El registro pasa por la Edge Function graph-signup (crea la cuenta ya confirmada, marcada con
 * metadata app=graph para que el backend no la trate como usuario de otras apps del proyecto).
 * El login es el grant de contraseña normal de GoTrue; la sesión (access + refresh token) vive en
 * las prefs y se renueva sola cuando está por vencer.
 */
class SupabaseAuth(private val prefs: SharedPreferences) {

    private val json = Json { ignoreUnknownKeys = true }

    val loggedIn get() = userId.isNotBlank() && refreshToken.isNotBlank()
    val userId: String get() = prefs.getString("authUserId", "") ?: ""
    val email: String get() = prefs.getString("authEmail", "") ?: ""
    private val refreshToken: String get() = prefs.getString("authRefresh", "") ?: ""

    /** Crea la cuenta (queda lista para entrar: sin confirmación por correo). Null = ok. */
    fun signUp(email: String, password: String): String? = runCatching {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val (code, text) = http("POST", "$PROJECT/functions/v1/graph-signup", body.toString())
        if (code >= 300) return friendlyError(text)
        null
    }.getOrElse { "Sin conexión: ${it.message}" }

    /** Inicia sesión y guarda la sesión completa en prefs. Null = ok. */
    fun signIn(email: String, password: String): String? = runCatching {
        val body = buildJsonObject { put("email", email.trim()); put("password", password) }
        val (code, text) = http("POST", "$AUTH/token?grant_type=password", body.toString())
        if (code >= 300) return friendlyError(text)
        saveSession(text)
        null
    }.getOrElse { "Sin conexión: ${it.message}" }

    /** Cierra la sesión local (los recuerdos quedan en la cuenta, en la nube). */
    fun signOut() {
        runCatching { http("POST", "$AUTH/logout", "{}", bearer = prefs.getString("authAccess", "")) }
        prefs.edit()
            .remove("authUserId").remove("authEmail")
            .remove("authAccess").remove("authRefresh").remove("authExpiresAt")
            .apply()
    }

    /**
     * Token vigente para llamar a la API con la identidad del usuario (null si no hay sesión).
     * Si está por vencer (o ya venció) lo renueva con el refresh token; si la renovación es
     * rechazada por el servidor (sesión revocada), limpia la sesión local.
     */
    @Synchronized
    fun accessToken(): String? {
        if (!loggedIn) return null
        val access = prefs.getString("authAccess", "") ?: ""
        val expiresAt = prefs.getLong("authExpiresAt", 0L)
        if (access.isNotBlank() && System.currentTimeMillis() < expiresAt - 60_000) return access
        return runCatching {
            val body = buildJsonObject { put("refresh_token", refreshToken) }
            val (code, text) = http("POST", "$AUTH/token?grant_type=refresh_token", body.toString())
            if (code >= 300) {
                // 400 = refresh token inválido/revocado → la sesión murió de verdad.
                if (code in 400..499) {
                    LogBus.log("auth", "sesión expirada: vuelve a iniciar sesión")
                    signOut()
                }
                return null
            }
            saveSession(text)
            prefs.getString("authAccess", "")
        }.getOrElse { LogBus.log("auth", "no pude renovar la sesión: ${it.message}"); access.ifBlank { null } }
    }

    private fun saveSession(responseJson: String) {
        val o = json.parseToJsonElement(responseJson).jsonObject
        val user = o["user"]?.jsonObject
        val expiresIn = o["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        prefs.edit()
            .putString("authAccess", o["access_token"]?.jsonPrimitive?.content ?: "")
            .putString("authRefresh", o["refresh_token"]?.jsonPrimitive?.content ?: "")
            .putLong("authExpiresAt", System.currentTimeMillis() + expiresIn * 1000)
            .putString("authUserId", user?.get("id")?.jsonPrimitive?.content ?: "")
            .putString("authEmail", user?.get("email")?.jsonPrimitive?.content ?: "")
            .apply()
    }

    /** Traduce los errores de GoTrue/la Edge Function a un mensaje corto en español. */
    private fun friendlyError(text: String): String {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val raw = o?.get("error")?.jsonPrimitive?.content
            ?: o?.get("msg")?.jsonPrimitive?.content
            ?: o?.get("error_description")?.jsonPrimitive?.content
            ?: text.take(120)
        return when {
            raw.contains("Invalid login credentials", true) -> "Correo o contraseña incorrectos"
            raw.contains("Email not confirmed", true) -> "Confirma tu correo para entrar"
            else -> raw
        }
    }

    private fun http(method: String, url: String, body: String? = null, bearer: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("apikey", KEY)
        if (!bearer.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $bearer")
        c.setRequestProperty("Content-Type", "application/json")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        return code to text
    }

    companion object {
        private const val PROJECT = "https://zyvfamlhlmztliexvmej.supabase.co"
        private const val AUTH = "$PROJECT/auth/v1"
        private const val KEY = "sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI" // publishable (cliente)
    }
}
