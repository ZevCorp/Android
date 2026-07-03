package com.zevcorp.graph.platform

import android.content.SharedPreferences
import com.zevcorp.graph.BuildConfig
import graph.core.domain.StepStatus
import graph.core.domain.Workflow
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * Sincroniza el estado de la ejecución con el dashboard en tiempo real (Vercel + Supabase).
 * Hace upsert de un snapshot del workflow (una fila por dispositivo) vía PostgREST; el dashboard
 * hace polling de esa fila y pinta los steps en verde/rojo mientras el asistente ejecuta.
 * Endpoint y anon key son configurables (BuildConfig por defecto), así el APK no depende de nada.
 */
class DashboardSync(prefs: SharedPreferences, private val scope: CoroutineScope) {

    private val base = prefs.getString("dashUrl", BuildConfig.DASHBOARD_URL)?.trimEnd('/').orEmpty()
    private val key = prefs.getString("dashKey", BuildConfig.DASHBOARD_KEY).orEmpty()
    private val device = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().take(8)
        .also { prefs.edit().putString("deviceId", it).apply() }

    val enabled get() = base.isNotBlank() && key.isNotBlank()
    val shareUrl get() = if (base.isBlank()) "" else "${BuildConfig.DASHBOARD_SITE}?device=$device"

    private var lastPost = 0L

    fun report(workflow: Workflow, activeOrder: Int, note: String) {
        if (!enabled) return
        val terminal = note == "fin" || note == "inicio" || note.contains("verde") || note.contains("rojo")
        val now = System.nanoTime() / 1_000_000
        if (!terminal && now - lastPost < 250) return
        lastPost = now

        val steps = JsonArray(workflow.steps.map { s ->
            buildJsonObject {
                put("order", s.order)
                put("action", s.action.name)
                put("label", s.label.ifBlank { s.selector.short() })
                put("status", when (s.status) {
                    StepStatus.CONFIRMED -> "green"; StepStatus.LLM -> "red"; else -> "draft"
                })
                put("branch", s.branch)
                put("active", s.order == activeOrder)
            }
        })
        val row = buildJsonObject {
            put("device", device)
            put("workflow_id", workflow.id)
            put("name", workflow.name)
            put("learned", workflow.learnedPct())
            put("note", note)
            put("steps", steps)
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val c = URL("$base/rest/v1/runs?on_conflict=device").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("apikey", key)
                c.setRequestProperty("Authorization", "Bearer $key")
                c.setRequestProperty("Prefer", "resolution=merge-duplicates")
                c.outputStream.use { it.write(Json.encodeToString(JsonObject.serializer(), row).toByteArray()) }
                val code = c.responseCode
                if (code >= 300) LogBus.log("dashboard", "HTTP $code: ${c.errorStream?.bufferedReader()?.readText()?.take(160)}")
                c.disconnect()
            }.onFailure { LogBus.log("dashboard", "error: ${it.message?.take(80)}") }
        }
    }
}
