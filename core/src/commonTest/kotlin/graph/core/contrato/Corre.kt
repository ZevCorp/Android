package graph.core.contrato

/**
 * Corre un bloque `suspend` hasta el final desde una prueba de commonTest. En común no existe
 * `runBlocking` y traer `kotlinx-coroutines-test` solo para esto sería una dependencia nueva por
 * una línea: cada target lo resuelve con lo que ya tiene (jvm: `runBlocking`).
 */
expect fun corre(block: suspend () -> Unit)
