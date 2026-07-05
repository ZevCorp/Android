package com.zevcorp.graph.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Lo que el asistente decide anticipar tras terminar una tarea. */
data class Foresight(
    /** Acción autónoma a ejecutar YA (solo si es de certeza total y segura); "" si ninguna. */
    val task: String = "",
    /** Aviso hablado al usuario (el amigo prevenido); "" si nada que decir. */
    val say: String = "",
)

/**
 * El "amigo prevenido": al terminar una tarea, una cadena de pensamiento CORTÍSIMA evalúa si hay
 * algo que debería hacerse JUSTO AHORA para que lo pedido no se tropiece (p.ej. tras poner una
 * alarma, asegurar que el volumen esté arriba). Solo autoriza acciones de certeza TOTAL y seguras;
 * lo demás lo deja como aviso hablado. Se anticipa a los problemas antes de que pasen.
 */
class Anticipation(
    private val apiKey: () -> String,
    private val model: () -> String,
) {
    suspend fun consider(request: String, done: String, tools: String): Foresight? = withContext(Dispatchers.IO) {
        val prompt = """
            Eres Graph. Acabas de completar lo que el usuario pidió. Piensa como ese AMIGO PREVENIDO
            que se anticipa a los problemas antes de que pasen (vamos al carro → nota que vas corto de
            gasolina y busca una estación en el camino).

            Lo que el usuario pidió: "$request"
            Lo que hiciste: "$done"
            Herramientas/acciones disponibles para actuar: $tools

            PIENSA CORTO (campo "reasoning", UNA frase): ¿hay algo que hacer JUSTO AHORA para que lo
            pedido no se tropiece? Ejemplos: tras poner una ALARMA, asegurar que el volumen de la
            alarma esté arriba (set_volume alarm 100); antes de transmitir a un parlante, que el wifi
            esté encendido.

            REGLAS ESTRICTAS:
            - "task": SOLO si es una acción de CERTEZA TOTAL, segura y reversible, que claramente
              protege o habilita lo que se pidió. Debe poder hacerse con las herramientas disponibles.
              Ante la MÍNIMA duda, deja "task" vacío. JAMÁS envíes mensajes/correos, llames, compres,
              publiques, borres, ni nada que afecte a terceros o sea irreversible.
            - "say": si hay un riesgo o dato útil que un amigo mencionaría pero que NO debes ejecutar
              por tu cuenta, ponlo aquí como aviso corto y natural. Si no hay nada, déjalo vacío.
            - Si no hay nada que anticipar, devuelve task y say vacíos. NO inventes tareas por inventar.

            Responde SOLO JSON:
            {"reasoning": "una frase", "task": "instrucción imperativa para ti mismo o vacío", "say": "aviso al usuario o vacío"}
        """.trimIndent()
        runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "run")
            val reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val task = o["task"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val say = o["say"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (reasoning.isNotBlank()) LogBus.log("run", "🔮 anticipo: ${reasoning.take(120)}")
            if (task.isBlank() && say.isBlank()) null else Foresight(task, say)
        }.getOrElse { LogBus.log("run", "anticipación falló: ${it.message}"); null }
    }
}
