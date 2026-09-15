package graph.core.contrato

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.fail

/**
 * Lo más que dura una prueba. Una conversación que no termina —una salida que no llama a `acabar()`, una espera que no
 * vence— dejaba el contrato corriendo para siempre: con el tope, la prueba sale roja y el juez sigue. Es un tope de
 * cuelgue, no de rendimiento: la más lenta tarda poco más de un segundo.
 */
private const val TOPE_DE_UNA_PRUEBA_MS = 30_000L

actual fun corre(block: suspend () -> Unit) = runBlocking {
    // withTimeoutOrNull y no withTimeout: un withTimeout que vence DENTRO de la prueba no se confunde con este.
    withTimeoutOrNull(TOPE_DE_UNA_PRUEBA_MS) { block() }
        ?: fail("la prueba no terminó en ${TOPE_DE_UNA_PRUEBA_MS / 1000} s: algo quedó colgado")
}

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
