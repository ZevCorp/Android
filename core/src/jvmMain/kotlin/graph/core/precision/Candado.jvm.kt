package graph.core.precision

/** En jvm, el monitor de un objeto propio: reentrante y sin librerías. */
actual class Candado actual constructor() {
    private val cerrojo = Any()

    actual fun <T> con(bloque: () -> T): T = synchronized(cerrojo) { bloque() }
}
