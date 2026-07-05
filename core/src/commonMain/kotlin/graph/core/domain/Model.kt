package graph.core.domain

import kotlinx.coroutines.delay

/**
 * Estado de la pantalla que ve el cerebro en cada turno.
 * Por defecto es TEXTO (georreferenciación por árbol de UI): `screen` (paquete·título) y `uiContext`
 * (tipo de pantalla + etiquetas visibles) bastan para que el modelo sepa dónde está y actúe con MCP.
 * El `screenshotPng` solo se adjunta cuando el modelo va a usar computer-use (mirar y tocar).
 */
class ScreenState(
    val screen: String,
    val uiContext: String,
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
 * `via` documenta CÓMO se ejecuta (gesto de accesibilidad vs Intent/API del sistema, sin tocar la UI).
 * Hoy están hardcodeadas; en v2 el asistente añadirá herramientas aprendidas del árbol de UI.
 */
class McpTool(
    val name: String,
    val description: String,
    val params: List<McpParam> = emptyList(),
    val via: String = "Intent/API de Android",
    val run: suspend (Map<String, String>) -> Boolean,
)

private fun Map<String, String>.int(key: String, default: Int) = this[key]?.trim()?.toIntOrNull() ?: default
private fun Map<String, String>.str(key: String) = this[key]?.trim() ?: ""

/**
 * El "servidor" MCP: el catálogo de herramientas que el asistente invoca por function-calling.
 * Dos familias hoy:
 *  - GESTOS de accesibilidad (navegación: home, cajón, notificaciones, paneo, scroll).
 *  - ACCIONES del sistema por Intent/API (abrir apps, alarmas, timers, llamadas, SMS, correo,
 *    calendario, búsqueda web, mapas, ajustes, portapapeles…): directas, sin navegar la interfaz.
 * Expone el esquema (para declararlas al modelo), la documentación (semilla de los workflows-MCP de
 * v2) y el despacho. Añadir una capacidad = añadir una entrada a `tools`.
 */
class Mcp(
    gestures: Gestures,
    system: SystemApi,
    learned: List<LearnedTool> = emptyList(),
    player: UiPlayer? = null,
    /** Pausa entre los steps (taps) de una herramienta aprendida enviados juntos; ajustable en la app. */
    stepDelay: () -> Long = { 350 },
    private val log: GraphLog = GraphLog { _, _ -> },
) {

    /** Detalle del último fallo de una herramienta aprendida (qué pasos no se pudieron tocar). */
    private var lastDetail: String? = null

    private val gestureTools = listOf(
        McpTool("go_home", "Vuelve a la pantalla de inicio (home) de Android.", via = GESTURE) { gestures.home() },
        McpTool("open_app_drawer", "Abre el cajón de aplicaciones deslizando hacia arriba desde el home.", via = GESTURE) { gestures.appDrawer() },
        McpTool("open_notifications", "Despliega la barra de notificaciones deslizando desde el borde superior.", via = GESTURE) { gestures.notifications() },
        McpTool("pan_home", "Cambia de panel dentro del home moviéndote hacia los lados.",
            listOf(McpParam("direction", "Hacia dónde moverse en el home", listOf("left", "right"))), GESTURE) { gestures.panHome(it["direction"] == "right") },
        McpTool("scroll_menu", "Desliza (scroll) dentro de una lista o del cajón de aplicaciones.",
            listOf(McpParam("direction", "Dirección del desplazamiento", listOf("up", "down"))), GESTURE) { gestures.scrollMenu(it["direction"] != "up") },
    )

    private val systemTools = listOf(
        McpTool("launch_app", "Abre una aplicación por su nombre directamente (Intent de lanzamiento), sin navegar la UI.",
            listOf(McpParam("app", "Nombre visible o paquete de la app"))) { system.openApp(it.str("app")) },
        McpTool("set_alarm", "Crea una alarma vía la API AlarmClock, sin abrir la interfaz del reloj.",
            listOf(McpParam("hour", "Hora 0-23"), McpParam("minute", "Minuto 0-59"), McpParam("message", "Etiqueta (opcional)"))) {
            system.setAlarm(it.int("hour", 8), it.int("minute", 0), it.str("message")) },
        McpTool("set_timer", "Inicia un temporizador vía AlarmClock, sin UI.",
            listOf(McpParam("seconds", "Duración en segundos"), McpParam("message", "Etiqueta (opcional)"))) {
            system.setTimer(it.int("seconds", 60), it.str("message")) },
        McpTool("show_alarms", "Abre la lista de alarmas del reloj.") { system.showAlarms() },
        McpTool("create_event", "Crea un evento de calendario vía Intent (prellenado).",
            listOf(McpParam("title", "Título del evento"), McpParam("start", "Inicio ISO-8601 local, p.ej. 2026-07-06T15:00 (opcional)"), McpParam("location", "Lugar (opcional)"))) {
            system.createEvent(it.str("title"), it.str("start"), it.str("location")) },
        McpTool("dial", "Abre el marcador con un número (sin llamar todavía).",
            listOf(McpParam("number", "Número de teléfono"))) { system.dial(it.str("number")) },
        McpTool("call", "Llama directamente a un número vía Intent (requiere permiso de llamada).",
            listOf(McpParam("number", "Número de teléfono"))) { system.call(it.str("number")) },
        McpTool("send_sms", "Abre un SMS prellenado a un número (el usuario confirma el envío).",
            listOf(McpParam("number", "Destinatario"), McpParam("message", "Texto (opcional)"))) {
            system.sendSms(it.str("number"), it.str("message")) },
        McpTool("send_email", "Abre un correo prellenado.",
            listOf(McpParam("to", "Destinatario (opcional)"), McpParam("subject", "Asunto (opcional)"), McpParam("body", "Cuerpo (opcional)"))) {
            system.sendEmail(it.str("to"), it.str("subject"), it.str("body")) },
        McpTool("web_search", "Busca en la web vía el Intent de búsqueda del sistema.",
            listOf(McpParam("query", "Qué buscar"))) { system.webSearch(it.str("query")) },
        McpTool("open_url", "Abre una URL en el navegador.",
            listOf(McpParam("url", "URL http(s)"))) { system.openUrl(it.str("url")) },
        McpTool("open_maps", "Abre Maps en un lugar o búsqueda.",
            listOf(McpParam("query", "Lugar o búsqueda"))) { system.maps(it.str("query")) },
        McpTool("directions", "Abre la navegación hacia un destino.",
            listOf(McpParam("destination", "Destino"))) { system.directions(it.str("destination")) },
        McpTool("open_camera", "Abre la cámara para tomar una foto.") { system.openCamera() },
        McpTool("open_settings", "Abre una pantalla de Ajustes del sistema.",
            listOf(McpParam("section", "Sección", listOf("general", "wifi", "bluetooth", "data", "display", "sound", "battery", "location", "apps")))) {
            system.openSettings(it["section"] ?: "general") },
        McpTool("share_text", "Abre el diálogo de compartir con un texto.",
            listOf(McpParam("text", "Texto a compartir"))) { system.shareText(it.str("text")) },
        McpTool("set_clipboard", "Copia un texto al portapapeles (sin UI).",
            listOf(McpParam("text", "Texto a copiar"))) { system.setClipboard(it.str("text")) },
        McpTool("set_volume", "Ajusta el volumen de un canal de audio directamente (sin UI). Útil para " +
            "asegurar que una alarma/llamada/medio se oiga.",
            listOf(
                McpParam("stream", "Canal de audio", listOf("media", "ring", "alarm", "notification", "call")),
                McpParam("percent", "Nivel 0-100 (usa 100 para asegurar que se oiga)"),
            )) { system.setVolume(it.str("stream").ifBlank { "media" }, it.int("percent", 100)) },
    )

    // Herramientas aprendidas: el mapa de una pantalla estructurado en la enseñanza. El modelo compone
    // en runtime la secuencia que necesite (taps) con el catálogo de elementos; se toca por árbol de UI.
    private val learnedTools = learned.map { lt ->
        val appNote = if (lt.app.isNotBlank()) "[app: ${lt.app}] " else ""
        McpTool(
            sanitize(lt.name),
            "$appNote${lt.description} Elementos disponibles (etiquetas exactas): ${lt.elements.joinToString(", ")}.",
            listOf(McpParam("taps", "Etiquetas a tocar EN ORDEN, separadas por comas (usa solo las disponibles)")),
            via = "aprendido (árbol de UI)",
        ) { args ->
            val labels = (args["taps"] ?: "").split(',').map { it.trim() }.filter { it.isNotBlank() }
            val failed = mutableListOf<String>()
            for (label in labels) {
                if (player?.tapLabel(label) != true) {
                    failed += label
                    log.log("mcp", "🧩 ${sanitize(lt.name)}: falló el paso \"$label\"")
                }
                delay(stepDelay())
            }
            if (failed.isNotEmpty())
                lastDetail = "pasos fallidos: ${failed.joinToString(", ")} (de ${labels.size})"
            else if (labels.isEmpty())
                lastDetail = "llamada sin taps: no había pasos que ejecutar"
            labels.isNotEmpty() && failed.isEmpty()
        }
    }

    val tools: List<McpTool> = gestureTools + systemTools + learnedTools

    fun tool(name: String) = tools.firstOrNull { it.name == name }

    private fun sanitize(s: String) =
        s.trim().lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("").trim('_').ifBlank { "learned_tool" }

    /** Ejecuta una herramienta por nombre y devuelve un resultado legible para el modelo. */
    suspend fun call(name: String, args: Map<String, String>): String {
        val t = tool(name) ?: return "herramienta MCP desconocida: $name"
        lastDetail = null
        return if (t.run(args)) "ok"
        else "la herramienta no se pudo ejecutar" + (lastDetail?.let { " — $it" } ?: "")
    }

    /** Documentación del protocolo en Markdown, agrupada por vía. Semilla de los workflows-MCP de v2. */
    fun docMarkdown(): String = buildString {
        appendLine("# Herramientas MCP disponibles")
        appendLine("Capacidades que el asistente invoca por function-calling. Muchas usan Intents/APIs del sistema (sin tocar la interfaz).")
        tools.groupBy { it.via }.forEach { (via, group) ->
            appendLine("\n## Vía: $via")
            for (t in group) {
                appendLine("\n### `${t.name}`")
                appendLine(t.description)
                t.params.forEach { p ->
                    val opts = if (p.options.isNotEmpty()) " (opciones: ${p.options.joinToString(", ")})" else ""
                    appendLine("- **${p.name}**: ${p.description}$opts")
                }
            }
        }
    }

    private companion object { const val GESTURE = "gesto de accesibilidad" }
}
