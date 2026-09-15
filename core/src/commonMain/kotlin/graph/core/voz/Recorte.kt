package graph.core.voz

/**
 * CUÁNTO CABE EN UN RESULTADO DE HERRAMIENTA: 32 768 bytes UTF-8 contados sobre el MENSAJE que viaja,
 * no sobre el texto. Un mensaje de 41 084 B dio `response_input_buffer_full` y, en el mismo milisegundo,
 * `function_call_outputs_required`: la llamada quedó pendiente y cada `response.create` de la sesión
 * falló, con la voz hablando y el delegado ya sin hacer nada (medido por U, 2026-09-12).
 *
 * Se cuenta lo que emite el serializador porque las comillas, la barra invertida y los controles se
 * escapan, y no se midió si el servidor cuenta lo que viaja o lo que decodifica: contar lo que viaja
 * cumple las dos lecturas.
 */
const val TOPE_DE_UN_RESULTADO = 32_768

/**
 * Bytes UTF-8 de un texto, sin copiarlo. Un par surrogate son 4; uno suelto cuenta 3, lo más que puede
 * ocupar al codificarse: contar de más solo recorta antes, contar de menos deja la llamada colgada.
 */
fun bytesUtf8(texto: String): Int {
    var n = 0
    var i = 0
    while (i < texto.length) {
        val c = texto[i]
        when {
            c.code < 0x80 -> n += 1
            c.code < 0x800 -> n += 2
            c.isHighSurrogate() && i + 1 < texto.length && texto[i + 1].isLowSurrogate() -> { n += 4; i++ }
            else -> n += 3
        }
        i++
    }
    return n
}

/**
 * El mensaje entero si cabe; si no, el de lo más largo del principio del [texto] que quepa, sin partir un
 * carácter, con una cola que dice cuánto se mandó de cuánto. Recortar y decirlo es lo único que no deja la
 * llamada pendiente, y el delegado lee la cola y sabe que falta algo. [mensaje] viste el texto: el tope se
 * mide sobre lo que devuelve.
 */
fun recortado(texto: String, tope: Int = TOPE_DE_UN_RESULTADO, mensaje: (String) -> String): String {
    val entero = mensaje(texto)
    if (bytesUtf8(entero) <= tope) return entero

    val total = bytesUtf8(texto)
    // Un emoji son DOS Char: cortar entre los dos manda medio carácter, que el codificador cambia por otro.
    fun sinPartir(n: Int) = if (n > 0 && texto[n - 1].isHighSurrogate()) n - 1 else n
    fun con(n: Int): String {
        val principio = texto.substring(0, n)
        return mensaje("$principio…[recortado: ${bytesUtf8(principio)} de $total bytes]")
    }

    // Crece con n (más texto nunca ocupa menos), así que se busca por mitades el n más largo que cabe.
    var bajo = 0
    var alto = texto.length
    while (bajo < alto) {
        val medio = bajo + (alto - bajo + 1) / 2
        if (bytesUtf8(con(sinPartir(medio))) <= tope) bajo = medio else alto = medio - 1
    }
    return con(sinPartir(bajo))
}
