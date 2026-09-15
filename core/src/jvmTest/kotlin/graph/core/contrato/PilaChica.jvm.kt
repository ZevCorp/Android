package graph.core.contrato

actual fun <T> enPilaChica(bloque: () -> T): Result<T> {
    var resultado: Result<T> = Result.failure(IllegalStateException("el hilo de pila chica no llegó a correr"))
    val hilo = Thread(null, { resultado = runCatching(bloque) }, "pila-chica", 256L * 1024)
    hilo.start()
    hilo.join()
    return resultado
}
