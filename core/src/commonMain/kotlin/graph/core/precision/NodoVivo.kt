package graph.core.precision

/**
 * Un nodo del árbol de accesibilidad VIVO: el que está en pantalla ahora, no uno recordado. Sin lógica:
 * lo llena la plataforma y lo leen el tope de intentos (3C) y los homónimos (3D).
 */
data class NodoVivo(
    /** Identidad estable del nodo en esta pantalla, p. ej. `a11y:id=…;text=…;desc=…;cls=…;path=…`. */
    val selector: String,
    /** Lo que se lee: texto o descripción de contenido. */
    val etiqueta: String,
    /** Clase o rol: Button, Tab, EditText… */
    val tipo: String,
    val bounds: Bounds,
    /** Se puede pulsar él o un ancestro cercano. */
    val accionable: Boolean,
) {
    /** Rectángulo en píxeles de pantalla, como `android.graphics.Rect`. */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
}
