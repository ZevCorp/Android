package graph.core.contrato

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
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

/**
 * 512 KB: sobra para lo que el contrato hace de verdad, y el `toString` de un JSON desborda desde ~1000 niveles con 1 MB
 * de pila, así que con la mitad los 1000 de la 203 y los 2000 de la 413 la agotan con seguridad.
 */
private const val PILA_CHICA_BYTES = 512L * 1024

/** `CountDownLatch.await` bloquea el hilo de verdad: ninguna corrutina de ese hilo avanza mientras dure. */
actual class Traba actual constructor() {
    private val cerrojo = CountDownLatch(1)

    actual fun esperaBloqueando(topeMs: Long): Boolean = cerrojo.await(topeMs, TimeUnit.MILLISECONDS)

    actual fun abrir() = cerrojo.countDown()
}

actual fun despachadorDeIo(): CoroutineContext = Dispatchers.IO

actual fun <T> enPilaChica(bloque: () -> T): Result<T> {
    var resultado: Result<T> = Result.failure(IllegalStateException("el hilo de pila chica no llegó a correr"))
    val hilo = Thread(null, { resultado = runCatching(bloque) }, "pila-chica", PILA_CHICA_BYTES)
    hilo.start()
    hilo.join()
    return when (val e = resultado.exceptionOrNull()) {
        is StackOverflowError -> Result.failure(
            AssertionError(
                "reventó con StackOverflowError en una pila de ${PILA_CHICA_BYTES / 1024} KB: " + e.stackTrace.take(4).joinToString(" ← "),
                e,
            ),
        )
        else -> resultado
    }
}
