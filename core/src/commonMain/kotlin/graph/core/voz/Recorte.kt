package graph.core.voz

/** Cuánto cabe en un resultado de herramienta: bytes UTF-8 del mensaje serializado (docs/specs/002). */
const val TOPE_DE_UN_RESULTADO = 32_768

fun bytesUtf8(texto: String): Int = TODO()

fun recortado(texto: String, tope: Int = TOPE_DE_UN_RESULTADO, mensaje: (String) -> String): String = TODO()
