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
 */
object CatalogoDeVoz {

    const val DONDE_ESTOY = "donde_estoy"
    const val QUE_VEO = "que_veo"
    const val QUE_PUEDO_HACER = "que_puedo_hacer"

    /** El argumento de [QUE_VEO]: qué buscar en la pantalla. */
    const val FILTRO = "filtro"

    /** Lo que se contesta a lo que todavía no se puede ejecutar. Esta fase da ojos, no manos. */
    const val TODAVIA_NO =
        "todavía no puedo hacer eso: por ahora solo miro la pantalla y te cuento lo que hay. Dilo con palabras y lo miro."

    /** Lo que cabe de la descripción de una acción en el catálogo: la primera frase basta para saber qué hace. */
    const val TOPE_DE_LA_DESCRIPCION = 140

    /** Lo que cabe el catálogo entero. Muy por debajo del tope de un resultado, que además lo recorta. */
    const val TOPE_DEL_CATALOGO = 12_000

    /** Las tres que viajan en la delegación del `session.start`. */
    val UTENSILIOS: List<Utensilio> get() = TODO("2B2a")

    /** Ninguna toca la pantalla: todas son de control. */
    fun actuaEnPantalla(nombre: String): Boolean = TODO("2B2a")

    /** El catálogo de capacidades que lee el delegado, derivado de [acciones] y agrupado por vía. */
    fun capacidades(acciones: List<McpTool>): String = TODO("2B2a")
}
