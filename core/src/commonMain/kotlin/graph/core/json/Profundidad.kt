package graph.core.json

/*
 * LO QUE LLEGA DE AFUERA SE MIDE ANTES DE LEERLO. Una sola guarda para la voz (docs/specs/002, promesa 203: lo que manda
 * GPT-Live y los argumentos que escribe el delegado) y para lo enseñado (docs/specs/004, promesa 413: lo que responde
 * Graph). kotlinx-serialization 1.7.1 no tiene tope de profundidad: parsear miles de niveles, pasar a texto un millar
 * (`toString`, un log) o comparar unos miles (`equals`) lanza StackOverflowError. Es un Error, no una Exception: ningún
 * `catch (e: Exception)` lo atrapa; en la voz se lleva el socket y en Android tumba el proceso. En U no pasa porque
 * System.Text.Json corta a 64 niveles con una JsonException que sí se captura: el tope es el mismo.
 */

/** Niveles de objetos y listas que se leen, como System.Text.Json en U: 64 se leen, 65 no. */
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
