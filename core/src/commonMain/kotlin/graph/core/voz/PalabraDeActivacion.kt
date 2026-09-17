package graph.core.voz

/**
 * PALABRA DE ACTIVACIÓN (spec 007): decide, a partir de lo que transcribió el reconocedor de voz, si
 * la persona llamó a Ü por su nombre para que empiece a escuchar. Pura: no toca el micrófono, no
 * decide nada de Android — solo compara texto. Quien la llama nunca debe loguear el [texto] de
 * entrada completo (spec 007): esta función tampoco lo hace, solo devuelve sí/no.
 */
object PalabraDeActivacion {

    /** Con qué se puede anteponer al nombre. `hey` se suma como variante razonable: en el habla
     *  casual se pronuncia igual que `ey` y el reconocedor a veces la transcribe así. */
    private val PREFIJOS = setOf("hola", "ey", "hey", "oye")

    /** El nombre, normalizado: el reconocedor del sistema casi nunca transcribe el carácter «ü» tal
     *  cual, así que tras quitar tildes y diéresis el nombre queda en una sola letra. */
    private const val NOMBRE = "u"

    /** ¿[texto] llama a Ü por su nombre para activar la escucha? Compara por PALABRA COMPLETA, nunca
     *  por substring: así «hola tú» (segunda palabra «tu», no «u») y «cuchara» (una sola palabra, no
     *  «u») quedan afuera aunque contengan la letra en algún lado. */
    fun activa(texto: String): Boolean {
        TODO("promesa 701/702: reconocer la palabra de activación")
    }
}
