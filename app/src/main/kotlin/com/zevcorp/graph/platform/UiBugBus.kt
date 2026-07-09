package com.zevcorp.graph.platform

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bus de BUGS de UI: registra los casos en que el elemento que el usuario (o el propio agente) tocó
 * NO coincide con el que el agente resolvería por su etiqueta/ID (mismo mecanismo que tapLabel:
 * findByLabel devuelve el PRIMER nodo con esa etiqueta). Ejemplo real: en la lista de chats de
 * WhatsApp todas las filas comparten el id "contact_row_container", así que replicar el clic siempre
 * caería en el primer chat.
 *
 * Es un sistema de mejora continua: cada mismatch se diagnostica con el LLM (GeminiClickDoctor), que
 * halla el ID ÚNICO correcto y escribe un resumen de cómo endurecer la detección nativa. Todo se ve
 * en la card "Bugs de UI" del panel de desarrollador. Es puro diagnóstico: no altera la ejecución.
 */
object UiBugBus {

    /** Un desajuste detectado y (async) su diagnóstico del LLM. */
    class Bug(
        val time: String,
        val app: String,
        val screen: String,
        val label: String,
        val touched: String,   // "x,y" centro del elemento tocado
        val resolved: String,  // "x,y" centro del elemento al que el agente iría
    ) {
        @Volatile var diagnosis: String = "" // lo rellena el LLM cuando termina (ID correcto + resumen)
    }

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = ArrayDeque<Bug>()
    private val seen = HashSet<String>() // dedup: (app|screen|label) ya reportados, para no re-diagnosticar
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    /**
     * Reporta un mismatch. Devuelve el Bug NUEVO (hay que diagnosticarlo con el LLM) o null si ese
     * (app,pantalla,etiqueta) ya se había visto (evita spam de llamadas al modelo).
     */
    @Synchronized
    fun report(app: String, screen: String, label: String, touched: String, resolved: String): Bug? {
        val key = "$app|$screen|$label"
        if (!seen.add(key)) return null
        val bug = Bug(fmt.format(Date()), app, screen, label, touched, resolved)
        buffer.addLast(bug)
        if (buffer.size > 200) { buffer.removeFirst() }
        _events.tryEmit(Unit)
        return bug
    }

    /** Adjunta el diagnóstico del LLM a un bug ya reportado (y refresca el panel). */
    @Synchronized
    fun attachDiagnosis(bug: Bug, diagnosis: String) {
        bug.diagnosis = diagnosis
        _events.tryEmit(Unit)
    }

    @Synchronized fun count(): Int = buffer.size

    @Synchronized fun clear() { buffer.clear(); seen.clear(); _events.tryEmit(Unit) }

    /** Texto para el panel de desarrollador: un bloque por bug (más reciente al final), o "" si no hay. */
    @Synchronized
    fun dump(): String = if (buffer.isEmpty()) "" else buffer.joinToString("\n\n") { b ->
        buildString {
            append("${b.time}  ❌ \"${b.label}\" en ${b.app}\n")
            append("   tocado ${b.touched} → el agente iría a ${b.resolved}\n")
            append("   pantalla: ${b.screen}")
            if (b.diagnosis.isNotBlank()) append("\n   🩺 ${b.diagnosis}")
            else append("\n   🩺 diagnosticando…")
        }
    }
}
