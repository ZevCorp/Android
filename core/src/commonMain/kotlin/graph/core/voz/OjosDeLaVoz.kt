package graph.core.voz

import graph.core.graph.TurnScreenState

/**
 * LO QUE LA VOZ VE DE LA PANTALLA (docs/specs/002, fase 2B2a). SOLO LEE: no toca, no abre, no captura.
 *
 * MIRA EL MISMO ESTADO QUE EL TURNO DE GRAPH ([TurnScreenState]: `screen`, `uiContext`, tamaño), el que arma el
 * servicio de accesibilidad. No hay una segunda lectura de la pantalla: dos lecturas distintas acaban diciendo cosas
 * distintas, y la voz contaría una pantalla que el cerebro no está viendo. La captura NO viaja ([ProtocoloGptLive.mira]
 * es `false`): una PNG no cabe en los 32 768 B de la sesión.
 *
 * NO SE INVENTA NADA. Si el `uiContext` no trae lo que se le pregunta —una pantalla protegida, un formato que cambió—
 * vuelve tal cual, porque la persona de la voz prohíbe justo eso: «Nunca inventes lo que hay en pantalla ni lo que no
 * ves».
 *
 * LO QUE LEE ES LO QUE LA PANTALLA MUESTRA, así que nada de aquí va al log: el log sale del teléfono por la telemetría
 * (spec 005). Quien registre, que registre la MEDIDA ([etiquetas] y los largos).
 */
object OjosDeLaVoz {

    /**
     * LA CAUSA, UNA SOLA VEZ Y CON LAS MISMAS PALABRAS. Las tres herramientas se quedan sin nada que decir por el mismo
     * motivo, y antes cada una lo contaba a su manera: dos nombraban el servicio y `que_puedo_hacer` callaba la causa y
     * devolvía un catálogo vacío, que se lee como «no sé hacer nada».
     */
    const val SIN_SERVICIO = "el servicio de accesibilidad de Ü no está activo"

    /** Sin servicio de accesibilidad no hay estado que leer, y eso se dice en vez de callar. */
    const val SIN_PANTALLA = "no puedo ver la pantalla ahora mismo: $SIN_SERVICIO"

    /**
     * La lectura tardó más de lo que la conversación puede esperar. SE DICE, no se calla: mientras el delegado no
     * recibe salida, la voz se queda muda y el usuario no sabe si sigue viva.
     */
    const val NO_PUDE_MIRAR = "no pude mirar la pantalla a tiempo; vuelve a pedírmelo"

    /** Lo que se cita de un filtro. Más largo no es una búsqueda: es un texto que alguien quiere que se repita. */
    const val TOPE_DEL_FILTRO = 60

    /** Las líneas con que `GraphAccessibilityService.uiContext()` arma el resumen de la pantalla. */
    private const val PAQUETE = "paquete: "
    private const val TIPO = "tipo: "
    private const val CUENTAS = "clickeables: "
    private const val CAMPOS = "campos de texto: "
    private const val ETIQUETAS = "etiquetas visibles: "
    private const val ENFOCADO = "(enfocado: \""
    private const val TECLADO = " · teclado abierto"
    private const val NINGUNA = "(ninguna)"
    private const val SEPARADOR = " · "

    /** Dónde está el teléfono: la app al frente, el tipo de pantalla, el teclado y el tamaño. */
    fun dondeEstoy(estado: TurnScreenState?): String {
        if (estado == null) return SIN_PANTALLA
        val leido = Lectura.de(estado.uiContext)
        val app = estado.screen.trim().ifBlank { leido?.paquete.orEmpty() }.ifBlank { "no sé qué app está al frente" }
        val partes = mutableListOf("estás en «$app»")
        if (leido != null) {
            partes += leido.tipo
            if (leido.teclado) partes += "teclado abierto"
        }
        partes += "pantalla de ${estado.width}×${estado.height}"
        // Un `uiContext` que no se reconoce se cita entero: es lo único que se sabe de verdad de esa pantalla.
        if (leido == null) partes += "la pantalla dice: ${estado.uiContext.trim()}"
        return partes.joinToString(SEPARADOR)
    }

    /**
     * Qué hay en la pantalla. Sin [filtro], las cuentas y las etiquetas visibles; con él, si eso está o no, sin mirar
     * tildes ni mayúsculas. Lo que no se encontró NO se acompaña de la pantalla entera: se preguntó por una cosa.
     */
    fun queVeo(estado: TurnScreenState?, filtro: String = ""): String {
        if (estado == null) return SIN_PANTALLA
        val leido = Lectura.de(estado.uiContext) ?: return estado.uiContext.trim()
        val buscado = filtro.trim().take(TOPE_DEL_FILTRO)
        if (buscado.isEmpty()) {
            return buildString {
                append("${leido.tocables} elementos se pueden tocar${SEPARADOR}${leido.campos} campos de texto")
                if (leido.enfocado.isNotBlank()) append(" (escribiendo en «${leido.enfocado}»)")
                append(SEPARADOR)
                if (leido.etiquetas.isEmpty()) append("no leo ninguna etiqueta")
                else append("${leido.etiquetas.size} etiquetas: ${leido.etiquetas.joinToString(SEPARADOR)}")
            }
        }
        val aguja = comparable(buscado)
        val encontradas = leido.etiquetas.filter { aguja in comparable(it) }
        return if (encontradas.isEmpty()) {
            "«$buscado»: no lo veo entre las ${leido.etiquetas.size} etiquetas de esta pantalla"
        } else {
            "«$buscado»: sí, lo veo: ${encontradas.joinToString(SEPARADOR)}"
        }
    }

    /** Cuántas etiquetas se leyeron. Es la MEDIDA que puede ir al log; las etiquetas, no. */
    fun etiquetas(estado: TurnScreenState?): Int = Lectura.de(estado?.uiContext ?: "")?.etiquetas?.size ?: 0

    /** Sin tildes y en minúsculas: quien habla dice «camara» y la pantalla pone «Cámara». */
    private fun comparable(texto: String): String = buildString {
        for (c in texto.lowercase()) {
            append(
                when (c) {
                    'á', 'à', 'ä', 'â' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    'ñ' -> 'n'
                    else -> c
                },
            )
        }
    }

    /** El `uiContext` entendido. `null` si no tiene la forma que arma la accesibilidad: entonces no se interpreta. */
    private class Lectura(
        val paquete: String,
        val tipo: String,
        val teclado: Boolean,
        val tocables: Int,
        val campos: Int,
        val enfocado: String,
        val etiquetas: List<String>,
    ) {
        companion object {
            fun de(uiContext: String): Lectura? {
                // LAS ETIQUETAS SON LA ÚLTIMA SECCIÓN Y SE LEEN HASTA EL FINAL, no hasta el fin de la línea. Se sanean
                // en origen (`etiquetaDePantalla`), pero una que llegara con un salto de línea partía el resumen en una
                // sección que nadie escribió: se perdían las de detrás y se contestaba «no lo veo» de algo que estaba.
                if (ETIQUETAS !in uiContext) return null
                val cabecera = uiContext.substringBefore(ETIQUETAS)
                val etiquetas = uiContext.substringAfter(ETIQUETAS)
                val lineas = cabecera.lines()
                fun linea(prefijo: String) = lineas.firstOrNull { it.startsWith(prefijo) }?.removePrefix(prefijo)?.trim()
                val paquete = linea(PAQUETE) ?: return null
                val tipoCrudo = lineas.firstOrNull { it.startsWith(TIPO) }?.removePrefix(TIPO) ?: return null
                val cuentas = linea(CUENTAS) ?: return null
                // El enfocado lo cierra la ÚLTIMA comilla-paréntesis de la cabecera: su propio texto puede traer una.
                val enfocado = if (ENFOCADO in cabecera) cabecera.substringAfter(ENFOCADO).substringBeforeLast("\")") else ""
                return Lectura(
                    paquete = paquete,
                    tipo = tipoCrudo.removeSuffix(TECLADO).trim(),
                    teclado = tipoCrudo.trimEnd().endsWith(TECLADO.trim()),
                    tocables = cuentas.substringBefore(SEPARADOR).trim().toIntOrNull() ?: return null,
                    campos = cuentas.substringAfter(CAMPOS, "").trim().takeWhile { it.isDigit() }.toIntOrNull() ?: return null,
                    enfocado = enfocado,
                    etiquetas = if (etiquetas.isBlank() || etiquetas.trim() == NINGUNA) emptyList()
                    else etiquetas.split(SEPARADOR).map { it.trim() }.filter { it.isNotEmpty() },
                )
            }
        }
    }
}
