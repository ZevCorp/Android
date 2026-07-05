package graph.core.domain

/** Captura del estado de la pantalla que ve el cerebro en cada turno. */
class ScreenState(
    val screen: String,
    val width: Int,
    val height: Int,
    val screenshotPng: ByteArray? = null,
)

/* ---------- Protocolo MCP: capacidades declaradas como herramientas de primera clase ---------- */

/** Un parámetro de una herramienta MCP. Si `options` no está vacío, es una enumeración cerrada. */
class McpParam(
    val name: String,
    val description: String,
    val options: List<String> = emptyList(),
)

/**
 * Una herramienta MCP: una capacidad con nombre único, descripción, esquema de parámetros y ejecutor.
 * Hoy son gestos básicos hardcodeados; en v2 el asistente añadirá herramientas aprendidas del árbol de UI.
 */
class McpTool(
    val name: String,
    val description: String,
    val params: List<McpParam> = emptyList(),
    val run: suspend (Map<String, String>) -> Boolean,
)

/**
 * El "servidor" MCP: el catálogo de herramientas que el asistente puede invocar por function-calling.
 * Expone el esquema (para declararlas al modelo), la documentación (semilla de los workflows-MCP de v2)
 * y el despacho de una llamada. Añadir una capacidad = añadir una entrada a `tools`.
 */
class Mcp(gestures: Gestures) {

    val tools: List<McpTool> = listOf(
        McpTool("go_home", "Vuelve a la pantalla de inicio (home) de Android.") { gestures.home() },
        McpTool("open_app_drawer", "Abre el cajón de aplicaciones deslizando hacia arriba desde el home.") { gestures.appDrawer() },
        McpTool("open_notifications", "Despliega la barra de notificaciones deslizando desde el borde superior.") { gestures.notifications() },
        McpTool(
            "pan_home", "Cambia de panel dentro del home moviéndote hacia los lados.",
            listOf(McpParam("direction", "Hacia dónde moverse en el home", listOf("left", "right"))),
        ) { gestures.panHome(it["direction"] == "right") },
        McpTool(
            "scroll_menu", "Desliza (scroll) dentro de una lista o del cajón de aplicaciones.",
            listOf(McpParam("direction", "Dirección del desplazamiento", listOf("up", "down"))),
        ) { gestures.scrollMenu(it["direction"] != "up") },
    )

    fun tool(name: String) = tools.firstOrNull { it.name == name }

    /** Ejecuta una herramienta por nombre y devuelve un resultado legible para el modelo. */
    suspend fun call(name: String, args: Map<String, String>): String {
        val t = tool(name) ?: return "herramienta MCP desconocida: $name"
        return if (t.run(args)) "ok" else "el gesto no se pudo ejecutar"
    }

    /** Documentación del protocolo en Markdown: qué puede hacer el asistente vía MCP. */
    fun docMarkdown(): String = buildString {
        appendLine("# Herramientas MCP disponibles")
        appendLine("Gestos básicos para controlar el teléfono. El asistente elige entre estas herramientas y computer-use.")
        for (t in tools) {
            appendLine()
            appendLine("## `${t.name}`")
            appendLine(t.description)
            t.params.forEach { p ->
                val opts = if (p.options.isNotEmpty()) " (opciones: ${p.options.joinToString(", ")})" else ""
                appendLine("- **${p.name}**: ${p.description}$opts")
            }
        }
    }
}
