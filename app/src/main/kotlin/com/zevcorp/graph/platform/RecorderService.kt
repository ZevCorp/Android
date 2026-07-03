package com.zevcorp.graph.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import graph.core.domain.ScreenRecorder
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Etapa 1 · Teaching: graba la pantalla (y el micrófono, para la narración) como video-tutorial. */
class RecorderService : Service() {

    companion object {
        var grantCode = 0
        var grantData: Intent? = null
        var onStopped: ((String) -> Unit)? = null
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var outPath = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopRecording()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("rec", "Grabación", NotificationManager.IMPORTANCE_LOW))
        startForeground(
            1,
            Notification.Builder(this, "rec")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Graph está grabando tu tutorial").build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        val m = resources.displayMetrics
        val w = m.widthPixels - m.widthPixels % 16
        val h = m.heightPixels - m.heightPixels % 16
        outPath = File(filesDir, "teach_${System.currentTimeMillis()}.mp4").absolutePath
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(w, h)
            setVideoFrameRate(24)
            setVideoEncodingBitRate(4_000_000)
            setOutputFile(outPath)
            prepare()
        }
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(grantCode, grantData!!)
        projection!!.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
        display = projection!!.createVirtualDisplay(
            "graph-teaching", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder!!.surface, null, null,
        )
        recorder!!.start()
        return START_NOT_STICKY
    }

    private fun stopRecording() {
        runCatching { recorder?.stop() }
        recorder?.release()
        display?.release()
        projection?.stop()
        onStopped?.invoke(outPath)
        onStopped = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}

class DroidScreenRecorder(private val context: Context) : ScreenRecorder {
    override fun start() {
        context.startForegroundService(Intent(context, RecorderService::class.java))
    }

    override suspend fun stop(): String = suspendCancellableCoroutine { cont ->
        RecorderService.onStopped = { cont.resume(it) }
        context.startService(Intent(context, RecorderService::class.java).setAction("stop"))
    }
}
