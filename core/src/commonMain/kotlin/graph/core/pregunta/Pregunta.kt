package graph.core.pregunta

import graph.core.domain.AgentAction

/**
 * UNA PREGUNTA DEL CLIENTE (docs/specs/006). No es la de Graph (`ask_user`, que viaja en `inform`): esta la hace el cliente
 * antes de ejecutar, y el texto se lo dice a la persona por el canal que ya existe ([graph.core.domain.UserChannel]).
 *
 * [asunto] es de qué se preguntó, normalizado y sin lo que la persona escribió: es la llave del registro de lo ya preguntado
 * en esta corrida, y nunca sale al log.
 */
class Pregunta(val clase: Clase, val asunto: String, val texto: String, val opciones: List<String> = emptyList()) {

    /** Por qué se pregunta. Lo único de la pregunta que sale del teléfono (spec 005, promesa 608). */
    enum class Clase(val enLog: String) { PERMISO("permiso"), CUAL("cual"), DATO("dato") }
}

/** La pregunta que está en el aire y la acción que frenó: mientras existe, la corrida está viva y quieta (promesa 604). */
class PreguntaPendiente(val pregunta: Pregunta, val accion: AgentAction)

/**
 * Lo ÚNICO que el cliente lee de una respuesta: si niega. Lo demás lo interpreta el cerebro, que para eso tiene el contexto
 * entero; un clasificador de «sí/no» en el cliente es justo lo que se rompe (spec 006, «lo que NO entra»).
 */
object Respuesta {
    /** ¿La persona dijo que no, o no dijo nada? */
    fun niega(texto: String): Boolean = false
}
