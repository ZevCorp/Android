package graph.core.voz

/**
 * LA COLA DEL ALTAVOZ (docs/specs/002, fase B1b). GPT-Live manda la voz mucho más rápido de lo que suena: una frase de
 * cinco segundos entra en menos de uno y espera aquí. Espejo del `BufferedWaveProvider` de
 * `U-Windows-App/windows-client/src/Voice/LiveAudio.cs:498-501`, reescrito puro para juzgarlo sin altavoz.
 *
 * SIN CANDADO, como [TurnosSinMarca]: commonMain no tiene `synchronized`. Quien la comparte entre el hilo de la conversación
 * y el del altavoz la guarda con el suyo.
 */
class ColaDeReproduccion(ritmoHz: Int = ProtocoloGptLive.RITMO, segundos: Int = SEGUNDOS) {

    companion object {
        const val SEGUNDOS = 30
    }

    /** PCM16 mono: 2 bytes por muestra. */
    val capacidad: Int = ritmoHz * 2 * segundos

    val pendientes: Int get() = TODO()

    val bytesDescartados: Long get() = TODO()

    fun meter(pcm: ByteArray): Unit = TODO()

    fun sacar(n: Int): ByteArray = TODO()

    fun sonando(): Boolean = TODO()

    fun callar(): Unit = TODO()
}
