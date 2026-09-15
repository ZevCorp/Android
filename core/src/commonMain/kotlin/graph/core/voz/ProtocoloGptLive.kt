package graph.core.voz

/** HABLAR CON GPT-LIVE, sin socket (docs/specs/002). Esqueleto: el contrato 002 nace rojo. */
class ProtocoloGptLive(val modelo: String = MODELO, val delegado: String = DELEGADO) {

    companion object {
        const val URL = "wss://api.openai.com/v1/live/sessions"
        const val MODELO = "gpt-live-1"
        const val DELEGADO = "gpt-5.6-luna"
        const val VOZ = "marin"
        const val RITMO = 24_000
    }

    val url: String get() = TODO()
    val ritmo: Int get() = TODO()
    val mira: Boolean get() = TODO()
    val marcaLosTurnos: Boolean get() = TODO()
    val confirmaQueAbrio: Boolean get() = TODO()

    fun cabeceras(clave: String): Map<String, String> = TODO()
    fun apertura(instruccionesVoz: String, instruccionesDelegado: String, utensilios: List<Utensilio>): String = TODO()
    fun audio(pcm: ByteArray): String = TODO()
    fun texto(texto: String): List<String> = TODO()
    fun resultados(hechas: List<Resultado>): List<String> = TODO()
    fun pedirRespuesta(): String = TODO()
    fun dictar(texto: String): String = TODO()
    fun cambiarDeModo(instrucciones: String, utensilios: List<Utensilio>, vuelve: Boolean, instruccionesVoz: String): List<String> = TODO()
    fun leer(json: String): List<Hecho> = TODO()
}
