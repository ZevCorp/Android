package graph.core.precision

/** El tope de dos intentos por petición. Esqueleto: las firmas existen para que el contrato 003 compile y nazca rojo. */
class TopeDeIntentos(private val celdaPx: Int) {

    sealed class Destino {
        abstract val legible: String

        class Nodo(val selector: String, override val legible: String = selector) : Destino()
        class Celda(val columna: Int, val fila: Int, override val legible: String = "celda:$columna,$fila") : Destino()
        class Campo(val campo: String, override val legible: String = campo) : Destino()
        class Nombre(val nombre: String, val which: String? = null) : Destino() {
            override val legible: String get() = if (which.isNullOrBlank()) nombre else "$nombre (which=$which)"
        }
    }

    sealed class Salida {
        class Revento(val queSalio: String) : Salida()
        class Lista(val texto: String, val candidatos: List<String> = emptyList()) : Salida()
        class Intento(val dio: Boolean, val escribio: Boolean, val cambio: Boolean?, val queSalio: String) : Salida()
    }

    fun alTocar(x: Int, y: Int, nodo: NodoVivo?): Destino = TODO("fase 3C")
    fun alEscribirEn(x: Int, y: Int): Destino.Campo = TODO("fase 3C")
    fun alEscribir(campo: String): Destino.Campo = TODO("fase 3C")
    fun clave(destino: Destino): String = TODO("fase 3C")
    fun rechazo(destino: Destino): String? = TODO("fase 3C")
    fun despues(destino: Destino, salida: Salida): Unit = TODO("fase 3C")
    fun nuevaPeticion(): Unit = TODO("fase 3C")

    companion object {
        const val MAXIMO = 2
        const val CELDA_DP = 48
        fun celdaPx(densidad: Float): Int = TODO("fase 3C")
        fun logro(dio: Boolean, escribio: Boolean, cambio: Boolean?): Boolean? = TODO("fase 3C")
    }
}
