package graph.core.voz

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * EL PICO DEL SILENCIO ES CERO, medido y no elegido a ojo (U, 2026-09-12, tres sesiones): el silencio
 * del servidor son ceros exactos, y DENTRO de una frase de Ü las pausas entre oraciones bajan a pico 1.
 * Un umbral de 64 se comía 11, 27 y 21 deltas de pausa, y Ü diría «Uno.Dos.Tres.» de corrido.
 */
private const val PICO_DEL_SILENCIO = 0

/** La muestra PCM16LE que empieza en [i], como Int: así −32768 tiene valor absoluto 32768 y no desborda. */
private fun muestra(pcm: ByteArray, i: Int): Int = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)

/** Todas las muestras a cero. Mira MUESTRAS, no bytes sueltos: 256 tiene el byte bajo a cero y es sonido. */
fun esSilencio(pcm: ByteArray): Boolean {
    var i = 0
    while (i + 1 < pcm.size) {
        if (abs(muestra(pcm, i)) > PICO_DEL_SILENCIO) return false
        i += 2
    }
    // Un byte suelto al final (tamaño impar) no es una muestra entera: cuenta como silencio solo si es cero.
    return pcm.size % 2 == 0 || pcm.last() == 0.toByte()
}

/** El pico absoluto de un trozo PCM16LE mono (0..32768). */
fun pico(pcm: ByteArray): Int {
    var pico = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val v = abs(muestra(pcm, i))
        if (v > pico) pico = v
        i += 2
    }
    return pico
}

/** El RMS de un trozo PCM16LE mono (0..32768). Es lo que oye [DetectorDeInterrupcion]. */
fun rms(pcm: ByteArray): Double {
    if (pcm.size < 2) return 0.0
    var suma = 0.0
    var i = 0
    while (i + 1 < pcm.size) {
        val v = muestra(pcm, i).toDouble()
        suma += v * v
        i += 2
    }
    return sqrt(suma / (pcm.size / 2))
}
