package graph.core.voz

/**
 * LO QUE DE LA VOZ PUEDE SALIR DEL TELÉFONO (docs/specs/002, fase B1b, promesa 245). `LogBus` reenvía cada línea a la
 * telemetría remota, que es una tabla de Supabase; y una transcripción es lo que alguien dijo en voz alta, a veces una clave.
 * Afuera solo sale la MEDIDA —el largo de cada frase, que el turno cerró—; la frase entera se queda en el log local.
 *
 * NO SE CAMBIA QUIÉN LOGUEA. [ConversacionViva] escribe `usuario dijo: …` y `Ü dijo: …` con su tag al cerrar el turno: el
 * filtro se aplica a todo tag `voz-` y trata como texto lo que sigue a «dijo:». Lo demás que podría traer contenido se
 * reconoce por su forma, porque ninguna línea de la voz debería traerlo (promesas 227 y 237) y la que lo traiga por error
 * no puede salir: del `toString` de un hecho o de una llamada queda su nombre, y de un JSON volcado, su tipo.
 */
object TelemetriaDeVoz {

    /** Los tags de la voz: `voz-viva`, `voz-canal`, `voz-audio`, `voz-dev`. Uno nuevo queda filtrado sin tocar esto. */
    const val PREFIJO = "voz-"

    private const val DIJO = "dijo:"

    /** Quienes hablan en las líneas de [ConversacionViva.cierraElTurno]. */
    private val QUIENES = listOf("usuario", "Ü")

    /** Los data class cuyo `toString` trae lo dicho, los argumentos o la salida de una herramienta. */
    private val CON_CONTENIDO = Regex("\\b(Llamada|Resultado|Pide|DiceU|DiceElUsuario)\\(")

    /** El primer `"type"` de un JSON, si tiene forma de tipo de evento y no de prosa. */
    private val TIPO = Regex("\"type\"\\s*:\\s*\"([A-Za-z0-9_.]{1,64})\"")

    fun esDeLaVoz(tag: String): Boolean = tag.startsWith(PREFIJO)

    /** Lo que de esta línea puede ir a la telemetría remota; null si nada. Fuera de la voz, la línea tal cual. */
    fun paraRemoto(tag: String, mensaje: String): String? {
        if (!esDeLaVoz(tag)) return mensaje
        for (quien in QUIENES) {
            if (mensaje.startsWith("$quien $DIJO")) return medida(mensaje, quien.length + 1 + DIJO.length)
        }
        CON_CONTENIDO.find(mensaje)?.let {
            return mensaje.substring(0, it.range.first) + "«${it.groupValues[1]}(…)» (${caracteres(mensaje.substring(it.range.first))} car.)"
        }
        val llave = mensaje.indexOf('{')
        if (llave >= 0) {
            val tipo = TIPO.find(mensaje, llave)?.groupValues?.get(1) ?: "sin tipo"
            return mensaje.substring(0, llave) + "«$tipo» (${caracteres(mensaje.substring(llave))} car.)"
        }
        val dijo = mensaje.indexOf(DIJO)
        if (dijo >= 0) return medida(mensaje, dijo + DIJO.length)
        return mensaje
    }

    /** Lo de antes de [desde] tal cual, y lo de después, contado. */
    private fun medida(mensaje: String, desde: Int): String {
        val texto = mensaje.substring(desde).removePrefix(" ")
        return mensaje.substring(0, desde) + " ${caracteres(texto)} caracteres"
    }

    /** Caracteres de verdad: un emoji fuera del plano básico es uno, no sus dos mitades UTF-16. */
    private fun caracteres(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            n++
        }
        return n
    }
}
