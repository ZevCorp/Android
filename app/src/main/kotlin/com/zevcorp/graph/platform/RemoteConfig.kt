package com.zevcorp.graph.platform

import com.zevcorp.graph.GraphApp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class MobileConfigResponse(
    val ok: Boolean = false,
    val geminiKey: String? = null,
    val openaiKey: String? = null,
    val deepgramKey: String? = null,
    val neo4jUri: String? = null,
    val neo4jUser: String? = null,
    val neo4jPass: String? = null,
)

/**
 * Trae las API keys desde el backend (el mismo Vercel del cliente Windows, ver `backend/`) en vez de
 * depender de que el build las traiga horneadas (`apikey.properties`, fácil de olvidar al compilar) o
 * de que el usuario las pegue a mano en el panel de Desarrollador. Se llama una vez al arrancar
 * (`GraphApp.onCreate`); el resultado se cachea en prefs bajo claves "remote*" DISTINTAS de las que
 * edita el usuario — así una key puesta a mano en la UI SIEMPRE gana (ver [GraphApp.resolvedKey]).
 *
 * Falla en silencio: sin red, sin `ANDROID_CLIENT_TOKEN` configurado en el servidor o el server caído,
 * la app sigue con lo último cacheado (u offline, con el default horneado). Nunca bloquea el arranque.
 *
 * Nota de seguridad: el teléfono recibe la key en claro (su cerebro corre on-device, a diferencia de
 * Windows) — es una mejora sobre hornearla en el APK (rotable sin nueva versión, no vive en un archivo
 * estático descargable), no una eliminación de la exposición. Ver DEPLOY.md del backend.
 */
object RemoteConfig {

    private const val ENDPOINT = "https://u-windows-backend.vercel.app/api/mobile/config"

    // Token PROPIO de Android (no el de Windows): mismo alcance de "freno al abuso" que el ClientToken
    // hardcodeado en el cliente Windows (Config.cs) — no es secreto de verdad (viaja en el APK), solo
    // evita que cualquiera sin este valor pueda leer el endpoint. Debe coincidir con la env var
    // ANDROID_CLIENT_TOKEN del backend en Vercel.
    private const val TOKEN = "0a5fadd9601355696a68d4f944b7b7c926f96c2a0b0566f6"

    private val json = Json { ignoreUnknownKeys = true }

    fun refresh() {
        val app = GraphApp.instance
        runCatching {
            val c = URL(ENDPOINT).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 6_000
            c.readTimeout = 6_000
            c.setRequestProperty("Authorization", "Bearer $TOKEN")
            val code = c.responseCode
            if (code != 200) {
                LogBus.log("config", "config remota: HTTP $code (sigue con lo cacheado)")
                c.disconnect()
                return
            }
            val body = c.inputStream.bufferedReader().readText()
            c.disconnect()
            val cfg = json.decodeFromString(MobileConfigResponse.serializer(), body)
            val e = app.prefs.edit()
            cfg.geminiKey?.let { e.putString("remoteApiKey", it) }
            cfg.openaiKey?.let { e.putString("remoteOpenaiKey", it) }
            cfg.deepgramKey?.let { e.putString("remoteDeepgramKey", it) }
            cfg.neo4jUri?.let { e.putString("remoteNeo4jUri", it) }
            cfg.neo4jUser?.let { e.putString("remoteNeo4jUser", it) }
            cfg.neo4jPass?.let { e.putString("remoteNeo4jPass", it) }
            e.apply()
            LogBus.log("config", "keys remotas actualizadas")
        }.onFailure { LogBus.log("config", "no se pudo traer config remota: ${it.message?.take(80)}") }
    }
}
