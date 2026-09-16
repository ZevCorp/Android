package graph.core.pregunta

import graph.core.domain.AgentAction

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

/** La pregunta que está en el aire y la acción que frenó: mientras existe, la corrida está viva y quieta (promesa 604). */
class PreguntaPendiente(val pregunta: Pregunta, val accion: AgentAction)

/**
 * Lo ÚNICO que el cliente lee de una respuesta: si niega. Lo demás lo interpreta el cerebro, que para eso tiene el contexto
 * entero; un clasificador de «sí/no» en el cliente es justo lo que se rompe (spec 006, «lo que NO entra»).
 *
 * No contestar es no autorizar: un diálogo que alguien cierra sin escribir nada no es un permiso.
 */
object Respuesta {

    fun niega(texto: String): Boolean {
        val dichas = palabras(texto)
        return dichas.isEmpty() || dichas.any { it in NEGACIONES }
    }

    /**
     * Cómo dice que no una persona, como palabra entera. «para» no está: es la palabra del alto, y el alto tiene su propio
     * freno (promesa 604); dentro de una respuesta suele ser «para las ocho».
     */
    private val NEGACIONES = setOf(
        "no", "nel", "nunca", "cancela", "cancelalo", "cancelar", "detente", "nada", "olvidalo", "olvidate", "ninguno",
        "ninguna", "negativo", "tampoco",
    )
}
