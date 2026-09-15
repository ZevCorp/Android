package graph.core.graph

/**
 * El "URL de Android" de la superficie donde está parado el usuario, en el mismo contrato que el
 * `SurfaceLocator` de Windows: con `surfaceId/Origin/Pathname` Graph scopea qué workflows declara
 * por MCP en este turno.
 */
class Surface(val id: String, val origin: String, val pathname: String)

object AndroidSurface {
    /**
     * Deriva la superficie de la pantalla tal como la arma la accesibilidad: `"paquete · título"`.
     * `"com.miui.calculator · Calculadora"` → origin `android://com.miui.calculator`, pathname
     * `/calculadora`, id = origin + pathname. Sin título → pathname `/`.
     */
    fun from(screen: String): Surface {
        val paquete = screen.substringBefore(SEPARADOR).trim()
        val titulo = if (SEPARADOR in screen) screen.substringAfter(SEPARADOR).trim() else ""
        val origin = "android://$paquete"
        val pathname = "/" + slug(titulo)
        return Surface(id = origin + pathname, origin = origin, pathname = pathname)
    }

    /** El separador con que la accesibilidad arma `screen` (ver GraphAccessibilityService). */
    private const val SEPARADOR = " · "

    /**
     * Un título en forma de segmento de URL: primero minúsculas y espacios → `-`; después, todo lo
     * que no sea `[a-z0-9._~-]` (los "unreserved" de la RFC 3986) va en percent-encoding UTF-8. Nada
     * se tira: «设置» o «Configurações» conservan cada letra, codificada, y el pathname se puede
     * deshacer. Así el id es comparable entre teléfonos aunque el título cambie de mayúsculas.
     */
    private fun slug(titulo: String): String = titulo.lowercase()
        .replace(Regex("\\s+"), "-")
        .trim('-')
        .encodeToByteArray()
        .joinToString("") { byte ->
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (b < 0x80 && (c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~')) c.toString()
            else "%" + HEX[b shr 4] + HEX[b and 0xF]
        }

    private const val HEX = "0123456789ABCDEF"
}
