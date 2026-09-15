package graph.core.voz

/**
 * LA COMPUERTA QUE IMPIDE QUE Ü SE OIGA A SÍ MISMA (docs/specs/002). Mientras la cola del altavoz suena
 * —más la gracia que tarda el eco en morir—, el micrófono viaja como SILENCIO DEL MISMO TAMAÑO: el
 * servidor no oye nada que confundir y el compás del buffer no se pierde. Espejo de
 * `U-Windows-App/voz/Realtime/CompuertaDeEco.cs`.
 *
 * LA LLAVE ES EL ESTADO DE LA REPRODUCCIÓN, JAMÁS EL VOLUMEN: un número no distingue el eco de Ü de quien
 * la interrumpe. [filtrar] recibe `sonando` = «la cola tiene bytes».
 */
class CompuertaDeEco(private val graciaMs: Long = GRACIA_MS, ritmoHz: Int = ProtocoloGptLive.RITMO) {

    companion object {
        /** Cubre la latencia del altavoz más el resto de sala. */
        const val GRACIA_MS = 300L
    }

    /** PCM16 mono: 2 bytes por muestra. */
    private val bytesPorMs = ritmoHz * 2 / 1000

    /** Cuándo sonó por última vez; null = nunca, y la compuerta nace abierta (cerrada dejaría muda la sesión). */
    private var ultimoSonando: Long? = null

    /** Milisegundos de micrófono sustituidos por silencio. Se cuenta lo TRAGADO, no lo enviado. */
    var msTragados: Long = 0
        private set

    /** Reabrirse A LA ORDEN, sin gracia: quien acaba de interrumpir necesita que el servidor oiga su primera sílaba. */
    fun abrir() {
        ultimoSonando = null
    }

    /** El mismo trozo si está abierta; ceros del mismo tamaño si Ü suena o el eco aún no murió. */
    fun filtrar(trozo: ByteArray, sonando: Boolean, ahora: Long): ByteArray {
        if (sonando) ultimoSonando = ahora
        val cerrada = sonando || ultimoSonando?.let { ahora - it < graciaMs } == true
        if (!cerrada) return trozo
        if (bytesPorMs > 0) msTragados += trozo.size / bytesPorMs
        return ByteArray(trozo.size)
    }
}

/**
 * QUIÉN CORTA EL ECO: el AEC del sistema o la compuerta, nunca los dos. Forzar solo ENCIENDE la garantía.
 *
 * EL DEFAULT ES EL MICRÓFONO SIEMPRE ABIERTO (decisión del dueño, 2026-08-31): interrumpir con la voz como
 * en la documentación de OpenAI; con parlantes, audífonos. En Windows lo invierte una variable de entorno;
 * en el teléfono no hay, y el default lo dice la firma.
 */
object ModoDeCaptura {
    fun activa(forzada: Boolean, aec: Boolean, sinCaminoDeEco: Boolean = true): Boolean =
        forzada || (!aec && !sinCaminoDeEco)
}
