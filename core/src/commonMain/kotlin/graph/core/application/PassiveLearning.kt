package graph.core.application

import graph.core.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    /** Si está, en el modo iniciado por el usuario el asistente puede interrumpir y preguntar. */
    private val inquirer: LearningInquirer? = null,
    /** Si está, la enseñanza también graba el paso a paso como WORKFLOW (se cierra al salir de la app). */
    private val recorder: WorkflowRecorder? = null,
    /** Nombre visible de una app a partir de su paquete (para hablarle al usuario en su idioma). */
    private val appName: (String) -> String = { it },
    /**
     * Se invoca cada vez que un MCP nace o se refina (aprendizaje continuo): la plataforma lo usa
     * para RECONECTAR los workflows ya existentes de esa app con el catálogo nuevo (fire-and-forget).
     */
    private val onLearned: ((LearnedTool) -> Unit)? = null,
) {
    @Volatile var active = false
        private set

    private val mutex = Mutex()
    private var app = ""
    private var screen = ""
    private val clicks = mutableListOf<String>()
    private val elements = LinkedHashSet<String>()

    /** true solo cuando lo activó el usuario con el 🎓 (no en el auto-aprendizaje de una ejecución). */
    private var userMode = false
    private var lastInquiry: TimeMark? = null
    private var clicksSinceInquiry = 0

    /** Enciende la observación. `quiet` = sin anuncios (modo automático durante una ejecución). */
    suspend fun start(quiet: Boolean = false) {
        active = true
        recorder?.start(WorkflowSource.PASSIVE)
        userMode = !quiet
        lastInquiry = null
        clicksSinceInquiry = 0
        log.log("learn", if (quiet) "▶ enseñanza pasiva automática (ejecución)" else "▶ enseñanza pasiva activada")
        if (!quiet) voice.narrate("🎓 Observo mientras usas el teléfono; aprendo solo.")
    }

    /** Apaga el modo consolidando lo que quedara pendiente de la app actual. */
    suspend fun stop(quiet: Boolean = false) {
        active = false
        // Primero consolida el MCP y después cierra la traza del workflow: así el post-procesamiento
        // del workflow ya ve el catálogo fresco (misma pasada, sin carrera).
        mutex.withLock { consolidate() }
        recorder?.stop()
        log.log("learn", "■ enseñanza pasiva desactivada")
        if (!quiet) voice.narrate("🎓 Dejo de observar.")
    }

    /** Un clic del usuario dentro de una app, con el árbol de UI visible en ese momento. */
    suspend fun signal(app: String, screen: String, label: String, visible: List<String>) {
        if (!active) return
        val ask: Triple<String, List<String>, List<String>>? = mutex.withLock {
            if (app != this.app) { consolidate(); this.app = app; clicksSinceInquiry = 0 }
            this.screen = screen
            clicks += label
            elements += visible
            clicksSinceInquiry++
            log.log("learn", "señal clic \"$label\" en $app · ${clicks.size} señales")
            // ¿Toca preguntar? Solo en modo usuario, con señal fresca y sin acosar (≥3 clics y ≥20 s).
            if (userMode && inquirer != null && clicksSinceInquiry >= 3 &&
                (lastInquiry?.elapsedNow()?.inWholeSeconds ?: Long.MAX_VALUE) >= 20
            ) {
                lastInquiry = TimeSource.Monotonic.markNow()
                clicksSinceInquiry = 0
                Triple(app, clicks.toList().takeLast(12), elements.toList())
            } else null
        }
        // El mismo clic también es un step del workflow en grabación (traza aparte, cierra por app).
        // Va DESPUÉS del lock: si el clic llegó con cambio de app, el MCP de la app anterior ya quedó
        // consolidado y el post-procesamiento del workflow verá el catálogo fresco.
        recorder?.appChanged(app)
        recorder?.record(app, screen, label)
        // Fuera del lock: la pregunta corre async en la implementación (no bloquea nada).
        ask?.let { (a, c, e) -> inquirer?.maybeAsk(a, screen, c, e) }
    }

    /** El usuario pasó a otra app (o al home): consolida lo observado en la anterior. */
    suspend fun appChanged(newApp: String) {
        if (!active) return
        mutex.withLock {
            if (newApp != app) { consolidate(); app = newApp }
        }
        // Después de consolidar: salir de la app cierra también la traza del workflow, que se
        // estructura ya viendo el MCP recién consolidado (aprendizaje continuo, misma pasada).
        recorder?.appChanged(newApp)
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
        // Al usuario se le habla en su idioma, no en técnico: nada de nombres de herramientas ni conteos.
        voice.narrate("🧩 Ahora el uso de ${appName(app)} es mejor y más rápido.")
        // Aprendizaje continuo: el MCP nuevo/refinado puede cubrir pasos conscientes de workflows
        // que ya existían — la plataforma los reconecta en background.
        onLearned?.invoke(final)
    }
}
