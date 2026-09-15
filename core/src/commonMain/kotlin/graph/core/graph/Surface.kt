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
     * Un título en forma de segmento de URL: minúsculas, sin acentos, espacios → `-`, y solo
     * `[a-z0-9._~-]` (los "unreserved" de la RFC 3986). Así el id es comparable entre teléfonos
     * aunque el título cambie de mayúsculas o de idioma de tilde.
     */
    private fun slug(titulo: String): String = titulo.lowercase()
        .map { ACENTOS[it] ?: it }
        .joinToString("")
        .replace(Regex("\\s+"), "-")
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' || it == '~' }
        .trim('-')

    private val ACENTOS = mapOf(
        'á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n',
        'à' to 'a', 'è' to 'e', 'ì' to 'i', 'ò' to 'o', 'ù' to 'u', 'ç' to 'c',
    )
}
