package com.zevcorp.graph.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zevcorp.graph.GraphApp

/** Detiene la ejecución desde la notificación (botón ⏹). El botón flotante llama directo a stopExecution(). */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? GraphApp)?.stopExecution()
    }
}
