package graph.core.voz

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/*
 * LO QUE LLEGA DE LA RED SE MIDE ANTES DE LEERLO (docs/specs/002, promesa 203). kotlinx-serialization 1.7.1 no tiene
 * tope de profundidad: parsear miles de niveles, o pasar a texto un millar, lanza StackOverflowError. Es un Error, no
 * una Exception: ningún catch de la voz lo atrapa y se lleva el socket. Y los argumentos de una llamada los escribe el
 * delegado, así que basta una inyección desde la pantalla. En U no pasa porque System.Text.Json corta a 64 niveles con
 * una JsonException que sí se captura: el tope es el mismo.
 */

/** Niveles de objetos y listas que se leen, como System.Text.Json en U: 64 se leen, 65 no. */
const val PROFUNDIDAD_MAXIMA = 64

/** Cuánto se guarda de un texto crudo del servidor: acaba en el log y en lo que se le dice al usuario. */
internal const val CRUDO_MAXIMO = 400

/**
 * Si [json] abre más de [tope] niveles de `{` o `[`. Una pasada, sin pila y sin parsear: lo que está dentro de un texto
 * no cuenta y una comilla escapada no lo cierra. No valida nada más: lo mal formado lo rechaza el parser, que hasta
 * el primer error va exactamente tan hondo como esta cuenta.
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

/** El JSON leído, o null si no se entiende o viene anidado de más. Nunca lanza: lo que viene de la red no tumba la voz. */
internal fun leerSinReventar(json: String): JsonElement? {
    if (demasiadoAnidado(json)) return null
    return try {
        VozJson.parseToJsonElement(json)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/**
 * El texto TAL CUAL LLEGÓ del valor de [campo] en el objeto raíz de [json], sin re-serializarlo (como `GetRawText` en U),
 * o null si no está. Se llama con [json] ya leído: bien formado y dentro del tope. Si la clave se repite, cuenta la
 * última, como en el parser.
 */
internal fun valorCrudo(json: String, campo: String): String? {
    var i = sinBlancos(json, 0)
    if (i >= json.length || json[i] != '{') return null
    var encontrado: String? = null
    i = sinBlancos(json, i + 1)
    while (i < json.length && json[i] == '"') {
        val finClave = finDelTexto(json, i)
        val clave = json.substring(i, finClave)
        i = sinBlancos(json, finClave)
        if (i >= json.length || json[i] != ':') return encontrado
        val inicio = sinBlancos(json, i + 1)
        val fin = finDelValor(json, inicio)
        if (textoDe(clave) == campo) encontrado = json.substring(inicio, fin)
        i = sinBlancos(json, fin)
        if (i < json.length && json[i] == ',') i = sinBlancos(json, i + 1) else break
    }
    return encontrado
}

/** [texto] entero si cabe en [maximo] caracteres; si no, su principio sin partir un carácter, y «…». */
internal fun recortadoA(texto: String, maximo: Int = CRUDO_MAXIMO): String {
    if (texto.length <= maximo) return texto
    val n = if (texto[maximo - 1].isHighSurrogate()) maximo - 1 else maximo
    return texto.substring(0, n) + "…"
}

private fun sinBlancos(json: String, desde: Int): Int {
    var i = desde
    while (i < json.length && json[i].isWhitespace()) i++
    return i
}

/** Justo detrás del texto que abre en [desde] con su comilla. */
private fun finDelTexto(json: String, desde: Int): Int {
    var i = desde + 1
    while (i < json.length) {
        when (json[i]) {
            '\\' -> i++
            '"' -> return i + 1
        }
        i++
    }
    return json.length
}

/** Justo detrás del valor que empieza en [desde]: un texto, un objeto o una lista enteros, o un literal. */
private fun finDelValor(json: String, desde: Int): Int {
    if (desde >= json.length) return desde
    return when (json[desde]) {
        '"' -> finDelTexto(json, desde)
        '{', '[' -> {
            var nivel = 0
            var i = desde
            while (i < json.length) {
                when (json[i]) {
                    '"' -> {
                        i = finDelTexto(json, i)
                        continue
                    }
                    '{', '[' -> nivel++
                    '}', ']' -> if (--nivel == 0) return i + 1
                }
                i++
            }
            json.length
        }
        else -> {
            var i = desde
            while (i < json.length && json[i] !in ",}] \t\r\n") i++
            i
        }
    }
}

/** Una clave con sus comillas, decodificada. Casi nunca trae escapes; si los trae, la decodifica el parser: un texto no anida. */
private fun textoDe(claveConComillas: String): String =
    if ('\\' !in claveConComillas) claveConComillas.substring(1, claveConComillas.length - 1)
    else (leerSinReventar(claveConComillas) as? JsonPrimitive)?.content.orEmpty()
