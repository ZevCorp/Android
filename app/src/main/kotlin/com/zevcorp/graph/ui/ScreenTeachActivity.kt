package com.zevcorp.graph.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.platform.ScreenTeachService

/**
 * Actividad transparente y efímera: MediaProjection exige una Activity para pedir el permiso de
 * captura de pantalla. Muestra el diálogo del sistema, y con el token arranca el foreground service
 * de grabación (aprendizaje activo). No tiene UI propia: se cierra en cuanto responde el usuario.
 */
class ScreenTeachActivity : Activity() {

    private val app get() = GraphApp.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MediaProjectionManager::class.java)
        runCatching { startActivityForResult(mgr.createScreenCaptureIntent(), REQ) }
            .onFailure { app.activeLearning.onCancelled(); finish() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, ScreenTeachService::class.java)
                .putExtra(ScreenTeachService.EXTRA_CODE, resultCode)
                .putExtra(ScreenTeachService.EXTRA_DATA, data)
            runCatching { startForegroundService(svc) }
                .onSuccess { app.activeLearning.onStarted() }
                .onFailure { app.activeLearning.onCancelled() }
        } else {
            app.activeLearning.onCancelled()
        }
        finish()
    }

    private companion object { const val REQ = 7 }
}
