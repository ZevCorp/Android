package com.zevcorp.graph.platform

import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * TELEMETRÍA PARA EL PANEL ANDROID DEL PROVIDER STUDIO (backend Graph).
 *
 * Sube tres cosas a Supabase para poder ver, usuario por usuario, qué pide la gente y qué le
 * pasa a cada ejecución:
 *
 *  · graph_app_users — cada instalación con el NOMBRE del usuario (popup obligatorio al empezar).
 *  · graph_prompts   — cada cosa que el usuario le pide al asistente, con su desenlace.
 *  · graph_exec_logs — TODAS las líneas del LogBus (el mismo panel de desarrollador local),
 *                      correlacionadas con el prompt en curso. Logs completos, no un resumen.
 *
 * Envío por lotes (buffer + flush cada pocos segundos) para no meter latencia a la ejecución.
 * Nunca lanza: la telemetría jamás puede tumbar al asistente.
 */
object Telemetry {

    private const val PROJECT = "https://zyvfamlhlmztliexvmej.supabase.co/rest/v1"
    private const val KEY = "sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI" // publishable (cliente)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    private var appVersion = ""
    private var deviceModel = ""

    /** Prompt en curso: sus logs viajan correlacionados. Null entre ejecuciones (logs sueltos). */
    @Volatile private var currentPromptId: String? = null

    /**
     * Prompts abiertos (id → prompt y vía de entrada). Los upserts van por RPC SECURITY DEFINER
     * (graph_upsert_prompt): los clientes no tienen SELECT sobre la telemetría y sin él el
     * ON CONFLICT de PostgREST viola RLS; además HttpURLConnection ni siquiera soporta PATCH.
     */
    private val openPrompts = HashMap<String, Pair<String, String>>()

    private val pendingLogs = ArrayDeque<Pair<String?, Pair<String, String>>>() // promptId → (tag, message)

    /** Identidad de la instalación: un UUID generado una vez (no requiere cuenta). */
    val deviceId: String
        get() {
            val p = prefs ?: return ""
            p.getString("telemetryDeviceId", "")?.takeIf { it.isNotBlank() }?.let { return it }
            val id = UUID.randomUUID().toString()
            p.edit().putString("telemetryDeviceId", id).apply()
            return id
        }

    /** Nombre con el que el usuario se presentó (popup obligatorio). Blanco = aún no se presenta. */
    val userName: String get() = prefs?.getString("userName", "")?.trim().orEmpty()

    /** Cablea la telemetría al arrancar la app y enciende el flusher de logs en background. */
    fun init(preferences: SharedPreferences, version: String) {
        prefs = preferences
        appVersion = version
        deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        scope.launch {
            while (true) {
                delay(4_000)
                flushLogs()
            }
        }
    }

    /**
     * El usuario acaba de presentarse (o volvió a abrir la app): registra/refresca su tarjeta en el
     * panel. Upsert por device_id; fire-and-forget.
     */
    fun ensureUser(name: String) {
        val id = deviceId.ifBlank { return }
        if (name.isBlank()) return
        scope.launch {
            runCatching {
                val body = buildJsonObject {
                    put("p_device_id", id)
                    put("p_display_name", name)
                    put("p_device_model", deviceModel)
                    put("p_app_version", appVersion)
                }.toString()
                http("POST", "$PROJECT/rpc/graph_upsert_app_user", body)
            }.onFailure { LogBus.log("telemetry", "no pude registrar el usuario: ${it.message}") }
        }
    }

    /**
     * Arranca la sesión de un prompt: desde ya, cada línea del LogBus viaja atada a este id.
     * Devuelve el id (generado en el cliente para no esperar al servidor).
     */
    fun promptStarted(prompt: String, source: String): String {
        val id = UUID.randomUUID().toString()
        currentPromptId = id
        synchronized(openPrompts) { openPrompts[id] = prompt to source }
        val device = deviceId
        val name = userName
        scope.launch {
            runCatching {
                val body = buildJsonObject {
                    put("p_id", id)
                    put("p_device_id", device)
                    put("p_user_name", name)
                    put("p_prompt", prompt)
                    put("p_source", source)
                    put("p_status", "running")
                }.toString()
                http("POST", "$PROJECT/rpc/graph_upsert_prompt", body)
            }.onFailure { LogBus.log("telemetry", "no pude subir el prompt: ${it.message}") }
        }
        return id
    }

    /** Cierra la sesión del prompt con su desenlace (ok · error · cancelled) y el resumen final. */
    fun promptFinished(id: String, status: String, summary: String) {
        if (currentPromptId == id) currentPromptId = null
        val (prompt, source) = synchronized(openPrompts) { openPrompts.remove(id) } ?: return
        val device = deviceId
        val name = userName
        scope.launch {
            flushLogs() // que los últimos logs de la ejecución no queden esperando al próximo tick
            runCatching {
                val body = buildJsonObject {
                    put("p_id", id)
                    put("p_device_id", device)
                    put("p_user_name", name)
                    put("p_prompt", prompt)
                    put("p_source", source)
                    put("p_status", status)
                    put("p_summary", summary.take(2000))
                    put("p_finished", true)
                }.toString()
                http("POST", "$PROJECT/rpc/graph_upsert_prompt", body)
            }.onFailure { LogBus.log("telemetry", "no pude cerrar el prompt: ${it.message}") }
        }
    }

    /**
     * Cada línea del LogBus cae aquí (lo llama el propio LogBus). Solo se encola: el flusher la
     * sube por lotes. Las líneas de la telemetría misma se ignoran (evita el eco infinito).
     */
    fun enqueue(tag: String, message: String) {
        if (tag == "telemetry") return
        if (prefs == null) return
        synchronized(pendingLogs) {
            pendingLogs.addLast(currentPromptId to (tag to message))
            if (pendingLogs.size > 500) pendingLogs.removeFirst() // sin red, no crecer sin límite
        }
    }

    private fun flushLogs() {
        val batch = synchronized(pendingLogs) {
            if (pendingLogs.isEmpty()) return
            val copy = pendingLogs.toList()
            pendingLogs.clear()
            copy
        }
        val device = deviceId.ifBlank { return }
        runCatching {
            val rows = batch.joinToString(",", "[", "]") { (promptId, log) ->
                buildJsonObject {
                    put("device_id", device)
                    if (promptId != null) put("prompt_id", promptId) else put("prompt_id", JsonNull)
                    put("tag", log.first.take(60))
                    put("message", log.second.take(4000))
                }.toString()
            }
            http("POST", "$PROJECT/graph_exec_logs", rows, "Prefer" to "return=minimal")
        }.onFailure {
            // Si no salió, vuelve al buffer (al frente) y se reintenta en el próximo tick.
            synchronized(pendingLogs) { batch.asReversed().forEach { pendingLogs.addFirst(it) } }
        }
    }

    private fun http(method: String, url: String, body: String?, vararg headers: Pair<String, String>) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("apikey", KEY)
        c.setRequestProperty("Authorization", "Bearer $KEY")
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        c.disconnect()
        if (code >= 300) error("HTTP $code: ${text.take(160)}")
    }
}
