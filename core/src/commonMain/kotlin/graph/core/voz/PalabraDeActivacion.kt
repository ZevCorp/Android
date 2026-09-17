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
        val palabras = normaliza(texto).split(' ').filter { it.isNotEmpty() }
        return when (palabras.size) {
            1 -> palabras[0] == NOMBRE
            2 -> palabras[0] in PREFIJOS && palabras[1] == NOMBRE
            else -> false
        }
    }

    /** minúsculas; tildes y diéresis fuera; todo lo que no sea letra o dígito pasa a espacio; espacios
     *  repetidos colapsados y bordes recortados. Nunca deja el [texto] de entrada en un log: quien
     *  llama a [activa] solo se entera del resultado, sí o no. */
    private fun normaliza(texto: String): String {
        val sb = StringBuilder(texto.length)
        for (c in texto.lowercase()) {
            val plana = SIN_TILDE[c]
            when {
                plana != null -> sb.append(plana)
                c.isLetterOrDigit() -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        var colapsado = false
        return buildString {
            for (c in sb) {
                if (c == ' ') {
                    if (!colapsado && isNotEmpty()) append(' ')
                    colapsado = true
                } else {
                    append(c)
                    colapsado = false
                }
            }
        }.trim()
    }

    private val SIN_TILDE = mapOf(
        'á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u',
        'à' to 'a', 'è' to 'e', 'ì' to 'i', 'ò' to 'o', 'ù' to 'u',
        'â' to 'a', 'ê' to 'e', 'î' to 'i', 'ô' to 'o', 'û' to 'u',
    )
}
