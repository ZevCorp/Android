package graph.core.voz

import graph.core.domain.McpTool

/**
 * LO QUE EL DELEGADO PUEDE PEDIR (docs/specs/002, fase 2B2a). Tres herramientas y las tres SOLO LEEN.
 *
 * EL CATÁLOGO DE CAPACIDADES SE DERIVA DEL CATÁLOGO REAL de acciones ([McpTool], el mismo que ve el cerebro de Graph):
 * una lista escrita a mano se desincroniza en cuanto alguien añade una acción, y entonces la voz promete lo que no hay
 * o calla lo que sí. Aquí una acción nueva aparece sola.
 *
 * NINGUNA ACTÚA EN LA PANTALLA ([actuaEnPantalla] es falso para todas), así que son herramientas DE CONTROL y corren en
 * el acto (promesa 234): una lectura que tarda no deja mudo al delegado ni encola a las que vienen detrás.
 *
 * TRES Y NO TREINTA. Declarar una herramienta por acción llenaría la delegación con todo el catálogo —y el servidor
 * admite 32 768 B por sesión—, así que las acciones se cuentan en el resultado de [QUE_PUEDO_HACER] (que además pasa por
 * el recorte), no en la apertura.
 */
object CatalogoDeVoz {

    const val DONDE_ESTOY = "donde_estoy"
    const val QUE_VEO = "que_veo"
    const val QUE_PUEDO_HACER = "que_puedo_hacer"

    /** El argumento de [QUE_VEO]: qué buscar en la pantalla. */
    const val FILTRO = "filtro"

    /** Lo que se contesta a lo que todavía no se puede ejecutar. Esta fase da ojos, no manos. */
    const val TODAVIA_NO =
        "todavía no puedo hacer eso: por ahora solo miro la pantalla y te cuento lo que hay. Dime qué quieres saber y lo miro."

    /** Lo que cabe de la descripción de una acción: la primera frase basta para saber qué hace. */
    const val TOPE_DE_LA_DESCRIPCION = 140

    /** Lo que cabe el catálogo entero. Muy por debajo del tope de un resultado, que además lo recortaría. */
    const val TOPE_DEL_CATALOGO = 12_000

    private const val COLA = "…[y más acciones]"

    /** Las tres que viajan en la delegación del `session.start`. */
    val UTENSILIOS: List<Utensilio> = listOf(
        Utensilio(
            DONDE_ESTOY,
            "Dice en qué app y en qué pantalla está el teléfono ahora mismo, y de qué tamaño es la pantalla. Solo mira: no toca nada.",
            emptyList(),
        ),
        Utensilio(
            QUE_VEO,
            "Dice qué hay en la pantalla: cuántos elementos se pueden tocar, cuántos campos de texto y las etiquetas visibles. " +
                "Con «$FILTRO» contesta si algo concreto está o no está. Solo mira: no toca nada.",
            listOf(Argumento(FILTRO, "Qué buscar en la pantalla; déjalo vacío para que te cuente todo lo que ve")),
        ),
        Utensilio(
            QUE_PUEDO_HACER,
            "Dice qué sabrá hacer Ü en este teléfono. Todavía no ejecuta ninguna de esas acciones.",
            emptyList(),
        ),
    )

    /** Ninguna toca la pantalla: todas son de control y corren en el acto. */
    fun actuaEnPantalla(nombre: String): Boolean = false

    /** El catálogo de capacidades que lee el delegado, derivado de [acciones] y agrupado por vía. */
    fun capacidades(acciones: List<McpTool>): String {
        val texto = buildString {
            append("Todavía no puedo ejecutar nada en el teléfono. Esto es lo que Ü sabrá hacer cuando tenga manos:\n")
            for ((via, grupo) in acciones.groupBy { it.via }) {
                append("\n$via:\n")
                for (t in grupo) append("- ${t.name}: ${corta(t.description)}\n")
            }
            append("\nAhora mismo solo puedo mirar: $DONDE_ESTOY, $QUE_VEO, $QUE_PUEDO_HACER.")
        }
        return if (texto.length <= TOPE_DEL_CATALOGO) texto else texto.take(TOPE_DEL_CATALOGO - COLA.length) + COLA
    }

    /** La primera frase de una descripción, acotada: hay descripciones de 1 500 caracteres en el catálogo real. */
    private fun corta(descripcion: String): String {
        val limpia = descripcion.replace('\n', ' ').trim()
        val punto = limpia.indexOf(". ")
        val frase = if (punto in 1 until TOPE_DE_LA_DESCRIPCION) limpia.take(punto + 1) else limpia
        return if (frase.length <= TOPE_DE_LA_DESCRIPCION) frase else frase.take(TOPE_DE_LA_DESCRIPCION).trimEnd() + "…"
    }
}
