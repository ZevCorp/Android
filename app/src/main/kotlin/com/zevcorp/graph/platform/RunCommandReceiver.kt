package com.zevcorp.graph.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zevcorp.graph.GraphApp
import kotlinx.coroutines.launch

/**
 * Etapa 3 · Subconsciente desde la terminal:
 *   adb shell am broadcast -a com.zevcorp.graph.RUN --es id wf_123 --es input_3 "valor"
 * El resultado se publica en logcat con tag GraphCLI (ver cli/graph).
 */
class RunCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as GraphApp
        when (intent.action) {
            "com.zevcorp.graph.SET_KEY" ->
                app.prefs.edit().putString("apiKey", intent.getStringExtra("key") ?: "").apply()

            "com.zevcorp.graph.LIST" -> app.scope.launch {
                val all = app.workflows.list()
                if (all.isEmpty()) Log.i(TAG, "No hay workflows aprendidos todavía")
                all.forEach { w ->
                    Log.i(TAG, "${w.id} · ${w.name} · ${w.steps.size} steps · " +
                        "graph run ${w.id} ${w.variables.joinToString(" ") { "--${it.name}=\"${it.default}\"" }}")
                }
            }

            "com.zevcorp.graph.RUN" -> {
                val id = intent.getStringExtra("id") ?: return
                val inputs = intent.extras?.keySet().orEmpty()
                    .filter { it.startsWith("input_") }
                    .associateWith { intent.getStringExtra(it) ?: "" }
                app.scope.launch { Log.i(TAG, app.runWorkflow(id, inputs)) }
            }
        }
    }

    private companion object { const val TAG = "GraphCLI" }
}
