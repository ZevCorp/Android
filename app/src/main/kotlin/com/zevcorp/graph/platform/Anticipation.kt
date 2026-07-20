package com.zevcorp.graph.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Lo que el asistente decide anticipar tras terminar una tarea. */
data class Foresight(
    /** "offer" (proponer y ejecutar si acepta), "task" (acción autónoma segura) o "none". */
    val action: String = "none",
    /** Probabilidad honesta (0..1) de que ESTO de verdad valga la pena; filtro por umbral. */
    val worth: Double = 0.0,
    /** Propuesta hablada al usuario cuando action="offer"; "" si no aplica. */
    val question: String = "",
    /** Instrucción imperativa: la que se ejecuta si acepta el offer, o la acción autónoma. */
    val task: String = "",
    /** App implicada (best-effort), para dar referente al contexto del offer; "" si no aplica. */
    val app: String = "",
)

/**
 * Por DEBAJO de este umbral de valor, silencio. No es un contador de intentos hardcodeado: es la
 * propia probabilidad que el modelo asigna a cada caso ("¿esto de verdad vale la pena ofrecérselo
 * al usuario?"). Como el default es callar, en la práctica solo unas ~3 de cada 10 ejecuciones
 * superan la barra.
 */
const val WORTH_THRESHOLD = 0.30

/**
 * Al terminar una tarea, una cadena de pensamiento CORTÍSIMA evalúa si hay UNA acción DIRECTA —el
 * siguiente eslabón natural de lo que se acaba de hacer— que le ahorre al usuario el próximo paso
 * y que Ü pueda ofrecerse a hacer él mismo. El mindset es PROACTIVO, no miedoso: propone la acción
 * concreta encadenada con la tarea (p.ej. tras revisar cuántos datos quedan para compartir →
 * "¿activo el compartir datos?"), nunca advertencias precavidas de hipótesis lejanas. Su default
 * es CALLAR: solo habla cuando la recomendación es claramente valiosa (worth ≥ WORTH_THRESHOLD).
 */
class Anticipation(
    private val apiKey: () -> String,
    private val model: () -> String,
) {
    suspend fun consider(request: String, done: String, tools: String): Foresight? = withContext(Dispatchers.IO) {
        val prompt = """
            Eres Ü. Acabas de completar lo que el usuario pidió. Antes de callarte, piensa UNA cosa:
            ¿hay UNA acción DIRECTA —el siguiente eslabón natural de lo que acabas de hacer— que le
            ahorraría al usuario el próximo paso y que puedes ofrecerte a hacer TÚ mismo ya?

            Lo que el usuario pidió: "$request"
            Lo que hiciste: "$done"
            Herramientas/acciones disponibles para actuar: $tools

            FILOSOFÍA (léela bien):
            - Tu DEFAULT es CALLAR. Casi nunca hay algo que de verdad valga la pena; entonces te
              quedas callado. Solo hablas cuando la recomendación es CLARAMENTE valiosa.
            - PROACTIVO, NO MIEDOSO. Ofrece la acción concreta que CONTINÚA la tarea, encadenada
              directo con lo que hiciste (p.ej. tras revisar cuántos datos te quedan para compartir
              → "¿quieres que active el compartir datos ya?"). PROHIBIDO el tono precavido de
              hipótesis lejanas ("ten un cargador cerca porque gasta batería", "ojo que esto consume
              datos", "cuidado que el volumen podría molestar"). Eso NO es valioso: es miedo y ruido.
              Si lo único que se te ocurre es una advertencia así, CÁLLATE (action="none").
            - SIN SESGOS por costumbre. En especial: NO ofrezcas subir/bajar/mutar el volumen salvo
              que sea de verdad el siguiente paso obvio e imprescindible de lo pedido. La
              recomendación nace de ESTA tarea concreta, jamás de una plantilla.

            DECIDE UNA de tres:
            - "offer": ves una acción directa, encadenada con lo que hiciste, que le ahorra el
              siguiente paso. En "question" dila como propuesta corta y natural que NOMBRE su
              referente concreto ("Vi que te quedan 8 GB para compartir, ¿quieres que active el
              hotspot?"). En "task" la instrucción imperativa exacta que ejecutarías si acepta. En
              "app" el nombre de la app implicada, o vacío.
            - "task": una acción de CERTEZA TOTAL, segura y reversible, que HABILITA lo que acabas de
              hacer y que harías sin preguntar. Con cuentagotas. JAMÁS envíes mensajes/correos,
              llames, compres, publiques, borres, ni nada irreversible o que afecte a terceros.
            - "none": nada que valga la pena. Es la respuesta más común. Ante la mínima duda, "none".

            "worth" (0.0–1.0): tu probabilidad honesta de que ESTO de verdad valga la pena
            ofrecérselo al usuario —que aporte valor real y directo, no por cortesía—. Sé TACAÑO:
            reserva worth alto SOLO para el siguiente paso obvio y útil; si dudas, worth bajo. Un
            filtro externo descarta todo lo que no llegue al umbral, así que la mayoría de las veces
            esto termina en silencio.

            Responde SOLO JSON:
            {"reasoning": "una frase", "worth": 0.0, "action": "offer|task|none", "question": "propuesta hablada o vacío", "task": "instrucción imperativa o vacío", "app": "app implicada o vacío"}
        """.trimIndent()
        runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "run")
            val reasoning = o["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val worth = o["worth"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val action = o["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val question = o["question"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val task = o["task"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val app = o["app"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (reasoning.isNotBlank()) LogBus.log("run", "🔮 anticipo: ${reasoning.take(120)} · $action · worth=$worth")
            // Umbral de valor: por debajo del 30% de "vale la pena", silencio (ver WORTH_THRESHOLD).
            if (worth < WORTH_THRESHOLD) return@runCatching null
            when (action) {
                "offer" -> if (question.isNotBlank() && task.isNotBlank()) Foresight("offer", worth, question, task, app) else null
                "task" -> if (task.isNotBlank()) Foresight("task", worth, "", task, app) else null
                else -> null
            }
        }.getOrElse { LogBus.log("run", "anticipación falló: ${it.message}"); null }
    }
}
