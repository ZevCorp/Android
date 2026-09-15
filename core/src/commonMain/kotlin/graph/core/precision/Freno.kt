package graph.core.precision

import graph.core.domain.GraphLog
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** El freno de una tarea. Esqueleto: las firmas existen para que el contrato 003 compile y nazca rojo. */
class Freno(
    private val log: GraphLog = GraphLog { _, _ -> },
    private val avisa: (String) -> Unit = {},
    private val reloj: TimeSource = TimeSource.Monotonic,
    private val espera: suspend (Long) -> Unit = { delay(it) },
) {
    val tarea: String get() = TODO("fase 3A")
    val abierta: Boolean get() = TODO("fase 3A")
    val pedido: Boolean get() = TODO("fase 3A")

    fun empezar(tarea: String): Unit = TODO("fase 3A")
    fun pide(porque: String): Unit = TODO("fase 3A")
    fun termine(): Unit = TODO("fase 3A")
    suspend fun <T> enTarea(nombre: String, bloque: suspend () -> T): T = TODO("fase 3A")
    suspend fun duerme(ms: Long): Boolean = TODO("fase 3A")
}
