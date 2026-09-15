package graph.core.voz

/** Los turnos de una voz que no los marca (docs/specs/002). Esqueleto. */
class TurnosSinMarca(private val reloj: () -> Long, private val silencioMs: Long = SILENCIO_MS) {

    companion object {
        const val SILENCIO_MS = 2000L
        const val PICO_DE_VOZ = 1000
        const val DEVUELTAS_QUE_SE_RECUERDAN = 64
    }

    val llamadasEnCurso: Int get() = TODO()

    fun oye(hecho: Hecho): Boolean = TODO()

    fun devuelta(llamadas: List<Llamada>): Unit = TODO()

    fun tocaCerrar(): Boolean = TODO()
}
