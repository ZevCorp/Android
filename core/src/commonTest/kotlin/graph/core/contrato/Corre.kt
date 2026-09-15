package graph.core.contrato

/**
 * Corre un bloque `suspend` hasta el final desde una prueba de commonTest. En común no existe
 * `runBlocking` y traer `kotlinx-coroutines-test` solo para esto sería una dependencia nueva por
 * una línea: cada target lo resuelve con lo que ya tiene (jvm: `runBlocking`).
 */
expect fun corre(block: suspend () -> Unit)

/**
 * Corre [bloque] en un hilo de pila CHICA y devuelve lo que dio o lo que lanzó, también un `Error`. Es el único hilo de
 * pila chica del contrato: lo usan la voz (203) y lo enseñado (413). Un JSON que se pasa a texto a mil niveles revienta
 * ahí siempre, y no según la pila que le toque al runner; y un `StackOverflowError` no es una `Exception`: suelto en el
 * hilo de la prueba puede llevarse el runner entero, y la promesa que lo juzga tiene que verse ROJA, no desaparecer. Por
 * eso vuelve como un AssertionError que lo dice; cualquier otra falla vuelve tal cual. Con un bloque `suspend`, se
 * anida [corre] dentro y hereda su tope: `enPilaChica { corre { … } }.getOrThrow()`.
 */
expect fun <T> enPilaChica(bloque: () -> T): Result<T>
