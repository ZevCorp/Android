package com.zevcorp.graph.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.ui.FloatingBubble
import com.zevcorp.graph.ui.HighlightOverlay
import graph.core.domain.Gestures
import graph.core.domain.LearningSurface
import graph.core.domain.Phone
import graph.core.domain.ScreenState
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Superficie del teléfono para Android: implementa las primitivas de computer-use (`Phone`) y los
 * gestos semánticos que se exponen como herramientas MCP (`Gestures`), todo vía accesibilidad.
 */
class GraphAccessibilityService : AccessibilityService(), Phone, Gestures, LearningSurface {

    var bubble: FloatingBubble? = null
        private set

    private val highlighter by lazy { HighlightOverlay(this) }

    override fun onServiceConnected() {
        GraphApp.instance.ui = this
        bubble = FloatingBubble(this).also { it.show() }
        LogBus.log("ui", "servicio de accesibilidad conectado · burbuja visible")
    }

    override fun onDestroy() {
        if (GraphApp.instance.ui === this) GraphApp.instance.ui = null
        bubble?.destroy()
        highlighter.destroy()
        super.onDestroy()
    }

    /* ---------- Enseñanza pasiva: los clics del usuario usando el teléfono son las señales ---------- */

    private val app get() = GraphApp.instance

    /** Última app "real" en primer plano: al cambiar, se consolida lo observado en la anterior. */
    private var foregroundApp = ""

    /** Paquete de la app en primer plano ahora mismo (lo usa el dictado de notas en vivo). */
    val currentApp: String get() = foregroundApp

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!isRealApp(pkg) || pkg == foregroundApp) return
                foregroundApp = pkg
                if (app.passive.active) app.scope.launch { app.passive.appChanged(pkg) }
                if (visualizing) refreshLearnedOverlay()
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // El diagnóstico visual (naranja/rojo) va con `visualizing` (solo mantener el 🎓, sin
                // necesitar enseñanza activa); el diagnóstico AUTÓNOMO por LLM va con el aprendizaje
                // pasivo, sin necesitar la visualización. Son dos gates independientes, misma señal.
                val observing = visualizing || app.passive.active || app.recorder.active
                if (!observing || !isRealApp(pkg) || pkg == launcherPkg) return
                val src = event.source
                val label = src?.let { labelOf(it) } ?: ""
                val screenNow = currentScreen()
                if (label.isNotBlank() && (visualizing || app.passive.active)) {
                    // detectClickMismatch decide internamente qué hacer con cada señal: destello visual
                    // solo si `visualizing`, diagnóstico LLM solo si `app.passive.active`.
                    src?.let { runCatching { detectClickMismatch(it, pkg, screenNow) } }
                }
                if (app.passive.active && label.isNotBlank()) {
                    app.scope.launch { app.passive.signal(pkg, screenNow, label, elementsNow()) }
                } else if (app.recorder.active) {
                    // Enseñanza activa, o clic que el árbol de UI no logró etiquetar: igual es un
                    // step del workflow (sin etiqueta quedará como paso consciente).
                    app.scope.launch { app.recorder.record(pkg, screenNow, label) }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                if (visualizing) refreshLearnedOverlay()
        }
    }

    /** Una app donde tiene sentido aprender/consolidar: ni Graph, ni sistema, ni teclado flotante. */
    private fun isRealApp(pkg: String) = pkg.isNotBlank() && pkg != packageName &&
        pkg != "com.android.systemui" &&
        (pkg == launcherPkg || packageManager.getLaunchIntentForPackage(pkg) != null)

    override fun onInterrupt() {}

    /* ---------- Ver lo aprendido (mantener oprimido el 🎓): contornos de lo ya trackeado ---------- */

    @Volatile private var visualizing = false
    private var learnedLabels: Map<String, Set<String>> = emptyMap() // paquete → etiquetas (minúsculas)
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val overlayRefresh = Runnable { refreshLearnedOverlayNow() }

    /** Alterna el modo: dibuja el contorno de TODO elemento accionable que el sistema detecta en la app
     *  visible (verde = ya aprendido/trackeado en MCPs, acento = detectado pero aún no aprendido). */
    fun toggleLearnedVisualization(): Boolean {
        visualizing = !visualizing
        if (visualizing) {
            app.scope.launch {
                val tools = app.learnedTools.list()
                learnedLabels = tools.groupBy { it.app }
                    .mapValues { (_, ts) -> ts.flatMap { t -> t.elements }.map { it.lowercase() }.toSet() }
                withContext(Dispatchers.Main) { refreshLearnedOverlayNow() }
            }
        } else {
            uiHandler.removeCallbacks(overlayRefresh)
            uiHandler.removeCallbacks(probeClear)
            highlighter.hide()
        }
        LogBus.log("learn", if (visualizing) "👁 visualización de lo aprendido ON" else "visualización OFF")
        return visualizing
    }

    private val probeClear = Runnable { highlighter.probe(null) }

    /** Debounce con cola: coalescea la ráfaga de eventos pero SIEMPRE ejecuta el último refresco,
     *  para que los recuadros terminen alineados con la UI final (scroll, animaciones, navegación). */
    private fun refreshLearnedOverlay() {
        uiHandler.removeCallbacks(overlayRefresh)
        uiHandler.postDelayed(overlayRefresh, 90)
    }

    private fun refreshLearnedOverlayNow() {
        if (!visualizing) return
        val root = rootInActiveWindow ?: return highlighter.show(emptyList(), emptyList())
        val pkg = root.packageName?.toString() ?: ""
        // Mapas de esta app + mapas antiguos sin paquete (compatibilidad): se intenta igual.
        val labels = learnedLabels[pkg].orEmpty() + learnedLabels[""].orEmpty()
        val learnedRects = mutableListOf<Rect>()
        val detectedRects = mutableListOf<Rect>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.isVisibleToUser && (n.isClickable || n.isEditable)) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (labelOf(n).lowercase() in labels) learnedRects += r else detectedRects += r
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        highlighter.show(learnedRects, detectedRects)
    }

    /* ---------- Estado de pantalla (georreferenciación por árbol de UI) ---------- */

    override suspend fun state(withScreenshot: Boolean): ScreenState {
        val m = resources.displayMetrics
        return ScreenState(currentScreen(), uiContext(), m.widthPixels, m.heightPixels,
            if (withScreenshot) screenshot() else null)
    }

    private fun currentScreen(): String {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        val title = windows.firstOrNull { it.isActive }?.title?.toString() ?: ""
        return "$pkg · $title".trim(' ', '·')
    }

    /** Paquete del launcher por defecto: sirve para reconocer que estamos en el home/cajón de apps. */
    private val launcherPkg: String by lazy {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: ""
    }

    /**
     * Resumen TEXTUAL de dónde está parado el asistente, sin imagen. Reúne las señales genéricas que,
     * sumadas, permiten al modelo inferir el contexto: paquete, tipo de pantalla (home/cajón, sistema,
     * teclado, app), conteo de clickeables/campos, campo enfocado y las etiquetas visibles más útiles.
     */
    private fun uiContext(): String {
        val root = rootInActiveWindow ?: return "sin contenido accesible (pantalla vacía o protegida)"
        val pkg = root.packageName?.toString() ?: ""
        val kb = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val kind = when {
            pkg == launcherPkg -> "launcher de Android (home o cajón de apps)"
            pkg == "com.android.systemui" -> "interfaz del sistema (barra de notificaciones / ajustes rápidos)"
            else -> "aplicación"
        }
        val labels = LinkedHashSet<String>()
        var clickables = 0
        var fields = 0
        var focused = ""
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.isClickable) clickables++
            if (n.isEditable) { fields++; if (n.isFocused) focused = labelOf(n) }
            labelOf(n).takeIf { it.isNotBlank() && it.length <= 40 }?.let { labels += it }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return buildString {
            append("paquete: ${pkg.ifBlank { "?" }}\n")
            append("tipo: $kind${if (kb) " · teclado abierto" else ""}\n")
            append("clickeables: $clickables · campos de texto: $fields")
            if (focused.isNotBlank()) append(" (enfocado: \"$focused\")")
            append("\netiquetas visibles: ")
            append(labels.take(28).joinToString(" · ").ifBlank { "(ninguna)" })
        }
    }

    private fun labelOf(n: AccessibilityNodeInfo): String = sequenceOf(
        n.contentDescription,
        if (n.isEditable) n.hintText else n.text,
        n.viewIdResourceName?.substringAfterLast('/'),
    ).firstOrNull { !it.isNullOrBlank() }?.toString()?.take(40) ?: ""

    private suspend fun screenshot(): ByteArray? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bmp == null) return cont.resume(null)
                val scaled = if (bmp.width > 1080)
                    Bitmap.createScaledBitmap(bmp, 1080, bmp.height * 1080 / bmp.width, true) else bmp
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
                cont.resume(out.toByteArray())
            }

            override fun onFailure(errorCode: Int) = cont.resume(null)
        })
    }

    /* ---------- Búsqueda en el árbol para computer-use ---------- */

    private fun nodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.contains(x, y) && (n.isClickable || n.isEditable)) {
                val area = r.width().toLong() * r.height()
                if (area <= bestArea) { best = n; bestArea = area }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return best
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo?) =
        node?.let { n -> generateSequence(n) { it.parent }.firstOrNull { it.isClickable } }

    private fun editableSelf(node: AccessibilityNodeInfo?) =
        node?.let { n -> generateSequence(n) { it.parent }.firstOrNull { it.isEditable } }

    /* ---------- Phone: primitivas de computer-use ---------- */

    override suspend fun tap(x: Int, y: Int): Boolean {
        bubble?.flyTo(x, y)
        val clickable = clickableAncestor(nodeAt(x, y))
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || tapGesture(x, y)
        LogBus.log("ui", "tap($x,$y) · ok=$ok")
        return ok
    }

    override suspend fun type(x: Int, y: Int, text: String): Boolean {
        val node = editableSelf(nodeAt(x, y)) ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val r = Rect().also { node.getBoundsInScreen(it) }
        bubble?.flyTo(r.centerX(), r.centerY())
        return setText(node, text)
    }

    override suspend fun openApp(query: String): Boolean {
        val q = query.trim()
        if (q.startsWith("http")) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(1500)
            return true
        }
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { it.packageName.equals(q, true) }
            ?: apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(q, true) }
            ?: return false
        val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        LogBus.log("ui", "openApp: ${match.packageName}")
        delay(1500)
        return true
    }

    override suspend fun scroll(down: Boolean): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val (y1, y2) = if (down) m.heightPixels * 0.7f to m.heightPixels * 0.3f
        else m.heightPixels * 0.3f to m.heightPixels * 0.7f
        return gesture(Path().apply { moveTo(x, y1); lineTo(x, y2) }, 300)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long): Boolean =
        gesture(Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat())
        }, ms.coerceIn(80, 10_000))

    override suspend fun pressKey(key: String): Boolean = when {
        key.contains("back", true) -> performGlobalAction(GLOBAL_ACTION_BACK)
        key.contains("home", true) -> performGlobalAction(GLOBAL_ACTION_HOME)
        key.contains("enter", true) || key.contains("return", true) ->
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
        else -> false
    }

    /* ---------- Gestures: herramientas MCP ---------- */

    override suspend fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    override suspend fun notifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    override suspend fun appDrawer(): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        return gesture(Path().apply { moveTo(x, m.heightPixels * 0.92f); lineTo(x, m.heightPixels * 0.30f) }, 260)
    }

    override suspend fun panHome(right: Boolean): Boolean {
        val m = resources.displayMetrics
        val y = m.heightPixels * 0.5f
        val (x1, x2) = if (right) m.widthPixels * 0.82f to m.widthPixels * 0.18f
        else m.widthPixels * 0.18f to m.widthPixels * 0.82f
        return gesture(Path().apply { moveTo(x1, y); lineTo(x2, y) }, 240)
    }

    override suspend fun scrollMenu(down: Boolean): Boolean = scroll(down)

    /* ---------- LearningSurface: leer el árbol y tocar elementos por etiqueta ---------- */

    override suspend fun screen(): String = currentScreen()

    /** Etiquetas de los elementos tocables de la pantalla actual (lo que el cerebro puede secuenciar). */
    override suspend fun elements(): List<String> = elementsNow()

    private fun elementsNow(): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.isClickable || n.isEditable) labelOf(n).takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out.take(48).toList()
    }

    override suspend fun tapLabel(label: String): Boolean {
        val node = findByLabel(label)
            ?: return false.also { LogBus.log("mcp", "tap \"$label\": no está en la pantalla actual ($foregroundApp)") }
        val target = clickableAncestor(node) ?: node
        val r = Rect().also { target.getBoundsInScreen(it) }
        bubble?.flyTo(r.centerX(), r.centerY())
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapGesture(r.centerX(), r.centerY())
        if (!ok) LogBus.log("mcp", "tap \"$label\": lo encontré pero ni ACTION_CLICK ni el gesto funcionaron")
        return ok
    }

    private fun findByLabel(label: String): AccessibilityNodeInfo? = findByLabel(rootInActiveWindow, label)

    /** Igual que la resolución del agente (primer nodo con esa etiqueta), pero contra un root dado:
     *  se usa para el diagnóstico contra el snapshot del clic (la UI puede cambiar al instante). */
    private fun findByLabel(root: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        root ?: return null
        var found: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (found == null && labelOf(n).equals(label, true)) found = n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return found
    }

    /* ---------- Diagnóstico autónomo de fallos de clic (IDs ambiguos) ---------- */

    /**
     * Corre en cada clic del aprendizaje pasivo (SIN necesitar la visualización 🎓). Resuelve la
     * etiqueta del elemento tocado EXACTAMENTE como el agente lo haría al replicar (findByLabel +
     * ancestro clickable) y compara con lo que de verdad se tocó. Si por etiquetas duplicadas resuelve
     * a OTRO elemento —el bug de "siempre el primer chat"—, arma el snapshot rico de la pantalla y
     * dispara el diagnóstico del LLM (que halla el ID único y resume cómo endurecer la detección).
     */
    private fun detectClickMismatch(source: AccessibilityNodeInfo, pkg: String, screen: String) {
        val label = labelOf(source).ifBlank { labelOf(clickableAncestor(source) ?: source) }
        if (label.isBlank()) return
        // Resolver contra el árbol del MOMENTO del clic (raíz del propio nodo del evento).
        val clickRoot = generateSequence(source) { runCatching { it.parent }.getOrNull() }.last()
        val touchedTarget = clickableAncestor(source) ?: source
        val resolved = findByLabel(clickRoot, label) ?: return
        val resolvedTarget = clickableAncestor(resolved) ?: resolved
        val touchedRect = Rect().also { touchedTarget.getBoundsInScreen(it) }
        val resolvedRect = Rect().also { resolvedTarget.getBoundsInScreen(it) }
        val isBug = touchedRect != resolvedRect

        // Destello VISUAL (solo si estás con el 🎓 en visualización): naranja si resuelve correcto,
        // rojo si es un bug — encima de los recuadros estáticos verde/detectado, momentáneo.
        if (visualizing) {
            uiHandler.removeCallbacks(probeClear)
            highlighter.probe(if (isBug) resolvedRect else touchedRect, isBug)
            uiHandler.postDelayed(probeClear, if (isBug) 3000 else 1600)
        }
        if (!isBug) {
            if (visualizing) LogBus.log("learn", "🔎 \"$label\" resuelve correcto en ${touchedRect.centerX()},${touchedRect.centerY()}")
            return // resuelve correcto: no hay bug que diagnosticar
        }
        // El diagnóstico AUTÓNOMO (LLM) requiere aprendizaje pasivo activo (aunque solo estés
        // visualizando con el 🎓, sin enseñanza en curso, el destello rojo ya se mostró arriba).
        if (!app.passive.active) return

        val snapshot = snapshotForBug(clickRoot, touchedRect, resolvedRect)
        LogBus.log("bug-ui", "❌ \"$label\": tocaste ${touchedRect.centerX()},${touchedRect.centerY()} " +
            "pero el agente iría a ${resolvedRect.centerX()},${resolvedRect.centerY()}")
        app.diagnoseClickBug(pkg, screen, label,
            "${touchedRect.centerX()},${touchedRect.centerY()}",
            "${resolvedRect.centerX()},${resolvedRect.centerY()}", snapshot)
    }

    /** Dump de los elementos accionables con sus atributos + textos descendientes, marcando el tocado
     *  y el que el agente resolvería. Es lo que el LLM lee para hallar el identificador único correcto. */
    private fun snapshotForBug(root: AccessibilityNodeInfo?, touched: Rect, resolved: Rect): String {
        val out = StringBuilder()
        var idx = 0
        fun descendantTexts(n: AccessibilityNodeInfo): String {
            val texts = LinkedHashSet<String>()
            fun dig(x: AccessibilityNodeInfo?, depth: Int) {
                x ?: return
                if (depth > 0) x.text?.toString()?.trim()?.takeIf { it.isNotBlank() && it.length <= 40 }?.let { texts += it }
                for (i in 0 until x.childCount) dig(x.getChild(i), depth + 1)
            }
            dig(n, 0)
            return texts.take(4).joinToString(" · ")
        }
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if ((n.isClickable || n.isEditable) && idx < 40) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val target = clickableAncestor(n) ?: n
                val tr = Rect().also { target.getBoundsInScreen(it) }
                val mark = when { tr == touched -> " [TOCADO]"; tr == resolved -> " [AGENTE]"; else -> "" }
                val vid = n.viewIdResourceName?.substringAfterLast('/') ?: ""
                val cd = n.contentDescription?.toString()?.take(40) ?: ""
                val tx = n.text?.toString()?.take(40) ?: ""
                val kids = descendantTexts(n)
                out.append("#${idx}$mark label=\"${labelOf(n)}\" text=\"$tx\" desc=\"$cd\" id=\"$vid\" " +
                    "class=${n.className?.toString()?.substringAfterLast('.') ?: "?"} centro=(${r.centerX()},${r.centerY()})")
                if (kids.isNotBlank()) out.append(" [dentro: $kids]")
                out.append("\n")
                idx++
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out.toString().ifBlank { "(sin elementos accionables)" }
    }

    /* ---------- Primitivas de accesibilidad ---------- */

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** El campo editable objetivo para el dictado: el que tiene el foco de entrada, o si no, el
     *  editable VISIBLE de mayor área (el cuerpo de una nota suele ser el EditText multilínea más
     *  grande de la pantalla). Así el dictado acierta el campo correcto sin depender de la app. */
    private fun bestEditable(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && it.isVisibleToUser }?.let { return it }
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.isEditable && n.isVisibleToUser) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return best
    }

    /**
     * DICTADO EN VIVO (modo reunión): escribe —reemplazando— el texto del campo de nota enfocado/más
     * grande y deja el cursor al final para que se vea "creciendo". Reemplazar el texto completo cada
     * vez lo hace idempotente: la nota siempre refleja EXACTAMENTE lo acumulado, aunque se pierda el
     * foco o se vuelva de otra app. Devuelve false si no hay ningún campo editable en pantalla.
     */
    fun writeNote(text: String): Boolean {
        val node = bestEditable() ?: return false
        val ok = setText(node, text)
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            })
        }
        return ok
    }

    private suspend fun tapGesture(x: Int, y: Int) =
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 80)

    private suspend fun gesture(path: Path, duration: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            val dispatched = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) = cont.resume(true)
                override fun onCancelled(d: GestureDescription?) = cont.resume(false)
            }, null)
            if (!dispatched) cont.resume(false)
        }
}
