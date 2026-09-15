package graph.core.voz

/**
 * EL BARGE-IN DE LA COMPUERTA (docs/specs/002): mientras la compuerta manda y el servidor oye silencio,
 * este es el único oído que puede devolverle la interrupción al usuario. Espejo exacto de
 * `U-Windows-App/voz/Realtime/DetectorDeInterrupcion.cs:64-100`, con los valores de la app
 * (`ConversacionEnVivo.cs:151`: 240 ms, 3.0, 500).
 *
 * El volumen decide UNA cosa acotada: que hay voz sostenida muy por encima del eco que este detector
 * aprendió. Tres defensas contra el disparo falso:
 *  - el SOSTÉN: un portazo es un trozo; la voz dura;
 *  - la LÍNEA BASE MÓVIL: es el eco de esta máquina y lo sigue si sube o baja;
 *  - la SIEMBRA: los primeros ms de una frase nueva son su eco, aunque sea más fuerte, y siembran la base.
 * Y tras disparar se desarma hasta que vuelva el eco: interrumpir una vez basta.
 *
 * `sonando` debe ser LA ERA DE LA COMPUERTA (cerrada = eco), no el estado crudo del buffer: con el crudo,
 * cada parpadeo entre ráfagas re-arrancaba la siembra y el detector no disparó nunca (medido en U).
 */
class DetectorDeInterrupcion(
    private val sostenMs: Long = SOSTEN_MS,
    private val factor: Double = FACTOR,
    private val pisoRms: Double = PISO_RMS,
) {

    companion object {
        const val SOSTEN_MS = 240L
        const val FACTOR = 3.0

        /** Piso absoluto de RMS (PCM16: 0..32768): por debajo jamás es voz encima. */
        const val PISO_RMS = 500.0

        /** Cuánto dura la siembra tras arrancar la cola: en ese tramo todo es eco por decreto. */
        const val SIEMBRA_MS = 250L
    }

    private var base = -1.0          // -1 = todavía no aprendió eco ninguno
    private var sobreDesde = -1L     // desde cuándo la energía está por encima; -1 = no lo está
    private var anterior = -1L
    private var siembraHasta = -1L
    private var sonabaAntes = false
    private var desarmado = false    // ya disparó este episodio; se rearma cuando vuelve el eco

    /** La línea base aprendida, para poder medir la calibración contra la sala real. */
    val lineaBase: Double get() = base

    /** «Oí este trozo con la cola en este estado». Verdadero UNA vez por episodio: cortar la cola y reabrir la compuerta. */
    fun oye(rms: Double, sonando: Boolean, ahora: Long): Boolean {
        val antes = anterior
        anterior = ahora

        // Sin cola sonando no hay eco que confundir ni nada que interrumpir: el servidor ya oye.
        if (!sonando) {
            sonabaAntes = false
            sobreDesde = -1
            return false
        }

        // La cola acaba de arrancar: empieza la siembra de ESTA frase.
        if (!sonabaAntes) siembraHasta = ahora + SIEMBRA_MS
        sonabaAntes = true

        val sembrando = ahora <= siembraHasta
        val sobre = !sembrando && rms >= pisoRms && base >= 0 && rms >= base * factor

        if (!sobre) {
            // Lo que suena y no es candidato a voz ES el eco: alimenta la línea base (media móvil corta).
            base = if (base < 0) rms else base * 0.8 + rms * 0.2
            sobreDesde = -1
            desarmado = false
            return false
        }

        if (desarmado) return false

        if (sobreDesde < 0) {
            sobreDesde = if (antes < 0) ahora else antes
            return false
        }
        if (ahora - sobreDesde < sostenMs) return false

        desarmado = true
        sobreDesde = -1
        return true
    }
}
