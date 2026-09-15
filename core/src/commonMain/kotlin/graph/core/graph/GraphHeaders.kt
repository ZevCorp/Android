package graph.core.graph

/**
 * Las cabeceras de cada request a Graph. Espejo de `BackendClient.cs`: la key, y la atribución del
 * consumo de IA del puente consciente (sin `X-Miracle-App`/`X-Miracle-Feature` todo el gasto del
 * cerebro quedaría como «sin atribuir»). El email y el id de dispositivo viajan solo si existen.
 *
 * [feature] es la del puente consciente por defecto (el turno y `/teach/…`). Las rutas de aprendizaje
 * y workflows van sin ella, como `GraphClient.cs` de Windows: se pasa `null` (spec 004, promesa 402).
 */
object GraphHeaders {
    const val FEATURE_CEREBRO = "conscious_bridge"

    fun build(apiKey: String, email: String?, deviceId: String?, feature: String? = FEATURE_CEREBRO): Map<String, String> = buildMap {
        put("X-API-Key", apiKey)
        put("X-Miracle-App", "android_app")
        feature?.takeIf { it.isNotBlank() }?.let { put("X-Miracle-Feature", it) }
        email?.takeIf { it.isNotBlank() }?.let { put("X-Miracle-User-Email", it) }
        deviceId?.takeIf { it.isNotBlank() }?.let { put("X-Miracle-Device-Id", it) }
        put("Content-Type", "application/json")
    }
}
