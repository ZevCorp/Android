package com.zevcorp.graph.platform

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

/**
 * Datos de las dos barras del modo usuario:
 *  · [uActiveMs]    tiempo que Ü estuvo ejecutando (lo acumula GraphApp mientras trabaja por ti).
 *  · [userScreenMs] tiempo total de pantalla del usuario hoy (UsageStatsManager, requiere acceso a uso).
 */
object UsageU {

    private const val PREFS = "graph"
    const val KEY_U_ACTIVE_MS = "u_active_ms"

    fun uActiveMs(ctx: Context): Long =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_U_ACTIVE_MS, 0L)

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Tiempo total en primer plano hoy de todas las apps (excepto la nuestra). 0 si no hay permiso. */
    fun userScreenMs(ctx: Context): Long {
        if (!hasUsageAccess(ctx)) return 0L
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return 0L
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now) ?: return 0L
        return stats.filter { it.packageName != ctx.packageName }
            .sumOf { it.totalTimeInForeground }
    }
}
