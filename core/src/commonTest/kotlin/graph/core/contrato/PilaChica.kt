package graph.core.contrato

/**
 * Corre [bloque] en un hilo de pila chica y devuelve lo que dio o lo que lanzó, también un `Error`. Un
 * `StackOverflowError` no es una `Exception`: suelto en el hilo de la prueba puede llevarse el runner entero, y
 * la promesa que lo juzga tiene que verse ROJA, no desaparecer. La pila chica, además, hace que reviente siempre
 * al mismo tamaño y no según la máquina.
 */
expect fun <T> enPilaChica(bloque: () -> T): Result<T>
