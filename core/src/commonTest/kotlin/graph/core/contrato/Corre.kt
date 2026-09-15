package graph.core.contrato

/**
 * Corre un bloque `suspend` hasta el final desde una prueba de commonTest. En común no existe
 * `runBlocking` y traer `kotlinx-coroutines-test` solo para esto sería una dependencia nueva por
 * una línea: cada target lo resuelve con lo que ya tiene (jvm: `runBlocking`).
 */
expect fun corre(block: suspend () -> Unit)

/**
 * Como [corre], pero en un hilo con la pila CHICA: un JSON de Graph que se pasa a texto a mil niveles revienta ahí
 * siempre, y no según la pila que le toque al runner. Si el bloque revienta con StackOverflowError, la prueba falla
 * con un AssertionError que lo dice y el runner sigue; cualquier otra falla sale tal cual.
 */
expect fun correConPilaChica(block: suspend () -> Unit)
