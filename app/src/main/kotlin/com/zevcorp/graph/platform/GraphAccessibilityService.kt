package com.zevcorp.graph.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zevcorp.graph.GraphApp
import com.zevcorp.graph.ui.FloatingBubble
import graph.core.domain.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Superficie de UI de Android: el análogo del recorder DOM de la extensión de Chrome de Graph.
 * Captura clics/texto del usuario desde el árbol de accesibilidad y ejecuta steps sobre él.
 */
class GraphAccessibilityService : AccessibilityService(), UiSurface {

    private val _userActions = MutableSharedFlow<Step>(extraBufferCapacity = 128)
    override val userActions = _userActions.asSharedFlow()

    @Volatile private var capturing = false
    private val pendingInputs = LinkedHashMap<String, Step>()

    var bubble: FloatingBubble? = null
        private set

    override fun onServiceConnected() {
        GraphApp.instance.ui = this
        bubble = FloatingBubble(this).also { it.show() }
        LogBus.log("ui", "servicio de accesibilidad conectado · burbuja visible")
    }

    override fun onDestroy() {
        if (GraphApp.instance.ui === this) GraphApp.instance.ui = null
        bubble?.destroy()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /* ---------- Captura de acciones del usuario (Teaching feedback / demos en Learning) ---------- */

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!capturing || event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                flushInputs()
                event.source?.let {
                    val step = stepFor(ActionType.CLICK, it)
                    LogBus.log("uitree", "evento CLICK ${step.selector.short()}")
                    _userActions.tryEmit(step)
                }
            }
            // Como en Graph: los eventos de tecleo se funden y gana el último valor por selector.
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val node = event.source ?: return
                val step = stepFor(ActionType.INPUT, node, node.text?.toString() ?: "")
                pendingInputs[step.selector.toString()] = step
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> flushInputs()
        }
    }

    private fun flushInputs() {
        pendingInputs.values.forEach { _userActions.tryEmit(it) }
        pendingInputs.clear()
    }

    override fun setCapturing(enabled: Boolean) {
        if (!enabled) flushInputs()
        capturing = enabled
    }

    /* ---------- Estado de pantalla ---------- */

    override suspend fun state(): ScreenState {
        // La carita se queda visible durante la ejecución (flota sobre el objetivo, arriba del punto de
        // clic, sin taparlo y en modo pass-through). A Gemini se le indica en el prompt que la ignore.
        val metrics = resources.displayMetrics
        return ScreenState(currentScreen(), metrics.widthPixels, metrics.heightPixels, screenshot())
    }

    private fun currentScreen(): String {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        val title = windows.firstOrNull { it.isActive }?.title?.toString() ?: ""
        return "$pkg · $title".trim(' ', '·')
    }

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

    /* ---------- Selectores semánticos (≈ buildElementSelector del content script) ---------- */

    private fun selectorOf(node: AccessibilityNodeInfo) = Selector(
        viewId = node.viewIdResourceName ?: "",
        // en campos editables el texto es el valor tecleado: no sirve como localizador
        text = if (node.isEditable) "" else node.text?.toString()?.take(60) ?: "",
        contentDesc = node.contentDescription?.toString() ?: "",
        className = node.className?.toString() ?: "",
        pkg = node.packageName?.toString() ?: "",
        // NUNCA se guardan coordenadas: los workflows se reproducen solo por árbol de UI.
    )

    private fun labelOf(node: AccessibilityNodeInfo): String = sequenceOf(
        node.hintText,
        node.contentDescription,
        if (node.isEditable) null else node.text,
        node.viewIdResourceName?.substringAfterLast('/'),
    ).firstOrNull { !it.isNullOrBlank() }?.toString()?.take(80) ?: ""

    private fun stepFor(action: ActionType, node: AccessibilityNodeInfo?, value: String = "") = Step(
        order = 0,
        action = action,
        selector = node?.let { selectorOf(it) } ?: Selector(),
        value = value,
        label = node?.let { labelOf(it) } ?: "",
        screen = currentScreen(),
        peers = if (action == ActionType.CLICK && node != null) collectPeers(node) else emptyList(),
    )

    /**
     * Elementos "paralelos" del nodo clickeado (como collectAlternativeTargets de Graph): sube por
     * los ancestros y, cuando un contenedor tiene ≥2 hijos clickeables del MISMO tipo (className),
     * devuelve sus etiquetas. Así "5" trae ["0".."9","+","−","×","="] y "pepperoni" trae los otros sabores.
     */
    private fun collectPeers(node: AccessibilityNodeInfo): List<String> {
        val cls = node.className?.toString() ?: return emptyList()
        val mine = labelOf(node)
        var ancestor = node.parent
        var depth = 0
        while (ancestor != null && depth < 5) {
            val siblings = LinkedHashSet<String>()
            fun scan(n: AccessibilityNodeInfo?) {
                n ?: return
                if (n.className?.toString() == cls && (n.isClickable || n.parent?.isClickable == true)) {
                    val l = labelOf(n)
                    if (l.isNotBlank() && l != mine) siblings += l
                }
                for (i in 0 until n.childCount) scan(n.getChild(i))
            }
            scan(ancestor)
            if (siblings.size >= 2) return siblings.take(24).toList()
            ancestor = ancestor.parent
            depth++
        }
        return emptyList()
    }

    /* ---------- Búsqueda en el árbol ---------- */

    /** Localiza el nodo SOLO por localizadores semánticos del árbol de UI (nunca por coordenadas). */
    private fun find(sel: Selector): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (sel.viewId.isNotBlank())
            root.findAccessibilityNodeInfosByViewId(sel.viewId)?.firstOrNull()?.let { return it }
        if (sel.text.isNotBlank())
            root.findAccessibilityNodeInfosByText(sel.text)?.firstOrNull()?.let { return it }
        if (sel.contentDesc.isBlank()) return null
        var byDesc: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (byDesc == null && n.contentDescription?.toString() == sel.contentDesc) byDesc = n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return byDesc
    }

    /** Localiza un paralelo por su etiqueta (texto o contentDesc), para ejecutar una variante. */
    private fun findByLabel(label: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString() == label }?.let { return it }
        var byDesc: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (byDesc == null && (n.contentDescription?.toString() == label ||
                    n.viewIdResourceName?.substringAfterLast('/') == label)) byDesc = n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return byDesc ?: root.findAccessibilityNodeInfosByText(label)?.firstOrNull()
    }

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

    /* ---------- Ejecución ---------- */

    override suspend fun tapAt(x: Int, y: Int): Step? {
        bubble?.flyTo(x, y)
        val node = nodeAt(x, y)
        val clickable = clickableAncestor(node)
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || tapGesture(x, y)
        val resolved = clickable ?: node
        LogBus.log("ui", "tap($x,$y) → " +
            (resolved?.let { "${it.viewIdResourceName ?: it.className}" } ?: "sin nodo, gesto directo") + " · ok=$ok")
        if (!ok) return null
        // El step se graba SIN coordenadas: si no hubo nodo semántico, queda rojo (lo hará Gemini en vivo).
        return stepFor(ActionType.CLICK, resolved)
    }

    override suspend fun typeAt(x: Int, y: Int, text: String): Step? {
        val node = editableSelf(nodeAt(x, y)) ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            LogBus.log("ui", "type($x,$y): sin campo editable ni foco")
            return null
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        bubble?.flyTo(r.centerX(), r.centerY())
        val ok = setText(node, text)
        LogBus.log("ui", "type(${node.viewIdResourceName ?: node.className}) = \"${text.take(30)}\" · ok=$ok")
        return if (ok) stepFor(ActionType.INPUT, node, text) else null
    }

    override suspend fun launch(query: String): Step? {
        val q = query.trim()
        if (q.startsWith("http")) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(q)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(1500)
            return Step(0, ActionType.LAUNCH, Selector(pkg = q), label = q, screen = currentScreen())
        }
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { it.packageName.equals(q, true) }
            ?: apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(q, true) }
            ?: return null
        val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return null
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        LogBus.log("ui", "launch: ${match.packageName}")
        delay(1500)
        return Step(0, ActionType.LAUNCH, Selector(pkg = match.packageName),
            label = pm.getApplicationLabel(match).toString(), screen = currentScreen())
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

    override fun pressKey(key: String): Boolean = when {
        key.contains("back", true) -> performGlobalAction(GLOBAL_ACTION_BACK)
        key.contains("home", true) -> performGlobalAction(GLOBAL_ACTION_HOME)
        key.contains("enter", true) || key.contains("return", true) ->
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
        else -> false
    }

    override suspend fun perform(step: Step, value: String): Boolean = when (step.action) {
        ActionType.LAUNCH -> launch(step.selector.pkg.ifBlank { step.label }) != null
        ActionType.CLICK -> {
            // value = variante pedida (pick): se localiza ese paralelo por su etiqueta en el árbol vivo.
            // Sin value, se usa el selector aprendido. Nunca por coordenadas guardadas.
            val found = if (value.isNotBlank() && value != step.label) findByLabel(value) else find(step.selector)
            val target = clickableAncestor(found) ?: found
            if (target == null) false
            else {
                val r = Rect().also { target.getBoundsInScreen(it) }
                bubble?.flyTo(r.centerX(), r.centerY())
                // clic al nodo; si no es clickable, gesto sobre su posición VIVA (no una coordenada guardada)
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapGesture(r.centerX(), r.centerY())
            }
        }
        ActionType.INPUT -> {
            val node = editableSelf(find(step.selector))
            if (node != null) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                bubble?.flyTo(r.centerX(), r.centerY())
                setText(node, value)
            } else false
        }
        ActionType.SCROLL -> scroll(value != "up")
        ActionType.KEY -> pressKey(value)
        ActionType.WAIT -> { delay(value.toLongOrNull() ?: 500); true }
    }

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
