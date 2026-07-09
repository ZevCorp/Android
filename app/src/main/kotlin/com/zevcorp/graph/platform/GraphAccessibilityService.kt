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
import android.view.MotionEvent
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
        deepCapture.disable()
        super.onDestroy()
    }

    /* ---------- Enseñanza pasiva: los clics del usuario usando el teléfono son las señales ---------- */

    private val app get() = GraphApp.instance

    /** Última app "real" en primer plano: al cambiar, se consolida lo observado en la anterior. */
    private var foregroundApp = ""

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
                // La captura profunda (vía moderna, panel de Desarrollador) ya cubre este toque de forma
                // más robusta —incluye apps que ni siquiera emiten este evento—: no duplicar la señal.
                if (deepCapture.active) return
                if (!isRealApp(pkg) || pkg == launcherPkg) return
                val src = event.source ?: return
                val label = labelOf(src).takeIf { it.isNotBlank() } ?: return
                // Diagnóstico (modo visualización del 🎓): ilumina el nodo que el AGENTE resolvería para
                // esta etiqueta con su MISMO mecanismo, y detecta el bug si no es el que tocaste. Solo visual.
                if (visualizing) probeResolved(src, label)
                // Enseñanza pasiva: comportamiento existente, intacto.
                if (app.passive.active) {
                    val screenNow = currentScreen()
                    val visible = elementsNow()
                    app.scope.launch { app.passive.signal(pkg, screenNow, label, visible) }
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

    /* ---------- Captura profunda de toques (vía moderna, toggle en el panel de Desarrollador) ---------- */

    override fun onMotionEvent(event: MotionEvent) = deepCapture.handle(event)

    private val deepCapture by lazy {
        DeepTouchCapture(
            this,
            onExplore = { x, y -> deepExploreAt(x, y) },
            onActivate = { _, _ -> deepActivate() },
            onKillSwitch = { reason ->
                deepEnabled = false // exige rehabilitar a mano en el panel: no reintenta y falla en bucle
                uiHandler.post { highlighter.probe(null) }
                LogBus.log("deep-touch", "🛑 apagado automático: $reason. Rehabilítala en el panel si quieres.")
            },
            // Deja pasar los toques sobre la carita/panel para poder abrir el panel y salir del modo.
            isPassthrough = { x, y -> bubble?.overOwnUi(x, y) == true },
        )
    }

    /** Capacidad habilitada desde el panel de Desarrollador. NO intercepta por sí sola: solo permite que
     *  el modo 🎓 use la detección moderna al mantenerlo oprimido. En memoria (nunca "encendida" sola). */
    @Volatile private var deepEnabled = false

    val deepTouchCaptureSupported get() = DeepTouchCapture.supported
    val deepTouchCaptureEnabled get() = deepEnabled

    /** Habilita/deshabilita la CAPACIDAD (no intercepta aquí; se activa al mantener el 🎓). */
    fun setDeepTouchCaptureEnabled(on: Boolean): Boolean {
        deepEnabled = on && DeepTouchCapture.supported
        // Si se apaga la capacidad estando ya activa (🎓 oprimido), se corta la interceptación ya.
        if (!deepEnabled && deepCapture.active) deepCapture.disable()
        LogBus.log("deep-touch", if (deepEnabled)
            "habilitada · se activa al mantener oprimido el 🎓 (un toque explora, doble toque interactúa)"
        else "deshabilitada")
        return deepEnabled
    }

    private class TouchProbe(
        val label: String, val screen: String, val pkg: String, val elements: List<String>,
    )

    @Volatile private var pendingProbe: TouchProbe? = null

    /**
     * UN toque en modo TalkBack (consumido, la app no lo recibe): identifica el elemento bajo el dedo
     * (nodeAt, igual que computer-use) y lo ENMARCA de forma persistente. Resuelve su etiqueta EXACTAMENTE
     * como el agente en tapLabel (findByLabel + clickableAncestor): si resolvería a otro elemento, el
     * marco es ROJO y se registra el bug. Guarda el snapshot (estado PRE-toque) por si hay doble toque.
     */
    private fun deepExploreAt(x: Int, y: Int) {
        val node = nodeAt(x, y)
        if (node == null) { highlighter.probe(null); pendingProbe = null; return }
        val label = labelOf(node).ifBlank { labelOf(clickableAncestor(node) ?: node) }
        val touchedTarget = clickableAncestor(node) ?: node
        val touchedRect = Rect().also { touchedTarget.getBoundsInScreen(it) }
        val resolved = label.takeIf { it.isNotBlank() }?.let { findByLabel(it) }
        val resolvedTarget = resolved?.let { clickableAncestor(it) ?: it }
        val resolvedRect = resolvedTarget?.let { t -> Rect().also { t.getBoundsInScreen(it) } } ?: touchedRect
        val isBug = label.isNotBlank() && resolvedRect != touchedRect
        // Marco PERSISTENTE (no auto-borra): así ves qué está seleccionado, como el foco de TalkBack.
        uiHandler.removeCallbacks(probeClear)
        highlighter.probe(if (isBug) resolvedRect else touchedRect, isBug)
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        pendingProbe = if (label.isBlank()) null
            else TouchProbe(label, currentScreen(), pkg, elementsNow())
        if (isBug) {
            LogBus.log("bug-ui", "❌ \"$label\": enmarcaste ${touchedRect.centerX()},${touchedRect.centerY()} " +
                "pero el agente iría a ${resolvedRect.centerX()},${resolvedRect.centerY()}")
            UiBugBus.report(currentScreen(), label, touchedRect, resolvedRect)
        } else {
            LogBus.log("deep-touch", "👆 exploras \"${label.ifBlank { "(sin etiqueta)" }}\" — doble toque para interactuar")
        }
    }

    /**
     * DOBLE toque: el clic real ya se reinyectó (lo hizo DeepTouchCapture). Aquí se envía la señal de
     * aprendizaje pasivo con el contexto PRE-toque guardado — así apps que nunca emiten TYPE_VIEW_CLICKED
     * (Spotify, Compose) sí se aprenden — y se apaga el marco tras un momento.
     */
    private fun deepActivate() {
        val p = pendingProbe
        pendingProbe = null
        uiHandler.postDelayed(probeClear, 500) // la UI va a cambiar: apaga el marco
        if (p != null && p.label.isNotBlank() && app.passive.active && isRealApp(p.pkg) && p.pkg != launcherPkg) {
            LogBus.log("deep-touch", "✅ interactúas \"${p.label}\" (doble toque)")
            app.scope.launch { app.passive.signal(p.pkg, p.screen, p.label, p.elements) }
        }
    }

    /* ---------- Ver la detección (mantener oprimido el 🎓): contorno de TODO lo detectado, verde = aprendido ---------- */

    @Volatile private var visualizing = false
    private var learnedLabels: Map<String, Set<String>> = emptyMap() // paquete → etiquetas (minúsculas)
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val overlayRefresh = Runnable { refreshLearnedOverlayNow() }

    /** Alterna el modo: dibuja el contorno de TODO elemento accionable que el sistema detecta en la app
     *  visible (verde = ya aprendido/trackeado en MCPs, acento = detectado pero aún no aprendido). */
    fun toggleLearnedVisualization(): Boolean {
        visualizing = !visualizing
        if (visualizing) {
            // Si la captura profunda está HABILITADA en el panel, el 🎓 la ACTIVA ahora (modo TalkBack:
            // un toque explora/enmarca, doble toque interactúa). Si no, sigue el diagnóstico por evento.
            if (deepEnabled) deepCapture.enable()
            app.scope.launch {
                val tools = app.learnedTools.list()
                learnedLabels = tools.groupBy { it.app }
                    .mapValues { (_, ts) -> ts.flatMap { t -> t.elements }.map { it.lowercase() }.toSet() }
                withContext(Dispatchers.Main) { refreshLearnedOverlayNow() }
            }
        } else {
            deepCapture.disable()
            uiHandler.removeCallbacks(overlayRefresh)
            uiHandler.removeCallbacks(probeClear)
            highlighter.hide()
        }
        LogBus.log("learn", if (visualizing) "👁 visualización de lo aprendido ON" else "visualización OFF")
        return visualizing
    }

    private val probeClear = Runnable { highlighter.probe(null) }

    /**
     * DIAGNÓSTICO (solo visual, modo 🎓): al tocar TÚ un elemento, resuelve su etiqueta EXACTAMENTE como
     * el agente en tapLabel (findByLabel + clickableAncestor) y compara el resultado con lo que tocaste.
     * Si coinciden, destella en NARANJA (resolución correcta). Si por etiquetas duplicadas resuelve a
     * OTRO elemento (bounds distintos) —el agente llamaría por ID uno equivocado—, destella en ROJO y lo
     * registra en UiBugBus (panel "Bugs de UI"). No modifica el mecanismo real: solo lo re-ejecuta.
     */
    private fun probeResolved(source: AccessibilityNodeInfo, label: String) {
        // Resolver contra el árbol DEL MOMENTO DEL CLIC (la raíz del propio nodo del evento), no contra
        // rootInActiveWindow: si la UI cambia al instante (p.ej. Spotify), el nodo tocado sigue vivo en
        // el snapshot del evento y la detección NO se pierde. findByLabel hace el mismo DFS que usa el
        // agente, pero sobre la misma pantalla en la que tocaste, para que la comparación sea válida.
        val clickRoot = generateSequence(source) { runCatching { it.parent }.getOrNull() }.last()
        val resolved = findByLabel(clickRoot, label) ?: source
        val resolvedTarget = clickableAncestor(resolved) ?: resolved
        val resolvedRect = Rect().also { resolvedTarget.getBoundsInScreen(it) }
        // Lo que TÚ tocaste, llevado a su ancestro clickable igual que hace tapLabel al ejecutar.
        val touchedTarget = clickableAncestor(source) ?: source
        val touchedRect = Rect().also { touchedTarget.getBoundsInScreen(it) }
        val isBug = resolvedRect != touchedRect
        highlighter.probe(resolvedRect, isBug)
        uiHandler.removeCallbacks(probeClear)
        uiHandler.postDelayed(probeClear, if (isBug) 3000 else 1600)
        if (isBug) {
            LogBus.log("bug-ui", "❌ \"$label\": tocaste ${touchedRect.centerX()},${touchedRect.centerY()} " +
                "pero el agente iría a ${resolvedRect.centerX()},${resolvedRect.centerY()}")
            UiBugBus.report(currentScreen(), label, touchedRect, resolvedRect)
        } else {
            LogBus.log("learn", "🔎 prueba: \"$label\" resuelve correcto en ${resolvedRect.centerX()},${resolvedRect.centerY()}")
        }
    }

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
        // Dos cubetas: lo que el sistema DETECTA como accionable (visible + clickable/editable) se
        // resalta todo; los que ya están aprendidos van aparte para pintarse en verde. Aunque no haya
        // nada aprendido en esta app, se muestran igual todos los detectados (así se ve la cobertura).
        val learned = mutableListOf<Rect>()
        val detected = mutableListOf<Rect>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.isVisibleToUser && (n.isClickable || n.isEditable)) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (labelOf(n).lowercase() in labels) learned += r else detected += r
            }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        highlighter.show(learned, detected)
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

    /** Primer nodo (en orden de árbol, DFS) cuya etiqueta coincide, buscando dentro de `root`. */
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

    /* ---------- Primitivas de accesibilidad ---------- */

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
