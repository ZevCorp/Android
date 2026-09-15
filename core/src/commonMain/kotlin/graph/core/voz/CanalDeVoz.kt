package graph.core.voz

/**
 * EL SOCKET DE LA VOZ, SIN SOCKET (docs/specs/002, fase A2). La conversación habla con esto y la fase B
 * lo cablea con OkHttp; el contrato, con un guion. Lo que aquí se decide es QUÉ tiene que poder distinguir
 * la conversación, no cómo se abre un WebSocket.
 *
 * TODO LO QUE PASA SE DEVUELVE COMO DATO, NO COMO EXCEPCIÓN: un apretón de manos rechazado tiene que llegar
 * con su HTTP. En U el 401 llegaba como estado 0 sin `CollectHttpResponseDetails`, y un 0 se reintenta como
 * si fuera la red (medido el 2026-09-13 con una clave falsa).
 */
interface CanalDeVoz {
    /** Abre el socket con estas cabeceras. No manda nada: la apertura de la sesión la escribe la conversación. */
    suspend fun abrir(url: String, cabeceras: Map<String, String>): Apertura

    /** Un mensaje de texto. Si el socket murió, lanza: quien manda decide si eso importa. */
    suspend fun enviar(texto: String)

    /** El siguiente mensaje entero, o el cierre. Lanzar es que la escucha se cortó sin cierre. */
    suspend fun recibir(): Recibido

    /** Cierra normal, con [motivo] («fin»). No espera, y cerrar dos veces no hace nada. */
    fun cerrar(motivo: String)
}

/** Cómo salió abrir el socket. */
sealed interface Apertura {
    data object Ok : Apertura

    /**
     * El servidor contestó el apretón de manos y dijo que no. [httpStatus] es el HTTP tal cual (401 con una
     * clave falsa) y [codigoCabecera] el `x-openai-ide-error-code` si vino (invalid_api_key).
     */
    data class Rechazo(val httpStatus: Int, val codigoCabecera: String? = null) : Apertura

    /** No hubo respuesta HTTP: sin red, DNS, tiempo agotado. Es lo único que se arregla solo. */
    data class SinRed(val motivo: String) : Apertura
}

/** Lo que llega por el socket. */
sealed interface Recibido {
    data class Mensaje(val texto: String) : Recibido

    /**
     * Se acabó. [porRed] es que el socket murió sin trama de cierre (GPT-Live aborta así ~2 s después de un
     * error); si no, [codigo] y [motivo] son los de la trama, y el motivo puede traer «type.code».
     */
    data class Cierre(val codigo: Int, val motivo: String, val porRed: Boolean) : Recibido
}

/** El tiempo de la conversación, para juzgarla sin esperar: la hora en ms y las esperas entre intentos. */
interface Reloj {
    fun ahora(): Long
    suspend fun esperar(ms: Long)
}
