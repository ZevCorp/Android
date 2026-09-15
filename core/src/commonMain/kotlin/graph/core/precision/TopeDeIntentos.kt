package graph.core.precision

import kotlin.math.roundToInt

/**
 * DOS INTENTOS Y NO TRES (spec 003, promesas 310-313). Dentro de una misma petición, la tercera entrada
 * hacia un destino que ya falló dos veces no se ejecuta, y se contesta qué salió en cada una.
 *
 * Nace de U (`TopeDeIntentos.cs`, promesa 204): tres toques idénticos a «Descargas», 6-7 s cada uno, y
 * ninguno llegó; la nota de voz de esa noche lo pidió en cifras: «máximo dos intentos».
 *
 * EL DESTINO ES LO QUE SE TOCA, NO CÓMO SE PIDE. Un toque cuenta por el selector del nodo vivo bajo el
 * punto: dos coordenadas del mismo botón son el mismo botón. Sin nodo, la celda de [CELDA_DP] dp que
 * contiene el punto. Por nombre, con una lista de homónimos previa, `which` elige el candidato y ese
 * candidato ES su nodo; sin lista, `which` no abre un destino nuevo (en U, which=1, 2, 3… eran claves
 * nuevas del MISMO botón, sin límite). Al escribir cuenta el campo tal como se pidió.
 *
 * LO LOGRADO NO CUENTA, Y LO QUE NO SE SABE NO SE ADIVINA. Tres «Siguiente» que avanzan son trabajo, no
 * insistencia. Logro es que la acción se dio y escribió o cambió la huella ([logro]); una lista de
 * homónimos no es intento; un toque que se dio sin huella con que juzgarlo no es fallo: el tope nunca
 * frena por adivinar.
 *
 * DIFERENCIA DELIBERADA CON U: aquí el tope vigila también los toques por coordenada de Graph, y por eso
 * la huella que decide «cambió» incluye los textos visibles. Sin ellos, apretar tres veces el «7» de la
 * calculadora (cambia el display, no los botones) sería un tercer intento fallido. Un falso «cambió» solo
 * afloja la protección; un falso «no cambió» bloquea a la persona.
 *
 * Límite conocido, como el [Freno]: sin candados (common no los trae sin dependencia); dos entradas
 * simultáneas desde hilos distintos podrían contarse mal. Hoy una sola corrida toca a la vez.
 */
class TopeDeIntentos(private val celdaPx: Int) {
    init {
        require(celdaPx > 0) { "la celda tiene que medir al menos 1 px: $celdaPx" }
    }

    /** Hacia dónde va una entrada. [legible] es como se le nombra al modelo en el rechazo. */
    sealed class Destino {
        abstract val legible: String

        /** El nodo vivo que se toca: su selector es su identidad. */
        class Nodo(val selector: String, override val legible: String = selector) : Destino()

        /** Sin nodo bajo el punto: la celda de 48 dp que lo contiene. */
        class Celda(val columna: Int, val fila: Int, override val legible: String = "celda:$columna,$fila") : Destino()

        /** Donde se escribe, tal como se pidió. */
        class Campo(val campo: String, override val legible: String = campo) : Destino()

        /** Por nombre (una etiqueta), con el número de candidato si lo trae. */
        class Nombre(val nombre: String, val which: String? = null) : Destino() {
            override val legible: String get() = if (which.isNullOrBlank()) nombre else "$nombre (which=${which.trim()})"
        }
    }

    /** Cómo salió una entrada, tal como se sabe. */
    sealed class Salida {
        /** La acción lanzó: un intento que no se logró. */
        class Revento(val queSalio: String) : Salida()

        /** Se contestó con una lista de homónimos: no es intento, pero se recuerda (es lo que hace valer `which`). */
        class Lista(val texto: String, val candidatos: List<String> = emptyList()) : Salida()

        /** Fue intento. [cambio] `null`: no hubo huella con que juzgarlo. */
        class Intento(val dio: Boolean, val escribio: Boolean, val cambio: Boolean?, val queSalio: String) : Salida()
    }

    private val fallidos = HashMap<String, MutableList<String>>()
    private val listas = HashMap<String, Salida.Lista>()

    /** El destino de un toque en (x, y): el nodo realmente tocado; sin nodo (o sin selector), su celda. */
    fun alTocar(x: Int, y: Int, nodo: NodoVivo?): Destino {
        val selector = nodo?.selector?.trim().orEmpty()
        return if (nodo != null && selector.isNotEmpty()) Destino.Nodo(selector, nodo.etiqueta.ifBlank { selector })
        else celda(x, y)
    }

    /** Escribir por coordenada: el campo pedido es el punto pedido (su celda), no el nodo que lo resuelve. */
    fun alEscribirEn(x: Int, y: Int): Destino.Campo = Destino.Campo(clave(celda(x, y)), "($x,$y)")

    /** Escribir por nombre de campo, tal como se pidió. */
    fun alEscribir(campo: String): Destino.Campo = Destino.Campo(campo.trim())

    /** El destino como se compara. Lo usa también la [CuentaDePeticion]: dos criterios de «mismo sitio» acabarían discrepando. */
    fun clave(destino: Destino): String = when (destino) {
        is Destino.Nodo -> destino.selector
        is Destino.Celda -> "celda:${destino.columna},${destino.fila}"
        is Destino.Campo -> destino.campo
        is Destino.Nombre -> nombreYLista(destino).first
    }

    /** `null` si la entrada puede ir; si no, el motivo ya redactado: que van dos, qué salió en cada una y qué hacer. */
    fun rechazo(destino: Destino): String? {
        val salio = fallidos[llave(destino)] ?: return null
        if (salio.size < MAXIMO) return null
        val lista = listaDe(destino)
        // Sin lista, `which` no se nombra: sería mandar al modelo a esquivar el tope con otro número.
        val legible = if (destino is Destino.Nombre && lista == null) destino.nombre else destino.legible
        val via = when {
            lista != null -> "De la lista, prueba OTRO candidato con which: ${lista.texto} — o dile al usuario qué está pasando."
            destino is Destino.Campo -> "Cambia de vía: mira la pantalla y escribe en otro campo, o dile al usuario qué está pasando."
            else -> "Cambia de vía: mira la pantalla y toca otra cosa, o dile al usuario qué está pasando."
        }
        return "no lo intento una tercera vez: «$legible» ya falló dos veces en esta petición — 1) ${salio[0]} · 2) ${salio[1]}. $via"
    }

    /** Lo que pasa DESPUÉS de cada entrada vigilada, en un solo sitio que el contrato juzga. */
    fun despues(destino: Destino, salida: Salida) {
        when (salida) {
            is Salida.Lista -> if (destino is Destino.Nombre) listas[aplanar(destino.nombre)] = salida
            is Salida.Revento -> falla(destino, salida.queSalio)
            is Salida.Intento -> if (logro(salida.dio, salida.escribio, salida.cambio) == false) falla(destino, salida.queSalio)
        }
    }

    /** La persona pidió otra cosa: lo de antes era otra petición. */
    fun nuevaPeticion() {
        fallidos.clear()
        listas.clear()
    }

    private fun celda(x: Int, y: Int) = Destino.Celda(x.floorDiv(celdaPx), y.floorDiv(celdaPx), "($x,$y)")

    /** Tocar y escribir en el mismo sitio son destinos distintos. */
    private fun llave(destino: Destino) = (if (destino is Destino.Campo) "escribir|" else "tocar|") + clave(destino)

    private fun falla(destino: Destino, queSalio: String) {
        fallidos.getOrPut(llave(destino)) { mutableListOf() } += recortar(queSalio)
    }

    /**
     * La clave de un nombre y su lista. Con lista de ese nombre, `which` se lee como número (`"02"` y
     * `"+2"` son el 2) y lleva al selector del candidato; lo que no es un número del 1 al N no elige nada.
     * Sin lista, el nombre a secas.
     */
    private fun nombreYLista(destino: Destino.Nombre): Pair<String, Salida.Lista?> {
        val nombre = aplanar(destino.nombre)
        val lista = listas[nombre] ?: return nombre to null
        val n = destino.which?.trim()?.toIntOrNull()?.takeIf { it >= 1 } ?: return nombre to lista
        return when {
            lista.candidatos.isEmpty() -> "$nombre#$n" to lista
            n <= lista.candidatos.size -> lista.candidatos[n - 1] to lista
            else -> nombre to lista
        }
    }

    /** La lista que nombra este destino: la de su nombre, o la que tiene su nodo entre los candidatos. */
    private fun listaDe(destino: Destino): Salida.Lista? = when (destino) {
        is Destino.Nombre -> nombreYLista(destino).second
        is Destino.Nodo -> listas.values.firstOrNull { destino.selector in it.candidatos }
        else -> null
    }

    companion object {
        const val MAXIMO = 2
        const val CELDA_DP = 48

        /** Los px de una celda de [CELDA_DP] dp a esta densidad (`displayMetrics.density`). */
        fun celdaPx(densidad: Float): Int = (CELDA_DP * densidad).roundToInt().coerceAtLeast(1)

        /**
         * Una clave tal como puede ir al log, que sale del teléfono por la telemetría (promesa 317): la celda tal
         * cual; el selector de un nodo o un nombre, que llevan lo que la pantalla muestra, como un hash corto (`#` y
         * 8 hex, FNV-1a de 32 bits). El mismo destino da el mismo hash: se sigue de una línea a otra sin leer qué dice.
         */
        fun enLog(clave: String): String {
            if (CELDA_EN_LOG.matches(clave)) return clave
            var h = 0x811c9dc5.toInt()
            for (c in clave) {
                h = h xor c.code
                h *= 0x01000193
            }
            return "#" + h.toUInt().toString(16).padStart(8, '0')
        }

        private val CELDA_EN_LOG = Regex("""celda:-?\d+,-?\d+""")

        /** ¿Se logró? `null` si no se sabe: se dio sin escribir y no hubo huella con que juzgar si cambió. */
        fun logro(dio: Boolean, escribio: Boolean, cambio: Boolean?): Boolean? = when {
            !dio -> false
            escribio -> true
            else -> cambio
        }

        private val SIN_TILDE = ("áàäâã" + "éèëê" + "íìïî" + "óòöôõ" + "úùüû" + "ñç")
            .zip("aaaaa" + "eeee" + "iiii" + "ooooo" + "uuuu" + "nc").toMap()

        /** Un nombre como se compara: minúsculas, sin tildes, espacios juntos. */
        internal fun aplanar(s: String): String = buildString {
            var espacio = false
            for (c in s.trim().lowercase()) {
                if (c.isWhitespace()) { espacio = true; continue }
                if (espacio) append(' ')
                espacio = false
                append(SIN_TILDE[c] ?: c)
            }
        }

        private fun recortar(s: String): String {
            val t = s.replace('\n', ' ').trim()
            return if (t.length <= 280) t else t.take(279) + "…"
        }
    }
}
