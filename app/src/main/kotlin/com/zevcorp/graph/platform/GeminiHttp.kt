package com.zevcorp.graph.platform

/**
 * Resiliencia ante la SOBRECARGA de Google. La API de Gemini devuelve 429/5xx cuando el modelo está
 * "experiencing high demand" — errores explícitamente TEMPORALES ("please try again later"). Sin
 * reintentos, cada bache de demanda hace que "todo falle"; con backoff, la mayoría se recupera sola.
 */
object GeminiHttp {

    /** ¿El código es un error transitorio del servidor (sobrecarga/caída momentánea)? Reintentable. */
    fun transient(code: Int) = code == 429 || code == 500 || code == 502 || code == 503 || code == 504

    /**
     * Ejecuta una petición HTTP y, si vuelve con un error transitorio, la reintenta con backoff
     * exponencial (0.8s, 1.6s, 3.2s… hasta 8s). `attempt` devuelve (código, cuerpo). Es SÍNCRONA
     * (se llama dentro de Dispatchers.IO): usa Thread.sleep para la espera. Devuelve el último
     * resultado (exitoso o el último error tras agotar los intentos).
     *
     * Seguro para la Interactions API: un 5xx significa que el servidor NO creó la interacción (no
     * devuelve `id`), así que reintentar el mismo POST no duplica acciones.
     */
    inline fun withRetry(tag: String, tries: Int = 4, attempt: () -> Pair<Int, String>): Pair<Int, String> {
        var result = attempt()
        var wait = 800L
        var n = 1
        while (transient(result.first) && n < tries) {
            LogBus.log(tag, "HTTP ${result.first} transitorio (sobrecarga de Google) · reintento $n/${tries - 1} en ${wait}ms")
            try { Thread.sleep(wait) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return result }
            wait = (wait * 2).coerceAtMost(8000L)
            result = attempt()
            n++
        }
        return result
    }
}
