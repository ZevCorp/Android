package com.zevcorp.graph.platform

import android.graphics.Rect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bus de BUGS de UI: registra los casos en que el elemento que el usuario tocó por coordenada NO
 * coincide con el que el agente resolvería por su etiqueta/ID (mismo mecanismo que tapLabel). Es puro
 * diagnóstico —no altera nada de la ejecución—; alimenta el panel "Bugs de UI" del área de desarrollador.
 */
object UiBugBus {
    /** Un desajuste detectado: la etiqueta resolvió a un elemento distinto del tocado. */
    class Bug(val time: String, val screen: String, val label: String, val touched: Rect, val resolved: Rect)

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = ArrayDeque<Bug>()
    private val _events = MutableSharedFlow<Bug>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    @Synchronized
    fun report(screen: String, label: String, touched: Rect, resolved: Rect) {
        val bug = Bug(fmt.format(Date()), screen, label, Rect(touched), Rect(resolved))
        buffer.addLast(bug)
        if (buffer.size > 200) buffer.removeFirst()
        _events.tryEmit(bug)
    }

    @Synchronized fun count(): Int = buffer.size

    @Synchronized fun clear() = buffer.clear()

    /** Texto para el panel: un bloque por bug (más reciente al final), o "" si no hay ninguno. */
    @Synchronized
    fun dump(): String = buffer.joinToString("\n") { b ->
        "${b.time}  \"${b.label}\"\n" +
            "   tocaste ${rect(b.touched)} → el agente iría a ${rect(b.resolved)}\n" +
            "   pantalla: ${b.screen}"
    }

    private fun rect(r: Rect) = "(${r.centerX()},${r.centerY()}) ${r.width()}×${r.height()}"
}
