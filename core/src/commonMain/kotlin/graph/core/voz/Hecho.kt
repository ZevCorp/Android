package graph.core.voz

/**
 * UN HECHO DE LA CONVERSACIÓN, dicho sin acento de nadie. Es la frontera: al otro lado, la
 * conversación enciende el micrófono, ejecuta herramientas y reproduce audio sin saber con qué
 * servidor habla. Espejo de `U-Windows-App/voz/Realtime/Hecho.cs` (docs/specs/002).
 *
 * UN MENSAJE PUEDE TRAER VARIOS HECHOS, y por eso se leen en lista: devolver solo el primero
 * perdería los otros en silencio.
 *
 * `PaseParaVolver` y `Consumo` no se declaran: GPT-Live nunca los produce (no da pase para volver
 * y no cuenta fichas, cuenta segundos). Se añaden el día que haya un protocolo que los mande.
 */
sealed interface Hecho {
    /** Un trozo de voz de Ü, PCM16LE mono a 24 kHz. Nunca vacío ni hecho de ceros. */
    class Suena(val pcm: ByteArray) : Hecho

    /** Un trozo de lo que dijo quien habla. Llega por pedazos, no por frases. */
    data class DiceElUsuario(val trozo: String) : Hecho

    /** Un trozo de lo que dice Ü. */
    data class DiceU(val trozo: String) : Hecho

    /** Se acabó el turno. GPT-Live no lo manda: lo marca [TurnosSinMarca]. */
    data object CierraElTurno : Hecho

    /** Hablaron encima. GPT-Live tampoco lo manda: lo detecta [DetectorDeInterrupcion]. */
    data object HablaronEncima : Hecho

    /** El delegado pide ejecutar herramientas. */
    data class Pide(val llamadas: List<Llamada>) : Hecho

    /** El modelo retira lo que había pedido: contestarlo es lo que lo hacía repetirlo en bucle. */
    data class Retira(val ids: List<String>) : Hecho

    /** Lo que va durando la sesión, en segundos: el ACUMULADO, no un incremento. */
    data class Duracion(val segundos: Double) : Hecho

    /**
     * El servidor dice que algo va mal. [codigo] es el `code` tal cual (credit_balance_exhausted,
     * invalid_model…), vacío si no trae: es lo que se mira para no reconectar lo que no se arregla
     * reconectando, y no el mensaje, que está en inglés y cambia de redacción.
     */
    data class Falla(val que: String, val codigo: String = "") : Hecho

    /** El servidor CONFIRMA que la sesión abrió (`session.started`). Lo que llega antes es que no abrió. */
    data object Abierta : Hecho
}

/** Algo que el delegado pide ejecutar. Los argumentos son siempre texto. */
data class Llamada(val id: String, val nombre: String, val args: Map<String, String>)

/** Una herramienta, descrita sin forma de nadie: cada protocolo la viste a su manera. */
data class Utensilio(val nombre: String, val descripcion: String, val args: List<Argumento>)

/** Un argumento de una herramienta: todos son texto. */
data class Argumento(val nombre: String, val que: String)

/** Lo que salió de ejecutar una llamada, para devolvérselo al delegado. */
data class Resultado(val id: String, val texto: String)
