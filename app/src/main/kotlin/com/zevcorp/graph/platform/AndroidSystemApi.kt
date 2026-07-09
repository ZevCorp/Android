package com.zevcorp.graph.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import graph.core.domain.SystemApi
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Acciones "genéricas" del teléfono vía Common Intents / APIs de Android (headless, sin navegar la UI):
 * AlarmClock, CalendarContract, MediaStore, Settings y los Intents estándar (ACTION_DIAL, SENDTO, VIEW…).
 * Se lanza desde el contexto del servicio de accesibilidad (con privilegio para iniciar actividades).
 */
class AndroidSystemApi(private val ctx: Context) : SystemApi {

    private fun fire(intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(ctx.packageManager) == null) {
            LogBus.log("api", "sin app que resuelva ${intent.action}"); return false
        }
        ctx.startActivity(intent)
        LogBus.log("api", "Intent ${intent.action} → lanzado")
        true
    }.getOrElse { LogBus.log("api", "error en ${intent.action}: ${it.message?.take(80)}"); false }

    override suspend fun openApp(name: String): Boolean {
        val pm = ctx.packageManager
        val q = name.trim()
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { it.packageName.equals(q, true) }
            ?: apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(q, true) }
            ?: return false.also { LogBus.log("api", "no encontré la app \"$q\"") }
        val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        return runCatching {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            LogBus.log("api", "launch_app → ${match.packageName}"); true
        }.getOrDefault(false)
    }

    override suspend fun setAlarm(hour: Int, minute: Int, message: String) = fire(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message) })

    override suspend fun setTimer(seconds: Int, message: String) = fire(
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (message.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, message) })

    override suspend fun showAlarms() = fire(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    override suspend fun createEvent(title: String, startIso: String, location: String): Boolean {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
        if (location.isNotBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location)
        parseIso(startIso)?.let { start ->
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3_600_000L)
        }
        return fire(intent)
    }

    private fun parseIso(s: String): Long? = s.trim().takeIf { it.isNotBlank() }?.let {
        runCatching {
            LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    override suspend fun dial(number: String) = fire(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))

    override suspend fun call(number: String) = fire(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))

    override suspend fun sendSms(number: String, message: String) = fire(
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", message))

    override suspend fun sendEmail(to: String, subject: String, body: String) = fire(
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to"))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body))

    override suspend fun openUrl(url: String): Boolean {
        val u = if (url.startsWith("http", true)) url else "https://$url"
        return fire(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    override suspend fun maps(query: String) = fire(
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")))

    override suspend fun directions(destination: String) = fire(
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}")))

    override suspend fun openCamera() = fire(Intent(MediaStore.ACTION_IMAGE_CAPTURE))

    override suspend fun openSettings(section: String): Boolean {
        val action = when (section.trim().lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "data", "mobile" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return fire(Intent(action))
    }

    override suspend fun shareText(text: String) = fire(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))

    override suspend fun setClipboard(text: String): Boolean = runCatching {
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("graph", text))
        LogBus.log("api", "portapapeles ← \"${text.take(40)}\""); true
    }.getOrDefault(false)

    private fun streamType(stream: String) = when (stream.trim().lowercase()) {
        "ring", "ringtone" -> android.media.AudioManager.STREAM_RING
        "alarm" -> android.media.AudioManager.STREAM_ALARM
        "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
        "call", "voice" -> android.media.AudioManager.STREAM_VOICE_CALL
        else -> android.media.AudioManager.STREAM_MUSIC // media/música por defecto
    }

    override suspend fun setVolume(stream: String, percent: Int): Boolean = runCatching {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val type = streamType(stream)
        val max = am.getStreamMaxVolume(type)
        val index = (percent.coerceIn(0, 100) * max / 100).coerceIn(if (percent > 0) 1 else 0, max)
        am.setStreamVolume(type, index, 0)
        LogBus.log("api", "set_volume $stream → $index/$max"); true
    }.getOrElse { LogBus.log("api", "set_volume falló (¿acceso a No molestar?): ${it.message?.take(80)}"); false }

    override suspend fun adjustVolume(stream: String, direction: String): Boolean = runCatching {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val type = streamType(stream)
        val dir = when (direction.trim().lowercase()) {
            "raise", "up", "increase" -> android.media.AudioManager.ADJUST_RAISE
            "lower", "down", "decrease" -> android.media.AudioManager.ADJUST_LOWER
            "mute" -> android.media.AudioManager.ADJUST_MUTE
            "unmute" -> android.media.AudioManager.ADJUST_UNMUTE
            else -> android.media.AudioManager.ADJUST_SAME
        }
        am.adjustStreamVolume(type, dir, android.media.AudioManager.FLAG_SHOW_UI)
        LogBus.log("api", "adjust_volume $stream → $direction"); true
    }.getOrElse { LogBus.log("api", "adjust_volume falló (¿acceso a No molestar?): ${it.message?.take(80)}"); false }
}
