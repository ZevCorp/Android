package graph.core.telemetria

/** Una línea del LogBus tal como se encoló: local y entera. Lo que de ella sale del teléfono lo decide [PuertaDeTelemetria]. */
class LineaDeLog(val pedido: String?, val tag: String, val mensaje: String)

/** DEL TELÉFONO A LA TELEMETRÍA REMOTA SOLO SALEN MEDIDAS (docs/specs/005). Esqueleto: las promesas 501-507 nacen rojas. */
object PuertaDeTelemetria {
    const val OTRO = "otro"
    const val TOPE_DE_TAG = 60
    const val TOPE_DE_MENSAJE = 4000

    fun tag(tag: String): String = TODO("501")
    fun mensaje(mensaje: String): String = TODO("501")
    fun largo(texto: String): String = TODO("504")
    fun filasDeLog(dispositivo: String, lote: List<LineaDeLog>): String = TODO("505")
    fun cuerpoDePedido(id: String, dispositivo: String, usuario: String, pedido: String, via: String, estado: String, resumen: String? = null): String = TODO("504")
    fun cuerpoDeUsuario(dispositivo: String, nombre: String, modelo: String, version: String): String = TODO("504")
}
