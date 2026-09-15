package graph.core.voz

/** La compuerta que impide que Ü se oiga a sí misma (docs/specs/002). Esqueleto. */
class CompuertaDeEco(private val graciaMs: Long = GRACIA_MS, ritmoHz: Int = ProtocoloGptLive.RITMO) {

    companion object {
        const val GRACIA_MS = 300L
    }

    val msTragados: Long get() = TODO()

    fun abrir(): Unit = TODO()

    fun filtrar(trozo: ByteArray, sonando: Boolean, ahora: Long): ByteArray = TODO()
}

/** Quién corta el eco: el AEC del sistema o la compuerta. Esqueleto. */
object ModoDeCaptura {
    fun activa(forzada: Boolean, aec: Boolean, sinCaminoDeEco: Boolean = true): Boolean = TODO()
}
