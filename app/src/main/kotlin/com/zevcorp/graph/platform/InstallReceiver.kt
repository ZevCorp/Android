package com.zevcorp.graph.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Recibe el estado de la sesión de PackageInstaller. Cuando el sistema requiere confirmación del
 * usuario (STATUS_PENDING_USER_ACTION) hay que lanzar el intent de confirmación que trae el sistema
 * (la pantalla "¿Instalar actualización?"). Los demás estados solo se registran.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { LogBus.log("update", "no pude abrir la confirmación: ${it.message}") }
            }
            PackageInstaller.STATUS_SUCCESS -> LogBus.log("update", "✅ actualización instalada")
            else -> LogBus.log("update",
                "instalación: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "estado desconocido"}")
        }
    }

    companion object {
        const val ACTION = "com.zevcorp.graph.INSTALL_STATUS"
    }
}
