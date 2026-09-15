package graph.core.precision

/**
 * UNA SECCIÓN CRÍTICA SIN DEPENDENCIAS (spec 003, promesa 319). Kotlin 2.0.20 no trae atómicos ni candados en
 * common, y el núcleo no suma una librería por una clase: cada plataforma pone el suyo (jvm, `synchronized`).
 *
 * Es reentrante: quien ya lo tiene puede volver a entrar. El [Freno] avisa con el candado cerrado, y un aviso
 * que vuelva a mirar el freno desde el mismo hilo no se queda esperándose a sí mismo.
 */
expect class Candado() {
    /** Corre [bloque] con el candado cerrado: ningún otro hilo entra hasta que acabe. */
    fun <T> con(bloque: () -> T): T
}
