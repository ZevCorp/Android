package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val NO_LOG = GraphLog { _, _ -> }

/**
 * ENSEÑANZA PASIVA. Se activa y desactiva con el 🎓 de la burbuja y no muestra ninguna interfaz:
 * ni barra, ni botón de detener, ni popups. El usuario usa el teléfono con normalidad y sus clics
 * son las señales de lo que le importa; el árbol de UI completo se captura igual que antes.
 *
 * La estructuración del MCP ocurre al SALIR de una app (cambio de app en primer plano, o al apagar
 * el modo): se envía al cerebro lo observado y SOLO se guarda lo que entendió con certeza muy alta
 * y tiene valor real según esos clics. Si la app ya tenía mapa, se refina en vez de duplicarse.
 */
class PassiveLearning(
    private val brain: LearningBrain,
    private val repo: LearnedToolRepository,
    private val voice: Voice,
    private val log: GraphLog = NO_LOG,
) {
    @Volatile var active = false
        private set

    private val mutex = Mutex()
    private var app = ""
    private var screen = ""
    private val clicks = mutableListOf<String>()
    private val elements = LinkedHashSet<String>()

    fun start() {
        active = true
        log.log("learn", "▶ enseñanza pasiva activada")
        voice.narrate("🎓 Observo mientras usas el teléfono; aprendo solo.")
    }

    /** Apaga el modo consolidando lo que quedara pendiente de la app actual. */
    suspend fun stop() {
        active = false
        mutex.withLock { consolidate() }
        log.log("learn", "■ enseñanza pasiva desactivada")
        voice.narrate("🎓 Dejo de observar.")
    }

    /** Un clic del usuario dentro de una app, con el árbol de UI visible en ese momento. */
    suspend fun signal(app: String, screen: String, label: String, visible: List<String>) {
        if (!active) return
        mutex.withLock {
            if (app != this.app) { consolidate(); this.app = app }
            this.screen = screen
            clicks += label
            elements += visible
            log.log("learn", "señal clic \"$label\" en $app · ${clicks.size} señales")
        }
    }

    /** El usuario pasó a otra app (o al home): consolida lo observado en la anterior. */
    suspend fun appChanged(newApp: String) {
        if (!active) return
        mutex.withLock {
            if (newApp != app) { consolidate(); app = newApp }
        }
    }

    /** Estructura lo acumulado como herramienta MCP (solo si hay certeza y valor) y limpia. */
    private suspend fun consolidate() {
        val app = this.app
        val clicks = this.clicks.toList()
        val elements = this.elements.toList()
        val screen = this.screen
        this.clicks.clear(); this.elements.clear(); this.app = ""; this.screen = ""
        if (app.isBlank() || clicks.isEmpty()) return
        log.log("learn", "consolidando $app · ${clicks.size} clics · ${elements.size} elementos vistos")
        val previous = repo.list().firstOrNull { it.app == app }
        val tool = runCatching { brain.consolidate(app, screen, clicks, elements, previous) }
            .getOrElse { log.log("learn", "consolidación falló: ${it.message}"); null }
        if (tool == null || tool.elements.isEmpty()) {
            log.log("learn", "sin certeza o valor suficiente: no guardo nada de $app")
            return
        }
        // Si la app ya tenía mapa se actualiza bajo el mismo nombre: nunca duplicados.
        val final = tool.copy(name = previous?.name ?: tool.name, app = app)
        repo.save(final)
        log.log("learn", "■ ${if (previous != null) "refinado" else "aprendido"}: ${final.name} · ${final.elements.size} elementos")
        voice.narrate("🧩 ${if (previous != null) "Refiné" else "Aprendí"} \"${final.name}\" (${final.elements.size} elementos).")
    }
}
