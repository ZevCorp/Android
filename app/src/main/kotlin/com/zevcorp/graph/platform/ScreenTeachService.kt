package com.zevcorp.graph.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.zevcorp.graph.GraphApp
import java.io.File

/**
 * Foreground service del aprendizaje ACTIVO: mantiene la MediaProjection (compartir pantalla) y graba
 * VIDEO (H264) + AUDIO del micrófono (AAC) a un mp4 mientras el usuario le enseña al asistente. Al
 * detener (toque del 🎓, la acción de la notificación, o si el sistema corta la proyección) cierra la
 * grabación y entrega el archivo a `ActiveLearning` para que Gemini lo estructure como conocimiento.
 */
class ScreenTeachService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var outFile: File? = null
    @Volatile private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish(); return START_NOT_STICKY }
        start(intent)
        return START_STICKY
    }

    private fun start(intent: Intent?) {
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = intent?.let {
            if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") it.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) { LogBus.log("teach", "sin token de proyección"); finish(); return }

        startForeground(NOTIF_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { mgr.getMediaProjection(code, data) }.getOrNull()
        if (proj == null) { LogBus.log("teach", "no se pudo crear la proyección"); finish(); return }
        // Desde Android 14 hay que registrar el callback ANTES de crear el VirtualDisplay.
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { finish() }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        projection = proj

        runCatching { setupRecorder(proj) }.onFailure {
            LogBus.log("teach", "no se pudo iniciar la grabación: ${it.message}")
            finish()
        }
    }

    private fun setupRecorder(proj: MediaProjection) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)

        // Escala el lado menor a máx 720 px (pares) para un archivo manejable manteniendo proporción.
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val cap = 720
        if (minOf(w, h) > cap) {
            val scale = cap.toFloat() / minOf(w, h)
            w = (w * scale).toInt() / 2 * 2
            h = (h * scale).toInt() / 2 * 2
        }

        val file = File(cacheDir, "teach.mp4")
        if (file.exists()) file.delete()

        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setOutputFile(file.absolutePath)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setVideoSize(w, h)
        rec.setVideoFrameRate(24)
        rec.setVideoEncodingBitRate(4_000_000)
        rec.prepare()

        display = proj.createVirtualDisplay(
            "graph_teach", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null)
        rec.start()
        recorder = rec
        outFile = file
        LogBus.log("teach", "▶ grabando pantalla+audio ${w}x$h")
    }

    /** Cierra la grabación una sola vez y entrega el archivo (o null si no hubo) a ActiveLearning. */
    private fun finish() {
        if (finished) return
        finished = true
        val file = outFile
        runCatching { recorder?.stop() }.onFailure { LogBus.log("teach", "stop grabación: ${it.message}") }
        runCatching { recorder?.reset(); recorder?.release() }
        recorder = null
        runCatching { display?.release() }; display = null
        runCatching { projection?.stop() }; projection = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        val ok = file != null && file.exists() && file.length() > 0
        GraphApp.instance.activeLearning.onRecorded(if (ok) file else null)
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("teach", "Aprendizaje activo", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getForegroundService(
            this, 0, Intent(this, ScreenTeachService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "teach")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Ü está aprendiendo de tu pantalla")
            .setContentText("Enséñale; toca para terminar")
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "⏹ Terminar", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.zevcorp.graph.STOP_TEACH"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 4
    }
}
