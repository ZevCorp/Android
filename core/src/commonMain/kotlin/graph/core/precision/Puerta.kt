package graph.core.precision

import graph.core.domain.Gestures
import graph.core.domain.GraphLog
import graph.core.domain.Phone
import graph.core.domain.SystemApi
import graph.core.domain.UiPlayer

/** La puerta única al teléfono. Esqueleto: las firmas existen para que el contrato 003 compile y nazca rojo. */
class Puerta(
    private val freno: Freno,
    private val phone: Phone,
    private val gestures: Gestures,
    private val system: SystemApi,
    private val player: UiPlayer? = null,
    private val log: GraphLog = GraphLog { _, _ -> },
) {
    val telefono: Phone get() = TODO("fase 3A")
    val gestos: Gestures get() = TODO("fase 3A")
    val sistema: SystemApi get() = TODO("fase 3A")
    val reproductor: UiPlayer get() = TODO("fase 3A")
}
