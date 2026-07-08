package com.zevcorp.graph.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.zevcorp.graph.GraphApp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** La última versión publicada (fila única de graph_release en Supabase). */
class Release(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

/**
 * Auto-actualización para una app sideloaded (fuera de Play Store):
 *  - consulta en Supabase la última versión publicada (graph_release),
 *  - si es mayor a la instalada, avisa con notificación y/o botón "Actualizar",
 *  - descarga el APK y lo instala con PackageInstaller (el sistema pide confirmación),
 *  - el admin publica una versión nueva vía una Edge Function protegida por token.
 *
 * Requisito clave: todos los builds se firman con la MISMA clave (ver app/build.gradle.kts), porque
 * Android solo permite actualizar si la firma coincide con la instalada.
 */
object Updater {

    private const val BASE = "https://zyvfamlhlmztliexvmej.supabase.co"
    private const val ANON = "sb_publishable_qroW231Ts7UYAEgr_f5cnQ_3SrW2ZrI"
    private const val TABLE = "$BASE/rest/v1/graph_release"
    private const val PUBLISH_FN = "$BASE/functions/v1/publish-release"

    const val CHANNEL = "update"
    private const val NOTIF_ID = 5
    const val EXTRA_OPEN_UPDATE = "open_update"

    /** versionCode de la app instalada. */
    fun currentVersionCode(context: Context): Int =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt() }
            .getOrDefault(0)

    /** Lee la última versión publicada (o null si no hay/ falla). */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL("$TABLE?select=version_code,version_name,apk_url,notes&limit=1")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000; readTimeout = 20_000
                setRequestProperty("apikey", ANON)
                setRequestProperty("Authorization", "Bearer $ANON")
            }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            c.disconnect()
            if (code >= 300) { LogBus.log("update", "HTTP $code: ${body.take(160)}"); return@withContext null }
            val row = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return@withContext null
            Release(
                versionCode = row["version_code"]?.jsonPrimitive?.intOrNull ?: 0,
                versionName = row["version_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                apkUrl = row["apk_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                notes = row["notes"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.getOrElse { LogBus.log("update", "consulta de versión falló: ${it.message}"); null }
    }

    /** ¿Hay una versión más nueva y con APK que la instalada? */
    fun isNewer(context: Context, r: Release?): Boolean =
        r != null && r.versionCode > currentVersionCode(context) && r.apkUrl.isNotBlank()

    /**
     * Sondeo en segundo plano (lo llama el servicio siempre-activo y el arranque): si hay versión
     * nueva y aún no se avisó de ESA versión, muestra una notificación. Dedup por version_code.
     */
    suspend fun checkAndNotify(context: Context) {
        val r = fetchLatest() ?: return
        if (!isNewer(context, r)) return
        val prefs = GraphApp.instance.prefs
        if (prefs.getInt("notifiedVersion", 0) == r.versionCode) return
        notify(context, r)
        prefs.edit().putInt("notifiedVersion", r.versionCode).apply()
    }

    private fun notify(context: Context, r: Release) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Actualizaciones", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, com.zevcorp.graph.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_UPDATE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(NOTIF_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Hay una nueva versión de Ü 🚀")
            .setContentText("Ü ${r.versionName} · toca para actualizar")
            .setStyle(Notification.BigTextStyle().bigText(
                "Ü ${r.versionName} disponible.${if (r.notes.isNotBlank()) "\n${r.notes}" else ""}\nToca para actualizar."))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build())
    }

    fun clearNotification(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)

    /**
     * Descarga el APK y lanza el instalador del sistema (PackageInstaller: stream directo, sin
     * FileProvider). El sistema pedirá confirmación (y permiso de "instalar apps desconocidas").
     */
    suspend fun downloadAndInstall(context: Context, r: Release, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            val c = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 120_000
                instanceFollowRedirects = true
            }
            c.connect()
            if (c.responseCode >= 300) { c.disconnect(); error("descarga HTTP ${c.responseCode}") }
            val total = c.contentLengthLong
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (total > 0) params.setSize(total)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                c.inputStream.use { input ->
                    session.openWrite("graph.apk", 0, if (total > 0) total else -1).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read * 100 / total).toInt().coerceIn(0, 100))
                        }
                        session.fsync(out)
                    }
                }
                c.disconnect()
                val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
                val pi = PendingIntent.getBroadcast(context, sessionId, intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pi.intentSender)
                session.close()
            } catch (t: Throwable) {
                runCatching { session.abandon() }
                throw t
            }
        }

    /**
     * Publica una versión nueva (solo admin): llama a la Edge Function con el token de administrador,
     * que valida el token del lado servidor y actualiza graph_release. Devuelve null si OK, o el
     * mensaje de error. Publicar hace que todas las apps (al sondear o abrir) vean la versión y avisen.
     */
    suspend fun publish(
        adminToken: String, versionCode: Int, versionName: String, apkUrl: String, notes: String,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("admin_token", adminToken)
                put("version_code", versionCode)
                put("version_name", versionName)
                put("apk_url", apkUrl)
                put("notes", notes)
            })
            val c = (URL(PUBLISH_FN).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", ANON)
                setRequestProperty("Authorization", "Bearer $ANON")
                doOutput = true
            }
            c.outputStream.use { it.write(payload.toByteArray()) }
            val code = c.responseCode
            val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            c.disconnect()
            if (code < 300) null
            else {
                val msg = runCatching {
                    Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() ?: "HTTP $code"
                if (code == 401) "Token de administrador incorrecto" else msg
            }
        }.getOrElse { "No se pudo publicar: ${it.message}" }
    }
}
