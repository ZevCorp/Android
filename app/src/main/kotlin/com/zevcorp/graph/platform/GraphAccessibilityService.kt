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
import graph.core.domain.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Superficie de UI de Android: el análogo del recorder DOM de la extensión de Chrome de Graph.
 * Captura clics/texto del usuario desde el árbol de accesibilidad y ejecuta steps sobre él.
 */
class GraphAccessibilityService : AccessibilityService(), UiSurface {

    private val _userActions = MutableSharedFlow<Step>(extraBufferCapacity = 128)
    override val userActions = _userActions.asSharedFlow()

    @Volatile private var capturing = false
    private val pendingInputs = LinkedHashMap<String, Step>()

    override fun onServiceConnected() {
        GraphApp.instance.ui = this
    }

    override fun onDestroy() {
        if (GraphApp.instance.ui === this) GraphApp.instance.ui = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /* ---------- Captura de acciones del usuario (Teaching feedback / demos en Learning) ---------- */

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!capturing || event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                flushInputs()
                event.source?.let { _userActions.tryEmit(stepFor(ActionType.CLICK, it)) }
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

    private fun selectorOf(node: AccessibilityNodeInfo): Selector {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return Selector(
            viewId = node.viewIdResourceName ?: "",
            // en campos editables el texto es el valor tecleado: no sirve como localizador
            text = if (node.isEditable) "" else node.text?.toString()?.take(60) ?: "",
            contentDesc = node.contentDescription?.toString() ?: "",
            className = node.className?.toString() ?: "",
            pkg = node.packageName?.toString() ?: "",
            bounds = rect.toShortString(),
        )
    }

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
    )

    /* ---------- Búsqueda en el árbol ---------- */

    private fun find(sel: Selector): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (sel.viewId.isNotBlank())
            root.findAccessibilityNodeInfosByViewId(sel.viewId)?.firstOrNull()?.let { return it }
        if (sel.text.isNotBlank())
            root.findAccessibilityNodeInfosByText(sel.text)?.firstOrNull()?.let { return it }
        var byDesc: AccessibilityNodeInfo? = null
        var byClass: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?) {
            n ?: return
            if (byDesc == null && sel.contentDesc.isNotBlank() &&
                n.contentDescription?.toString() == sel.contentDesc) byDesc = n
            if (byClass == null && sel.className.isNotBlank() && n.className?.toString() == sel.className &&
                Rect().also { n.getBoundsInScreen(it) }.toShortString() == sel.bounds) byClass = n
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return byDesc ?: byClass
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
        val node = nodeAt(x, y)
        val clickable = clickableAncestor(node)
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: tapGesture(x, y)
        if (!ok) return null
        val step = stepFor(ActionType.CLICK, clickable ?: node)
        return if (step.selector.isEmpty())
            step.copy(selector = step.selector.copy(bounds = "[$x,$y][$x,$y]")) else step
    }

    override suspend fun typeAt(x: Int, y: Int, text: String): Step? {
        val node = editableSelf(nodeAt(x, y)) ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (setText(node, text)) stepFor(ActionType.INPUT, node, text) else null
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
            val node = find(step.selector)
            val clickable = clickableAncestor(node)
            when {
                clickable != null -> clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node != null -> {
                    val r = Rect().also { node.getBoundsInScreen(it) }
                    tapGesture(r.centerX(), r.centerY())
                }
                else -> boundsCenter(step.selector.bounds)?.let { (x, y) -> tapGesture(x, y) } ?: false
            }
        }
        ActionType.INPUT -> editableSelf(find(step.selector))?.let { setText(it, value) } ?: false
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

    private fun boundsCenter(bounds: String): Pair<Int, Int>? {
        val n = Regex("-?\\d+").findAll(bounds).map { it.value.toInt() }.toList()
        return if (n.size >= 4) (n[0] + n[2]) / 2 to (n[1] + n[3]) / 2 else null
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
