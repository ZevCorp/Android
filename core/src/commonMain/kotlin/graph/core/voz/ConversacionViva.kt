package graph.core.voz

/**
 * LA CONVERSACIÓN VIVA (docs/specs/002, fase A2). Esqueleto: las promesas 218-229 se escriben antes que su código.
 */
class ConversacionViva(
    private val canal: CanalDeVoz,
    private val protocolo: ProtocoloGptLive = ProtocoloGptLive(),
    private val credencial: () -> String?,
    private val instruccionesVoz: String,
    instruccionesDelegado: String,
    utensilios: List<Utensilio>,
    private val ejecutar: suspend (Llamada) -> String,
    private val reproducir: (ByteArray) -> Unit,
    private val callar: () -> Unit,
    private val sonando: () -> Boolean,
    private val dice: (String) -> Unit,
    private val log: (tag: String, mensaje: String) -> Unit,
    private val reloj: Reloj,
    private val compuertaActiva: Boolean = ModoDeCaptura.activa(forzada = false, aec = false),
    private val transcribe: (texto: String, esDeU: Boolean) -> Unit = { _, _ -> },
    private val alAbrirPeticion: (por: String) -> Unit = {},
) {
    val viva: Boolean get() = TODO("fase A2")
    val peticiones: Int get() = TODO("fase A2")
    val itemsEnSesion: Int get() = TODO("fase A2")

    suspend fun conversar(): Unit = TODO("fase A2")
    fun detener(): Unit = TODO("fase A2")
    suspend fun oirMicrofono(pcm: ByteArray): Unit = TODO("fase A2")
    suspend fun escribir(texto: String): Unit = TODO("fase A2")
    suspend fun avisar(texto: String): Unit = TODO("fase A2")
    fun retirar(ids: List<String>): Unit = TODO("fase A2")
    suspend fun cambiarModo(instrucciones: String, utensilios: List<Utensilio>, vuelve: Boolean): Unit = TODO("fase A2")
}
