package com.zevcorp.graph.platform

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fecha y hora actual en texto natural (es), para darle al modelo contexto temporal: "ahora" importa
 * para decidir (no subir el volumen de madrugada, saber si es fin de semana, cuánto falta para una
 * alarma…). Sin estado ni inyección: se lee en el momento de construir cada prompt/turno.
 */
object TimeContext {
    private val fmt = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "ES"))
    fun now(): String = LocalDateTime.now().format(fmt)
}
