package graph.core.precision

import graph.core.domain.GraphLog
import kotlin.time.TimeSource

/** Quién habla. Esqueleto: las firmas existen para que el contrato 003 compile y nazca rojo. */
enum class QuienHabla { PERSONA, SISTEMA }

/** La medida de una petición. Esqueleto: las firmas existen para que el contrato 003 compile y nazca rojo. */
class CuentaDePeticion(
    private val reloj: TimeSource = TimeSource.Monotonic,
    private val log: GraphLog = GraphLog { _, _ -> },
) {
    fun llamada(herramienta: String, destino: String = ""): Unit = TODO("fase 3C")
    fun resultado(herramienta: String, actuo: Boolean): Unit = TODO("fase 3C")
    fun rechazada(herramienta: String): Unit = TODO("fase 3C")
    fun retirada(herramienta: String): Unit = TODO("fase 3C")
    fun nuevaPeticion(): String? = TODO("fase 3C")
    fun cerrar(): String? = TODO("fase 3C")
}

fun abrePeticion(quien: QuienHabla, tope: TopeDeIntentos?, cuenta: CuentaDePeticion?): Boolean = TODO("fase 3C")
