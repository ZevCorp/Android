package com.zevcorp.graph.voice

import com.zevcorp.graph.platform.GeminiJson
import com.zevcorp.graph.platform.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** La jugada que decide el cerebro de reunión tras cada fragmento escuchado. */
data class MeetingMove(
    val notes: List<String> = emptyList(),
    val task: String = "",
    val say: String = "",
    val closing: Boolean = false,
)

/**
 * El cerebro del MODO REUNIÓN (escucha por esquinas): Ü como un integrante más de la conversación.
 * Recibe cada fragmento transcrito + el estado acumulado y decide: tomar nota, lanzar una tarea al
 * motor (construir la idea en paralelo), hablar en voz alta, o detectar el cierre de la reunión e
 * intervenir con el resumen y la demo. Reemplaza al destilador simple SOLO en las esquinas: sirve
 * igual para una persona dictando órdenes que para varias personas desarrollando ideas.
 */
class MeetingBrain(
    private val apiKey: () -> String,
    private val model: () -> String,
) {

    suspend fun consider(
        segment: String,
        notes: List<String>,
        tasks: List<String>,
        elapsedMin: Long,
        closingDone: Boolean,
    ): MeetingMove? = withContext(Dispatchers.IO) {
        val prompt = """
            Eres Ü, un asistente con voz propia que participa EN VIVO en una conversación, escuchando por el
            micrófono de un teléfono Android que además sabes usar (tienes un motor que ejecuta tareas en él).
            Puede ser UNA persona dándote instrucciones, o DOS O MÁS personas desarrollando ideas en una reunión.

            Tu papel: el integrante encargado de (1) tomar nota de lo importante, (2) construir EN PARALELO la
            versión mínima y rápida de lo que se decida probar — no perfecta, sí funcional — y (3) al final de
            la reunión tomar la palabra y mostrar lo que hiciste.

            Recibes el ÚLTIMO fragmento transcrito (puede traer ruido, muletillas y voces mezcladas) y el estado
            acumulado. Decide tu jugada:

            - "notes": nuevas notas IMPORTANTES (decisiones, requisitos, ideas concretas, acuerdos, pendientes),
              cada una auto-contenida. Nada de relleno; no repitas notas que ya existen.
            - "task": UNA instrucción imperativa y auto-contenida para el motor del teléfono, o "".
              · Si alguien te pide algo directamente ("Ü, pon una alarma…"), esa es la task.
              · Si la conversación DEFINE algo que construir ("probemos esto", "hagamos una página que…"),
                lánzala SIN esperar a que te lo pidan. Ejemplo de task de construcción: primero, si hace falta
                repositorio, abre la app de GitHub y crea uno nuevo con nombre corto en minúsculas; luego abre
                la app de Claude, entra a la sección Code, crea una sesión nueva, conecta ese repositorio y
                escribe un prompt EXCELENTE: detallado, con TODOS los requisitos mencionados en la reunión, que
                pida construir la versión mínima funcional de la idea. Incluye ese prompt textual completo
                dentro de la task, entre comillas.
              · No dupliques: mira las tareas ya lanzadas. Si después aparecen ajustes nuevos, lanza una task de
                seguimiento SOLO con los cambios (p.ej. mandar un mensaje nuevo en la misma sesión de Claude).
            - "say": algo que decir EN VOZ ALTA ahora mismo, o "". Casi siempre "": NO interrumpas por comentar.
              Habla solo si te hablan directamente a ti, o en tu intervención de cierre.
            - "closing": true SOLO cuando detectes que la reunión llega a su fin (despedidas, "bueno, entonces
              quedamos así", recapitulación, silencio de cierre). Entonces tomas la palabra como un integrante
              más: en "say" interviene ("Chicos, antes de terminar, les muestro lo que hice…"): resume en pocas
              frases las notas clave y lo que construiste. Y en "task" pon la DEMO: abrir lo construido y
              recorrerlo explicando EN VOZ ALTA (con speak) cómo cada parte refleja lo que pidieron — "esta es
              la sección de fotos que mencionaron, aquí van las reviews…" — e invitar a pedir cambios de una vez.

            ESTADO DE LA REUNIÓN:
            - Minutos transcurridos: $elapsedMin
            - Notas hasta ahora: ${if (notes.isEmpty()) "(ninguna)" else notes.joinToString(" | ")}
            - Tareas lanzadas: ${if (tasks.isEmpty()) "(ninguna)" else tasks.joinToString(" | ")}
            - ¿Ya hiciste tu intervención de cierre?: ${if (closingDone) "SÍ: no la repitas; solo anota los cambios que pidan y lánzalos como tasks de seguimiento" else "no"}

            FRAGMENTO NUEVO: "$segment"

            Responde SOLO JSON:
            {"notes": ["…"], "task": "", "say": "", "closing": false}
        """.trimIndent()
        runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "meeting")
            MeetingMove(
                notes = o["notes"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) } ?: emptyList(),
                task = o["task"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                say = o["say"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                closing = o["closing"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }.getOrElse { LogBus.log("meeting", "cerebro de reunión falló: ${it.message}"); null }
    }
}
