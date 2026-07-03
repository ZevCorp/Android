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
                    Log.i(TAG, "${w.id} · ${w.name} · ${w.steps.size} steps · ${w.branches.size} ramas · ${w.usage()}")
                }
            }

            // "man page" del workflow: pensado para que un LLM en terminal decida qué ramas activar
            "com.zevcorp.graph.INFO" -> app.scope.launch {
                val w = app.workflows.get(intent.getStringExtra("id") ?: "")
                if (w == null) {
                    Log.i(TAG, "workflow no encontrado")
                    return@launch
                }
                Log.i(TAG, "## ${w.id} — ${w.name}")
                Log.i(TAG, "proposito: ${w.purpose}")
                Log.i(TAG, "uso: ${w.usage()}")
                w.branches.forEach { Log.i(TAG, "rama --branch ${it.name}: ${it.description}") }
                w.variables.forEach { Log.i(TAG, "variable --${it.name}: ${it.field} (default: \"${it.default}\")") }
                w.steps.forEach { s ->
                    val status = when (s.status) {
                        graph.core.domain.StepStatus.CONFIRMED -> "verde"; graph.core.domain.StepStatus.LLM -> "rojo"; else -> "draft"
                    }
                    Log.i(TAG, "step ${s.order} [$status]${if (s.branch.isNotBlank()) " [rama ${s.branch}]" else ""}: " +
                        "${s.action} ${s.label.ifBlank { s.selector.short() }}")
                }
            }

            "com.zevcorp.graph.RUN" -> {
                val id = intent.getStringExtra("id") ?: return
                val inputs = intent.extras?.keySet().orEmpty()
                    .filter { it.startsWith("input_") }
                    .associateWith { intent.getStringExtra(it) ?: "" }
                val branches = intent.getStringExtra("branches")?.split(',')
                    ?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
                LogBus.log("cli", "RUN $id inputs=${inputs.keys.joinToString(",")} ramas=${branches.joinToString(",")}")
                app.scope.launch {
                    val result = app.runWorkflow(id, inputs, branches)
                    Log.i(TAG, result)
                    LogBus.log("cli", result)
                }
            }
        }
    }

    private companion object { const val TAG = "GraphCLI" }
}
