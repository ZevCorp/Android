package graph.core.voz

/*
 * POR QUÉ UN FALLO DEL SERVIDOR DE VOZ NO SE ARREGLA RECONECTANDO (docs/specs/002). Espejo de
 * `U-Windows-App/windows-client/src/Voice/NoSeArreglaReintentando.cs`. Nace de la cuenta sin crédito:
 * la conversación reconectó cuatro veces con la misma cuenta, y cada vez falló igual.
 *
 * LOS CÓDIGOS SON LOS QUE CONTESTÓ EL SERVIDOR (sonda de U, 2026-09-13), no los de la documentación. Y
 * SE COMPARAN PALABRAS ENTERAS, separadas por punto o espacio: la descripción de un cierre es
 * «type.code», y buscar dentro de la prosa leería «401» en una frase que no es un código.
 *
 * Una frase por causa, y ninguna nombra la palabra de otra: quien lea el log tiene que distinguirlas.
 */

private const val SIN_CREDITO = "la cuenta no tiene crédito"
private const val CLAVE_QUE_NO_VALE = "la clave (OPENAI_API_KEY) no vale"
private const val MODELO_QUE_NO_ESTA = "el modelo no existe o esta cuenta no tiene acceso a él"

private val CAUSAS = mapOf(
    "credit_balance_exhausted" to SIN_CREDITO,
    "insufficient_quota" to SIN_CREDITO,
    "invalid_api_key" to CLAVE_QUE_NO_VALE,
    // El apretón de manos de GPT-Live rechaza la clave con HTTP 401 antes de abrir el socket.
    "401" to CLAVE_QUE_NO_VALE,
    "invalid_model" to MODELO_QUE_NO_ESTA,
    "model_not_found" to MODELO_QUE_NO_ESTA,
)

/**
 * [texto] es el code de una [Hecho.Falla], la descripción de un cierre («type.code») o el estado HTTP del
 * apretón de manos. Devuelve la causa dicha para una persona, o null si puede ser un corte y reconectar
 * tiene sentido.
 */
fun causaFatal(texto: String?): String? {
    if (texto == null) return null
    for (palabra in texto.split('.', ' ')) CAUSAS[palabra.trim()]?.let { return it }
    return null
}
