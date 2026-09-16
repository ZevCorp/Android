package graph.core.pregunta

import graph.core.domain.AgentAction

/** Un texto sin tildes ni mayúsculas: así se comparan el pedido, un destinatario y una etiqueta. */
internal fun plano(texto: String): String = texto.lowercase().map { SIN_TILDE[it] ?: it }.joinToString("")

/** Las palabras de un texto, en plano y EN ORDEN: hay quien necesita saber cuál abre (ver [Respuesta]). */
internal fun palabrasEnOrden(texto: String): List<String> = PALABRA.findAll(plano(texto)).map { it.value }.toList()

/** Las palabras de un texto, en plano: lo que se busca como palabra entera, nunca como trozo. */
internal fun palabras(texto: String): Set<String> = palabrasEnOrden(texto).toSet()

/**
 * UNA HUELLA ACOTADA de un texto: su largo y un hash de 64 bits del texto en plano y con los espacios normalizados. Con
 * ella se arma la llave de lo ya contestado (docs/specs/006, promesa 617): un correo largo, o un compartir con el texto de
 * toda la pantalla, no tienen por qué vivir enteros en memoria mientras dura la corrida.
 *
 * SIGUE DISTINGUIENDO DOS CONTENIDOS DISTINTOS, que es lo que promete la 610. Lo único que deja de distinguir es el
 * espaciado: el mismo texto escrito con dos espacios o con un salto de línea es el mismo texto, y no se pregunta dos veces
 * por él.
 *
 * NO ES UN SELLO PARA EL LOG, y al log no sale (promesa 608): un hash corto sin sal se revierte por fuerza bruta. Para
 * nombrar un dato en una línea que sale del teléfono está [graph.core.precision.Sello], con su sal por proceso.
 */
fun huella(texto: String): String {
    val limpio = plano(texto).replace(ESPACIOS, " ").trim()
    var h = FNV_BASE
    for (byte in limpio.encodeToByteArray()) {
        h = h xor (byte.toLong() and 0xFF)
        h *= FNV_PRIMO
    }
    return "${limpio.length}#" + CharArray(16) { i -> HEX[((h ushr ((15 - i) * 4)) and 0xF).toInt()] }.concatToString()
}

/** Espacios, tabuladores y saltos de línea seguidos: escribir un texto más suelto no lo vuelve otro texto. */
private val ESPACIOS = Regex("""\s+""")

/* FNV-1a de 64 bits. Dos textos distintos con la misma huella son una casualidad de una entre 2^64, y no hay nada que
   revertir mientras la huella no salga del proceso, que es la regla de arriba. */
private const val FNV_BASE = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIMO = 0x100000001b3L
private const val HEX = "0123456789abcdef"

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
         * ¿El pedido de la persona pidió ESTA acción, con este destinatario y este contenido? Las tres cosas, y ninguna se
         * da por buena por no poder mirarla (promesa 609):
         *  - nombra la acción: una palabra de [VERBOS] de esa clase está en el pedido;
         *  - el destinatario se corresponde: ver [correspondeElDestino];
         *  - el contenido se corresponde: ver [correspondeElContenido].
         *
         * LA AUSENCIA DE EVIDENCIA NO ES UN PERMISO. Antes, una comprobación sin nada que cruzar devolvía `true` «porque no
         * había nada que comparar», y como un teléfono no tiene letras y una llamada no tiene contenido, las dos que debían
         * frenar quedaban vacías: bastaba nombrar el verbo. Un control lo reprodujo con seis frases ordinarias («llama a
         * mamá» llamaba a otro número; «busca la marca de este producto» llamaba, porque «marca» es verbo de llamada). Lo
         * que no se puede comparar **frena**.
         */
        fun loPidio(pedido: String, sensible: AccionSensible): Boolean {
            val dichas = palabras(pedido)
            if (VERBOS.getValue(sensible.clase).none { it in dichas }) return false
            if (!correspondeElDestino(pedido, dichas, sensible.destino)) return false
            return correspondeElContenido(dichas, sensible.contenido)
        }

        /**
         * ¿El destinatario de la ACCIÓN es el que nombra el pedido? Con letras («Ana», «jefe@…») se cruza por palabra. Sin
         * letras —el número que el cerebro resolvió de los contactos— se cruza COMO NÚMERO, sin separadores ni prefijo de
         * país. Si el pedido no trae ninguno, no hay con qué compararlo y **no autoriza**.
         *
         * Y SE CRUZA CON EL DESTINATARIO DE ESA ACCIÓN, NO CON CUALQUIER NÚMERO DEL TEXTO (promesa 616). Antes bastaba con
         * que el número apareciera en alguna parte: con «llama a mi jefe al 300 111 2222 y mándale un mensaje a mi hermana
         * al 300 333 4444», si el cerebro cruzaba los números, el equivocado también estaba en el pedido y la comprobación
         * autorizaba igual. Ahora el pedido tiene que traer UN SOLO destinatario posible —dos escrituras del mismo número
         * son uno—; con dos no hay forma de decidir cuál va con esta acción, así que se pregunta.
         *
         * Un destinatario vacío no es un destinatario que no cruza: es un dato que falta, y lo pide la pregunta de dato
         * (promesa 603). Por eso pasa de largo por aquí.
         *
         * Y UN DESTINO CORTO NO SE AUTORIZA POR COINCIDIR (promesa 618). Con menos de 7 cifras no hay forma de saber si el
         * número que trae el pedido es un destinatario: «cobré 89000 pesos, mándale un mensaje a mi mamá» con un `send_sms`
         * al código corto «89000» coincidía en el largo y en el valor, y el monto autorizaba un mensaje a un código de
         * banco. La igualdad exacta puede seguir sirviendo para descartar; para autorizar, nunca.
         */
        private fun correspondeElDestino(pedido: String, dichas: Set<String>, destino: String): Boolean {
            if (destino.isBlank()) return true
            val propias = palabras(destino).filter { it.length >= LARGO_DE_NOMBRE && it.any(Char::isLetter) }
            if (propias.isNotEmpty()) return propias.any { it in dichas }
            val suyo = soloCifras(destino)
            if (suyo.length < CIFRAS_DE_TELEFONO) return false // lo corto se pregunta: un monto no dice a quién va (618)
            val posibles = numerosDe(pedido).distinctBy { comoSeCruza(it) }
            val unico = posibles.singleOrNull() ?: return false
            return comoSeCruza(unico) == comoSeCruza(suyo)
        }

        /**
         * Cómo se cruzan dos escrituras de un mismo destino: por las últimas 7 cifras, así el prefijo de país y los
         * separadores no lo vuelven otro. Nada más corto llega hasta aquí: un destino de menos de 7 cifras no autoriza.
         */
        private fun comoSeCruza(numero: String) = numero.takeLast(CIFRAS_DE_TELEFONO)

        /**
         * ¿El contenido de la ACCIÓN sale del pedido? Alguna palabra propia suya —larga y fuera del [RELLENO]— tiene que
         * estar en el pedido. Un contenido sin ninguna palabra propia que cruzar («Ya voy», «Ok gracias») **no autoriza**:
         * que sea corto no lo vuelve algo que la persona pidió.
         *
         * Una acción sin contenido, como una llamada, no tiene nada que cruzar aquí: la frena su destinatario.
         */
        private fun correspondeElContenido(dichas: Set<String>, contenido: String): Boolean {
            if (contenido.isBlank()) return true
            val propias = palabras(contenido).filter { it.length >= LARGO_DE_PALABRA && it.any(Char::isLetter) && it !in RELLENO }
            return propias.any { it in dichas }
        }

        /** Las cifras de un texto, sin separadores ni signos: «+57 310-445-9821» → «573104459821». */
        private fun soloCifras(texto: String) = texto.filter { it.isDigit() }

        /**
         * Los destinatarios POSIBLES del pedido, cada uno sin sus separadores: «mándale al 310 445 9821 que…» →
         * «3104459821». Lo que no puede ser un destino no cuenta, y así no vuelve ambiguo un pedido que no lo es: una
         * [FECHA] (promesa 620), un número más corto que un teléfono («a las 8», «el bus 45», un código) y una ristra más
         * larga que un teléfono, que son dos números pegados.
         */
        private fun numerosDe(pedido: String): List<String> =
            TELEFONO.findAll(pedido)
                .filterNot { FECHA.matches(it.value) }
                .map { soloCifras(it.value) }
                .filter { it.length in CIFRAS_DE_TELEFONO..CIFRAS_MAXIMAS }
                .toList()

        /** Un número escrito con separadores: cifras y lo que puede ir entre ellas, empezando y acabando en cifra. */
        private val TELEFONO = Regex("""\d[\d\s().\-]*\d""")

        /**
         * Una fecha escrita con guiones («2026-03-15», «15-03-2026»). El guion también separa un teléfono, así que sin esto
         * la fecha se fundía en una ristra de 8 cifras y un pedido con fecha y un teléfono inequívoco quedaba «ambiguo» y
         * preguntaba de más (promesa 620). Con barras no hace falta: [TELEFONO] no las junta.
         */
        private val FECHA = Regex("""\d{1,4}-\d{1,2}-\d{1,4}""")

        /**
         * Por cuántas cifras del final se reconocen dos escrituras del mismo teléfono. Siete es el número local de Colombia
         * sin indicativo: con menos, dos números distintos coincidirían por casualidad.
         */
        private const val CIFRAS_DE_TELEFONO = 7

        /** El destino más largo que existe (E.164). Una ristra más larga son dos números pegados, y no es un destinatario. */
        private const val CIFRAS_MAXIMAS = 15

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
