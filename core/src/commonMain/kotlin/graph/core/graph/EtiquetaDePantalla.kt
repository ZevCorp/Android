package graph.core.graph

/** Lo que cabe de una etiqueta del árbol de UI. Más largo no identifica un elemento: es un párrafo de la pantalla. */
const val TOPE_DE_UNA_ETIQUETA = 40

/** Con esto une las etiquetas el resumen de la pantalla (`GraphAccessibilityService.uiContext()`). */
const val SEPARADOR_DE_ETIQUETAS = " · "

/**
 * UNA ETIQUETA COMO CABE EN EL RESUMEN DE LA PANTALLA (docs/specs/002, arreglo de la 2B2a).
 *
 * SE SANEA EN ORIGEN PORQUE EL RESUMEN ES UN TEXTO PLANO: `uiContext()` pone una línea por sección y une las etiquetas
 * con [SEPARADOR_DE_ETIQUETAS]. Una etiqueta con salto de línea partía el resumen en una sección que nadie escribió —y
 * quien lo lee contestaba «no lo veo» de algo que sí estaba—, y una que trajera el separador inflaba la cuenta.
 *
 * NO CAMBIA LO QUE VE EL CEREBRO: el mismo texto alimenta el turno de Graph. Un salto de línea pasa a espacio y el
 * separador, a guion; ambos se leen igual. Un `·` sin espacios alrededor no se toca, porque no parte nada.
 */
fun etiquetaDePantalla(texto: String, tope: Int = TOPE_DE_UNA_ETIQUETA): String = TODO("arreglo 2B2a")
