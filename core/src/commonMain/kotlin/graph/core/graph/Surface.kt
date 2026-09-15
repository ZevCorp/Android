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
    fun from(screen: String): Surface = TODO("pendiente")
}
