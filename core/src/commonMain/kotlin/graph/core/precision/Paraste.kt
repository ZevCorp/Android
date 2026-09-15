package graph.core.precision

import kotlinx.coroutines.CancellationException

/**
 * LO PARASTE TÚ. La lanza la [Puerta] cuando una entrada llega con el freno echado, y el motor la
 * recoge para terminar la corrida como una cancelación: sin ejecutar el resto y sin pedir otro turno.
 *
 * Es una [CancellationException] a propósito (promesa 14 del sprint 1: una cancelación no es un fallo
 * de red). Quien reintenta, registra errores o convierte excepciones en «falló» ya deja pasar las
 * cancelaciones tal cual; una parada tiene que recorrer ese mismo camino, no el de los fallos.
 */
class Paraste(val motivo: String) : CancellationException(motivo)

/** El motivo de un alto pedido por la persona: lo dicen la puerta y el motor, y tienen que decir lo mismo. */
internal const val PARASTE_TU = "paraste tú"
