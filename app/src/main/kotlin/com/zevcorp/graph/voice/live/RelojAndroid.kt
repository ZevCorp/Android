package com.zevcorp.graph.voice.live

import android.os.SystemClock
import graph.core.voz.Reloj
import kotlinx.coroutines.delay

/**
 * EL TIEMPO DE LA VOZ EN EL TELÉFONO (docs/specs/002, fase B1b). `elapsedRealtime` y no la hora de pared: el cierre de turno
 * cuenta 2000 ms, y un cambio de hora del sistema a mitad de frase no puede cerrarlo ni dejarlo abierto para siempre.
 */
object RelojAndroid : Reloj {
    override fun ahora(): Long = SystemClock.elapsedRealtime()

    override suspend fun esperar(ms: Long) = delay(ms)
}
