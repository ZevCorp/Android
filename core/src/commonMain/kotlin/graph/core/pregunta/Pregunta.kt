package graph.core.pregunta

/**
 * UNA PREGUNTA DEL CLIENTE (docs/specs/006). No es la de Graph (`ask_user`, que viaja en `inform`): esta la hace el cliente
 * antes de ejecutar, y se la dice a la persona por el canal que ya existe ([graph.core.domain.UserChannel]).
 *
 * [asunto] es DE QUÉ se preguntó, en plano y sin lo que la persona escribió: es la llave del registro de lo ya preguntado
 * en esta corrida (promesa 606) y nunca sale al log.
 */
class Pregunta(val clase: Clase, val asunto: String, val texto: String, val opciones: List<String> = emptyList()) {

    /** Por qué se pregunta. Es lo único de la pregunta que sale del teléfono (promesa 608). */
    enum class Clase(val enLog: String) { PERMISO("permiso"), CUAL("cual"), DATO("dato") }
}

/**
 * Lo ÚNICO que el cliente lee de una respuesta: en cuál de tres cae. Lo demás lo interpreta el cerebro, que para eso tiene
 * el contexto entero; un clasificador de «sí/no» en el cliente es justo lo que se rompe (spec 006, «lo que NO entra»).
 *
 * No contestar es no autorizar: un diálogo que alguien cierra sin escribir nada no es un permiso.
 *
 * DÓNDE CAE LA NEGACIÓN IMPORTA (promesa 614). Antes bastaba con que la palabra estuviera en cualquier posición, así que
 * «claro, no hay problema, mándalo» se leía como un NO, en silencio y sin que nadie se enterara. Ahora una negación decide
 * cuando **abre** la respuesta; si niega y afirma a la vez, no se asume ninguna de las dos: se vuelve a preguntar.
 */
object Respuesta {

    /** En cuál de tres cae una respuesta. [AMBIGUA] no es un veredicto: es la orden de volver a preguntar. */
    enum class Lectura { NIEGA, AMBIGUA, AUTORIZA }

    fun lee(texto: String): Lectura {
        val dichas = palabrasEnOrden(texto)
        if (dichas.isEmpty()) return Lectura.NIEGA          // no contestar es no autorizar
        if (dichas.none { it in NEGACIONES }) return Lectura.AUTORIZA
        return when {
            dichas.first() in NEGACIONES -> Lectura.NIEGA   // «no», «no, déjalo», «cancela»: la negación es la respuesta
            dichas.any { it in AFIRMACIONES } -> Lectura.AMBIGUA // dice que sí y que no: «claro, no hay problema, mándalo»
            else -> Lectura.NIEGA                           // niega, y nada en la frase la contradice
        }
    }

    /**
     * Cómo dice que no una persona, como palabra entera. «para» no está: es la palabra del alto, y el alto tiene su propio
     * freno (promesa 604); dentro de una respuesta suele ser «para las ocho».
     */
    private val NEGACIONES = setOf(
        "no", "nel", "nunca", "cancela", "cancelalo", "cancelar", "detente", "nada", "olvidalo", "olvidate", "ninguno",
        "ninguna", "negativo", "tampoco",
    )

    /** Cómo dice que sí, como palabra entera. Solo sirve para detectar que una frase se contradice, nunca para autorizar. */
    private val AFIRMACIONES = setOf(
        "si", "claro", "dale", "hazlo", "haz", "mandalo", "mandaselo", "mandala", "envialo", "enviaselo", "llamalo",
        "compartelo", "borralo", "ok", "okey", "vale", "listo", "adelante", "correcto", "exacto", "perfecto", "obvio",
        "confirmo", "acepto", "sip", "porfa",
    )
}
