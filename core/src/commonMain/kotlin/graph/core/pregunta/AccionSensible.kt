package graph.core.pregunta

import graph.core.domain.AgentAction

/**
 * LO QUE NO SE DESHACE (docs/specs/006). Una acción que sale del teléfono hacia otra persona —un mensaje, una llamada, algo
 * compartido— o que borra, paga o cambia un ajuste que otros notan. La tabla es CERRADA: lo que no está aquí no es sensible.
 *
 * ESQUELETO (fase E1): las promesas 601-608 se escribieron antes que esta lógica y nacen rojas contra estas firmas.
 */
class AccionSensible(val clase: Clase, val destino: String = "", val contenido: String = "") {

    /** Qué hace la acción, para decirlo al preguntar y para saber qué palabra buscar en el pedido. */
    enum class Clase { MENSAJE, LLAMADA, BORRAR, PAGO, COMPARTIR, AJUSTE }

    companion object {
        /** Qué tiene de sensible esta acción, o `null` si no lo es. */
        fun de(accion: AgentAction): AccionSensible? = null

        /** ¿El pedido de la persona pidió ESTA acción, con este destinatario y este contenido? */
        fun loPidio(pedido: String, sensible: AccionSensible): Boolean = true
    }
}
