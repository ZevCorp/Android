package com.zevcorp.graph.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zevcorp.graph.Ejecucion

/** Detiene la ejecución desde la notificación (botón ⏹): el mismo alto que la píldora (spec 003, promesa 308). */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Ejecucion.parar("notificación")
    }
}
