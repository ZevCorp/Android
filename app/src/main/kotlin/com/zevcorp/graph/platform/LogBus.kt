package com.zevcorp.graph.platform

import android.util.Log
import graph.core.domain.GraphLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Bus de logs de toda la app: alimenta el panel de desarrollador y logcat. */
object LogBus : GraphLog {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = ArrayDeque<String>()
    private val _lines = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val lines = _lines.asSharedFlow()

    @Synchronized
    override fun log(tag: String, message: String) {
        val line = "${fmt.format(Date())} [$tag] $message"
        buffer.addLast(line)
        if (buffer.size > 600) buffer.removeFirst()
        _lines.tryEmit(line)
        Log.d("Graph", "[$tag] $message")
        // El mismo bus alimenta la telemetría: cada línea viaja (por lotes) al panel Android del
        // Provider Studio, correlacionada con el prompt en curso. Encolar nunca bloquea ni lanza.
        Telemetry.enqueue(tag, message)
    }

    @Synchronized
    fun dump(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() = buffer.clear()
}
