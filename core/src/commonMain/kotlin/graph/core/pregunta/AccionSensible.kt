package graph.core.pregunta

import graph.core.domain.AgentAction

/** Un texto sin tildes ni mayúsculas: así se comparan el pedido, un destinatario y una etiqueta. */
internal fun plano(texto: String): String = texto.lowercase().map { SIN_TILDE[it] ?: it }.joinToString("")

/** Las palabras de un texto, en plano: lo que se busca como palabra entera, nunca como trozo. */
internal fun palabras(texto: String): Set<String> = PALABRA.findAll(plano(texto)).map { it.value }.toSet()

private val PALABRA = Regex("""[\p{L}\p{N}]+""")
private val SIN_TILDE = mapOf('á' to 'a', 'é' to 'e', 'í' to 'i', 'ó' to 'o', 'ú' to 'u', 'ü' to 'u', 'ñ' to 'n')

/**
 * LO QUE NO SE DESHACE (docs/specs/006). Una acción que sale del teléfono hacia otra persona —un mensaje, una llamada, algo
 * compartido— o que borra, paga o cambia un ajuste que otros notan.
 *
 * LA TABLA ES CERRADA: lo que no está aquí no es sensible. Preferir una lista corta y cierta a una heurística que un día
 * deja pasar un SMS: lo que falte se agrega con su promesa, y mientras tanto el freno y el tope siguen donde estaban.
 *
 * Llega por dos vías, que son las dos en las que el cliente PUEDE saber qué va a pasar:
 *  - una herramienta MCP del catálogo, por su nombre y sus argumentos;
 *  - una herramienta aprendida, por la etiqueta que va a tocar («Enviar», «Pagar», «Eliminar»).
 * `dial` no entra: abre el marcador y no llama (`Model.kt:92`). Un `tap(x,y)` de computer-use tampoco se puede clasificar:
 * el motor solo tiene el punto (ver «lo que NO entra» de la spec).
 */
class AccionSensible(val clase: Clase, val destino: String = "", val contenido: String = "") {

    /** Qué hace la acción: decide qué se le pregunta a la persona y qué palabra se busca en el pedido. */
    enum class Clase { MENSAJE, LLAMADA, BORRAR, PAGO, COMPARTIR, AJUSTE }

    companion object {
        /** Qué tiene de sensible esta acción, o `null` si no lo es. */
        fun de(accion: AgentAction): AccionSensible? {
            if (accion !is AgentAction.Mcp) return null
            val args = accion.args
            return when (accion.tool) {
                "send_sms" -> AccionSensible(Clase.MENSAJE, args.dato("number"), args.dato("message"))
                "send_email" -> AccionSensible(Clase.MENSAJE, args.dato("to"), junto(args.dato("subject"), args.dato("body")))
                "call" -> AccionSensible(Clase.LLAMADA, args.dato("number"))
                "share_text" -> AccionSensible(Clase.COMPARTIR, contenido = args.dato("text"))
                else -> deLasEtiquetas(args)
            }
        }

        /**
         * ¿El pedido de la persona pidió ESTA acción, con este destinatario y este contenido? Las tres cosas:
         *  - nombra la acción: una palabra de [VERBOS] de esa clase está en el pedido;
         *  - nombra al destinatario: alguna palabra suya de letras está en el pedido. Un destinatario SIN letras —el número
         *    que el cerebro resolvió de los contactos— no se puede cruzar con el pedido, así que no frena por sí solo;
         *  - el contenido viene del pedido: alguna palabra propia suya está en el pedido.
         */
        fun loPidio(pedido: String, sensible: AccionSensible): Boolean {
            val dichas = palabras(pedido)
            if (VERBOS.getValue(sensible.clase).none { it in dichas }) return false
            if (!nombra(dichas, sensible.destino, LARGO_DE_NOMBRE)) return false
            return nombra(dichas, sensible.contenido, LARGO_DE_PALABRA, RELLENO)
        }

        /** El nombre más corto que se cruza con el pedido: «Ana», «jefe». Menos que esto son partículas. */
        private const val LARGO_DE_NOMBRE = 3

        /** Una palabra propia de un contenido. Más corta que esto la escribe cualquiera. */
        private const val LARGO_DE_PALABRA = 4

        /** La etiqueta que va a tocar una herramienta aprendida: la primera que sea sensible manda. */
        private fun deLasEtiquetas(args: Map<String, String>): AccionSensible? {
            val etiquetas = args["taps"]?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: return null
            for (etiqueta in etiquetas) {
                val clase = palabras(etiqueta).firstNotNullOfOrNull { PALABRAS[it] }
                if (clase != null) return AccionSensible(clase, etiqueta)
            }
            return null
        }

        /** ¿El pedido nombra [texto]? Sin palabras propias que cruzar, no frena: no hay nada que comparar. */
        private fun nombra(dichas: Set<String>, texto: String, largo: Int, relleno: Set<String> = emptySet()): Boolean {
            val propias = palabras(texto).filter { it.length >= largo && it.any(Char::isLetter) && it !in relleno }
            return propias.isEmpty() || propias.any { it in dichas }
        }

        private fun Map<String, String>.dato(clave: String) = this[clave]?.trim().orEmpty()

        private fun junto(vararg partes: String) = partes.filter { it.isNotBlank() }.joinToString(" ")

        /** La palabra de una etiqueta que la vuelve sensible, como palabra entera y sin tildes. */
        private val PALABRAS: Map<String, Clase> = mapOf(
            "enviar" to Clase.MENSAJE, "envia" to Clase.MENSAJE, "mandar" to Clase.MENSAJE, "manda" to Clase.MENSAJE,
            "responder" to Clase.MENSAJE,
            "llamar" to Clase.LLAMADA, "llama" to Clase.LLAMADA, "videollamada" to Clase.LLAMADA,
            "borrar" to Clase.BORRAR, "borra" to Clase.BORRAR, "eliminar" to Clase.BORRAR, "elimina" to Clase.BORRAR,
            "vaciar" to Clase.BORRAR, "desinstalar" to Clase.BORRAR,
            "pagar" to Clase.PAGO, "paga" to Clase.PAGO, "comprar" to Clase.PAGO, "compra" to Clase.PAGO,
            "transferir" to Clase.PAGO, "suscribirse" to Clase.PAGO,
            "compartir" to Clase.COMPARTIR, "comparte" to Clase.COMPARTIR, "publicar" to Clase.COMPARTIR,
            "publica" to Clase.COMPARTIR,
            "bloquear" to Clase.AJUSTE, "bloquea" to Clase.AJUSTE, "silenciar" to Clase.AJUSTE, "silencia" to Clase.AJUSTE,
            "desactivar" to Clase.AJUSTE, "desactiva" to Clase.AJUSTE,
        )

        /** Cómo pide la persona cada clase de acción. En español: un pedido en otro idioma no autoriza, y se pregunta. */
        private val VERBOS: Map<Clase, Set<String>> = mapOf(
            Clase.MENSAJE to setOf(
                "manda", "mandale", "mandales", "mandar", "mandarle", "mandaselo", "envia", "enviale", "enviar", "enviarle",
                "escribe", "escribele", "escribir", "escribirle", "dile", "diles", "responde", "respondele", "contesta",
                "contestale", "mensaje", "mensajes", "sms", "correo", "email",
            ),
            Clase.LLAMADA to setOf("llama", "llamale", "llamalo", "llamar", "llamarle", "llamada", "marca", "marcale", "telefonea"),
            Clase.BORRAR to setOf("borra", "borrale", "borralo", "borrar", "elimina", "eliminalo", "eliminar", "vacia", "vaciar", "desinstala", "desinstalar"),
            Clase.PAGO to setOf("paga", "pagale", "pagalo", "pagar", "compra", "compralo", "comprar", "transfiere", "transferir", "transferencia", "suscribeme", "suscribir"),
            Clase.COMPARTIR to setOf("comparte", "compartelo", "compartir", "publica", "publicalo", "publicar"),
            Clase.AJUSTE to setOf("bloquea", "bloquealo", "bloquear", "silencia", "silenciar", "desactiva", "desactivar"),
        )

        /** Palabras que no dicen de qué habla un contenido: si son las únicas propias, no hay nada que cruzar. */
        private val RELLENO = setOf(
            "para", "esto", "este", "esta", "eso", "como", "donde", "cuando", "porque", "pero", "todo", "toda", "hola",
            "gracias", "buenas", "favor", "ahora", "bien",
        )
    }
}
