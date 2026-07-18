package com.zevcorp.graph.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.View
import com.zevcorp.graph.R
import com.zevcorp.graph.platform.LogBus
import com.zevcorp.graph.ui.FaceView

/**
 * VOLUMEN PROPIO DEL ASISTENTE, INDEPENDIENTE DEL RESTO DE APPS.
 *
 * Android no da streams de volumen por-app a terceros: todo el audio de medios comparte STREAM_MUSIC.
 * El único mecanismo estándar (el mismo que usan Chromecast y los dispositivos de casting) es publicar
 * una [MediaSession] con volumen "remoto" ([VolumeProvider]): mientras suena, el sistema le da a ESTA
 * sesión su propia barra en el panel de volumen y encamina las teclas físicas hacia aquí. El nivel que
 * el usuario elige se aplica como ganancia al [MediaPlayer] de la voz (y como volumen de la locución en
 * el TTS del sistema), y se recuerda entre sesiones.
 *
 * Además publica una notificación estilo media enlazada a la sesión para que la carita aparezca en el
 * panel/bandeja de medios tal cual el icono de cualquier reproductor. Todo es best-effort: si falla
 * (sin permiso de notificaciones, etc.) la voz nunca se ve afectada.
 *
 * Es un singleton compartido (ver [com.zevcorp.graph.GraphApp.voiceSession]); lo usan tanto la voz de
 * OpenAI como el TTS del sistema, desde la burbuja flotante y desde el asistente de sistema.
 */
class VoiceMediaSession(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** 15 pasos, como un stream de volumen nativo, para que las teclas físicas se sientan naturales. */
    private val steps = 15

    /** Ganancia 0..1 aplicada a la voz. Persistida en prefs para que el nivel elegido se recuerde. */
    @Volatile var gain: Float = clamp01(prefs().getFloat(PREF, 1f))
        private set

    /** El reproductor de la voz de OpenAI que está sonando ahora (para ajustar su volumen en vivo). */
    @Volatile private var boundPlayer: MediaPlayer? = null

    /** Se invoca cuando el usuario pulsa detener/pausa en el chip de medios. */
    var onStop: (() -> Unit)? = null

    private var session: MediaSession? = null
    private var provider: VolumeProvider? = null
    private var face: Bitmap? = null
    private val deactivate = Runnable { setInactive() }

    private fun prefs() = app.getSharedPreferences("graph", Context.MODE_PRIVATE)

    /* ---------- API pública (segura desde cualquier hilo: todo se encola en el hilo principal) ---------- */

    /** Enlaza el reproductor de la voz de OpenAI y le aplica ya la ganancia elegida. */
    fun bindPlayer(mp: MediaPlayer) {
        boundPlayer = mp
        runCatching { mp.setVolume(gain, gain) }
    }

    fun unbindPlayer(mp: MediaPlayer) {
        if (boundPlayer === mp) boundPlayer = null
    }

    /**
     * La voz empieza a sonar: activa la sesión (aparece la carita + su barra en el panel de volumen y
     * las teclas físicas la controlan). [holdMs] es una red de seguridad para desactivar sola si nadie
     * llama a [endSpeaking] (útil para el TTS del sistema, donde no sabemos el fin exacto).
     */
    fun beginSpeaking(holdMs: Long = 60_000L) = main.post {
        runCatching {
            val s = ensureSession() ?: return@post
            s.setPlaybackState(state(PlaybackState.STATE_PLAYING))
            if (!s.isActive) s.setActive(true)
            postMediaNotification()
            main.removeCallbacks(deactivate)
            main.postDelayed(deactivate, holdMs.coerceAtLeast(1_000L))
        }.onFailure { LogBus.log("voice", "media begin falló: ${it.message?.take(80)}") }
    }

    /** La voz terminó: deja de reproducir y suelta las teclas de volumen tras un breve margen. */
    fun endSpeaking() = main.post {
        runCatching {
            session?.setPlaybackState(state(PlaybackState.STATE_PAUSED))
            main.removeCallbacks(deactivate)
            main.postDelayed(deactivate, 3_000L) // margen para abrir el panel justo después de hablar
        }
    }

    /* ---------- Interno ---------- */

    private fun ensureSession(): MediaSession? {
        session?.let { return it }
        val s = runCatching { MediaSession(app, "MiracleVoice") }.getOrElse {
            LogBus.log("voice", "no se pudo crear MediaSession: ${it.message?.take(80)}"); return null
        }
        s.setCallback(object : MediaSession.Callback() {
            override fun onStop() = stopRequested()
            override fun onPause() = stopRequested()
        })
        val cur = Math.round(gain * steps).coerceIn(0, steps)
        val vp = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, steps, cur) {
            override fun onSetVolumeTo(volume: Int) = applyStep(volume)
            override fun onAdjustVolume(direction: Int) =
                applyStep(Math.round(gain * steps).coerceIn(0, steps) + direction.coerceIn(-1, 1))
        }
        provider = vp
        s.setPlaybackToRemote(vp)
        s.setMetadata(metadata())
        session = s
        return s
    }

    /** Un paso de volumen (0..steps): guarda la ganancia, la refleja en la barra y la aplica en vivo. */
    private fun applyStep(step: Int) {
        val v = step.coerceIn(0, steps)
        gain = v.toFloat() / steps
        runCatching { prefs().edit().putFloat(PREF, gain).apply() }
        provider?.currentVolume = v
        boundPlayer?.let { p -> runCatching { p.setVolume(gain, gain) } }
    }

    private fun stopRequested() {
        boundPlayer?.let { p -> runCatching { if (p.isPlaying) p.stop() } }
        runCatching { onStop?.invoke() }
        endSpeaking()
    }

    private fun setInactive() {
        runCatching {
            session?.setActive(false)
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
    }

    private fun state(playing: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
        .setState(playing, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    private fun metadata(): MediaMetadata {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Miracle")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Asistente")
        faceBitmap()?.let {
            b.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
        }
        return b.build()
    }

    /** Renderiza la carita real (FaceView) a un bitmap para el icono del panel de medios. */
    private fun faceBitmap(): Bitmap? {
        face?.let { return it }
        return runCatching {
            val px = 256
            val v = FaceView(app)
            v.measure(
                View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, px, px)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            face = bmp
            bmp
        }.getOrNull()
    }

    private fun postMediaNotification() {
        val s = session ?: return
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Voz del asistente", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Miracle")
                .setContentText("Voz del asistente")
                .apply { faceBitmap()?.let { setLargeIcon(it) } }
                .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken))
                .setOngoing(false)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    private companion object {
        const val PREF = "assistantVoiceVolume"
        const val CHANNEL = "assistant_voice"
        const val NOTIF_ID = 7
        fun clamp01(v: Float) = if (v.isNaN()) 1f else v.coerceIn(0f, 1f)
    }
}
