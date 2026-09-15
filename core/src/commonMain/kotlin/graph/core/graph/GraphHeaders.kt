package graph.core.graph

/**
 * Las cabeceras de cada request a Graph. Espejo de `BackendClient.cs`: la key, y la atribución del
 * consumo de IA del puente consciente (sin `X-Miracle-App`/`X-Miracle-Feature` todo el gasto del
 * cerebro quedaría como «sin atribuir»). El email y el id de dispositivo viajan solo si existen.
 */
object GraphHeaders {
    fun build(apiKey: String, email: String?, deviceId: String?): Map<String, String> = TODO("pendiente")
}
