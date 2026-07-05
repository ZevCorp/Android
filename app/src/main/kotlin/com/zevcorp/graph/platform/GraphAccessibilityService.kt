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

    /* Clics del usuario durante la enseñanza: SEÑALES para que el cerebro generalice. */
    @Volatile private var capturing = false
    private val clickBuffer = ArrayDeque<String>()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!capturing || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        if (event.packageName == packageName) return
        val label = event.source?.let { labelOf(it) } ?: return
        if (label.isBlank()) return
        synchronized(clickBuffer) { clickBuffer.addLast(label); if (clickBuffer.size > 40) clickBuffer.removeFirst() }
        LogBus.log("learn", "señal clic: \"$label\"")
    }

    override fun setCapturing(enabled: Boolean) {
        capturing = enabled
        if (!enabled) synchronized(clickBuffer) { clickBuffer.clear() }
    }

    override fun drainClicks(): List<String> = synchronized(clickBuffer) {
        val out = clickBuffer.toList()
        clickBuffer.clear()
        out
    }

    override fun onInterrupt() {}

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

    /* ---------- LearningSurface: leer, iluminar y tocar elementos por etiqueta ---------- */

    override suspend fun screen(): String = currentScreen()

    /** Etiquetas de los elementos tocables de la pantalla actual (lo que el cerebro puede secuenciar). */
    override suspend fun elements(): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (n.isClickable || n.isEditable) labelOf(n).takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out.take(48).toList()
    }

    /** Ilumina en secuencia (tin·tin·tin) los elementos por su etiqueta. */
    override suspend fun highlight(labels: List<String>) {
        for (label in labels) {
            val node = findByLabel(label) ?: continue
            val r = Rect().also { node.getBoundsInScreen(it) }
            withContext(Dispatchers.Main) { highlighter.show(r) }
            delay(700)
        }
        withContext(Dispatchers.Main) { highlighter.hide() }
    }

    override suspend fun tapLabel(label: String): Boolean {
        val node = findByLabel(label) ?: return false.also { LogBus.log("learn", "no encontré \"$label\"") }
        val target = clickableAncestor(node) ?: node
        val r = Rect().also { target.getBoundsInScreen(it) }
        bubble?.flyTo(r.centerX(), r.centerY())
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapGesture(r.centerX(), r.centerY())
    }

    private fun findByLabel(label: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
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
