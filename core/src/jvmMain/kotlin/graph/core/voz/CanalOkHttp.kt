package graph.core.voz

/** El socket de la voz sobre OkHttp (docs/specs/002, fase B1a). Esqueleto: el contrato nace rojo. */
class CanalOkHttp(
    private val tiempoDeConexionMs: Long = 10_000,
    private val log: (tag: String, mensaje: String) -> Unit = { _, _ -> },
) : CanalDeVoz {
    override suspend fun abrir(url: String, cabeceras: Map<String, String>): Apertura = TODO()
    override suspend fun enviar(texto: String): Unit = TODO()
    override suspend fun recibir(): Recibido = TODO()
    override fun cerrar(motivo: String): Unit = TODO()
}
