package com.zevcorp.graph.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service efímero de tipo micrófono: garantiza que AudioRecord capture audio aunque la
 * app no tenga Activity visible (Android restringe el mic en segundo plano). Vive solo mientras
 * dura una escucha del pipeline de voz.
 */
class MicService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("mic", "Escucha por voz", NotificationManager.IMPORTANCE_LOW))
        startForeground(3, Notification.Builder(this, "mic")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Ü te está escuchando")
            .build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) =
            runCatching { context.startForegroundService(Intent(context, MicService::class.java)) }
        fun stop(context: Context) =
            runCatching { context.stopService(Intent(context, MicService::class.java)) }
    }
}
