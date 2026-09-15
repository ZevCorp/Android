package graph.core.graph.learning

/*
 * LO QUE MANDA GRAPH SE MIDE ANTES DE LEERLO (spec 004, promesa 413). kotlinx-serialization 1.7.1 no tiene tope de
 * profundidad: lee miles de niveles sin problema, pero pasar a texto un millar (`toString`, un log) o comparar unos
 * miles (`equals`) lanza StackOverflowError. Es un Error, no una Exception: ningún `catch (e: Exception)` lo atrapa y en
 * Android tumba el proceso. En U no pasa porque System.Text.Json corta a 64 niveles con una JsonException que sí se
 * captura: el tope es el mismo. Con la guarda en la entrada de [LearningClient], ningún JsonElement que salga de él
 * pasa de 64 niveles, y quien lo reciba lo puede registrar, comparar y guardar sin miedo.
 *
 * La rama de voz tiene la misma cuenta en `graph.core.voz.JsonCrudo` (spec 002, promesa 203), con los mismos nombres a
 * propósito: al integrar las ramas se unifican en una sola (spec 004, «La guarda de profundidad»).
 */

/** Niveles de objetos y listas que se leen de Graph, como System.Text.Json en U: 64 se leen, 65 no. */
const val PROFUNDIDAD_MAXIMA = 64

/**
 * Si [json] abre más de [tope] niveles de `{` o `[`. Una pasada, sin pila y sin parsear: lo que está dentro de un texto
 * no cuenta y una comilla escapada no lo cierra. No valida nada más: lo mal formado lo rechaza el parser, que hasta el
 * primer error va exactamente tan hondo como esta cuenta.
 */
internal fun demasiadoAnidado(json: String, tope: Int = PROFUNDIDAD_MAXIMA): Boolean {
    var nivel = 0
    var enTexto = false
    var i = 0
    while (i < json.length) {
        val c = json[i]
        if (enTexto) {
            if (c == '\\') i++ else if (c == '"') enTexto = false
        } else {
            when (c) {
                '"' -> enTexto = true
                '{', '[' -> if (++nivel > tope) return true
                '}', ']' -> nivel--
            }
        }
        i++
    }
    return false
}

/** Cuántos bytes ocupa [texto] en UTF-8, sin copiarlo: lo que se registra de una respuesta en vez de la respuesta. */
internal fun bytesUtf8(texto: String): Int {
    var n = 0
    var i = 0
    while (i < texto.length) {
        val c = texto[i]
        n += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < texto.length && texto[i + 1].isLowSurrogate() -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return n
}
