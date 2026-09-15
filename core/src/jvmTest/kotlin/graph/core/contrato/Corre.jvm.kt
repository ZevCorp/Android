package graph.core.contrato

import kotlinx.coroutines.runBlocking

actual fun corre(block: suspend () -> Unit) = runBlocking { block() }

/** 512 KB: sobra para lo que el contrato hace de verdad, y 2000 niveles pasados a texto la agotan con seguridad. */
private const val PILA_CHICA_BYTES = 512L * 1024

actual fun correConPilaChica(block: suspend () -> Unit) {
    var fallo: Throwable? = null
    val hilo = Thread(null, { try { runBlocking { block() } } catch (e: Throwable) { fallo = e } }, "pila-chica", PILA_CHICA_BYTES)
    hilo.start()
    hilo.join()
    when (val e = fallo) {
        null -> Unit
        is StackOverflowError -> throw AssertionError(
            "reventó con StackOverflowError en una pila de ${PILA_CHICA_BYTES / 1024} KB: " + e.stackTrace.take(4).joinToString(" ← "),
        )
        else -> throw e
    }
}
