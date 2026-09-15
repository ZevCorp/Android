package graph.core.voz

/** El barge-in de la compuerta (docs/specs/002). Esqueleto. */
class DetectorDeInterrupcion(
    private val sostenMs: Long = SOSTEN_MS,
    private val factor: Double = FACTOR,
    private val pisoRms: Double = PISO_RMS,
) {

    companion object {
        const val SOSTEN_MS = 240L
        const val FACTOR = 3.0
        const val PISO_RMS = 500.0
        const val SIEMBRA_MS = 250L
    }

    val lineaBase: Double get() = TODO()

    fun oye(rms: Double, sonando: Boolean, ahora: Long): Boolean = TODO()
}
