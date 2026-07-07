package com.zevcorp.graph.platform

import graph.core.domain.RawStep
import graph.core.domain.Workflow
import graph.core.domain.WorkflowBrain
import graph.core.domain.WorkflowSource
import graph.core.domain.WorkflowStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * POST-PROCESADOR de workflows (el paso que organiza el workflow de forma efectiva). Al terminar la
 * enseñanza recibe la traza cruda de clics y la estructura: limpia los pasos que eran basura o
 * innecesarios, organiza los necesarios, agrega notas de contexto OPCIONALES donde decida que aportan,
 * y asigna la vía de cada step: MCP (subconsciente) a los que quedaron listos por árbol de UI,
 * consciente a los que no se lograron detectar bien.
 */
class GeminiWorkflow(
    private val apiKey: () -> String,
    private val model: () -> String,
) : WorkflowBrain {

    override suspend fun structure(
        source: WorkflowSource,
        steps: List<RawStep>,
        existing: List<Workflow>,
    ): Workflow? = withContext(Dispatchers.IO) {
        val mode = if (source == WorkflowSource.PASSIVE)
            "PASIVA (observaste al usuario usar el teléfono con normalidad; la traza terminó al salir de la app)"
        else
            "ACTIVA (el usuario compartió pantalla para enseñarte; la traza terminó al parar la grabación)"

        val trace = steps.mapIndexed { i, s ->
            val label = s.label.ifBlank { "(sin etiqueta: el árbol de UI no detectó el elemento)" }
            "${i + 1}. [${s.app} · ${s.screen}] clic en \"$label\""
        }.joinToString("\n")

        val existingBlock = if (existing.isEmpty()) "(ninguno todavía)"
        else existing.joinToString("\n") { "- ${it.name}: ${it.description.take(140)}" }

        val prompt = """
            Eres Graph, un asistente que controla el teléfono Android del usuario. Tu modo de enseñanza
            $mode acaba de terminar. Viste explícitamente el paso a paso de una tarea y lo grabaste como
            traza cruda de clics (la unidad de un step es el CLIC sobre un elemento del árbol de UI).
            Tu trabajo AHORA es el POST-PROCESAMIENTO: decidir si esa traza es un WORKFLOW reutilizable
            (una tarea con principio y fin que valdrá la pena repetir) y, si lo es, estructurarlo.

            TRAZA CRUDA, EN ORDEN:
            $trace

            WORKFLOWS QUE YA CONOCES:
            $existingBlock
            Si la traza es la MISMA tarea que un workflow existente, usa su MISMO nombre (se refina, no
            se duplica). Si es una tarea nueva, elige un nombre snake_case corto y descriptivo.

            CÓMO ESTRUCTURAR (calidad sobre cantidad):
            - LIMPIA: elimina los pasos basura y los innecesarios (clics erróneos, repetidos, desvíos,
              cierres de teclado, ruido) y ORGANIZA los necesarios en el orden real de la tarea.
            - Cada step: "action" = una frase corta e imperativa de qué hace ese paso.
            - "target": si el clic vino con etiqueta detectada, CÓPIALA EXACTA y marca
              "subconscious": true → ese step queda listo para ejecutarse por MCP (tocar por árbol de
              UI, sin mirar la pantalla).
            - Si el elemento NO se logró detectar bien (sin etiqueta, o la etiqueta es ambigua/dinámica,
              p.ej. el texto de un chat que cambia), deja "target": "" y "subconscious": false → ese
              step se ejecutará de forma CONSCIENTE (el asistente mirará la pantalla); describe muy
              bien "action" para que pueda hacerlo sin más contexto.
            - "note": nota de contexto OPCIONAL de ese step. NO todos los steps deben llevarla: agrégala
              SOLO donde decidas que aporta (p.ej. "aquí el destinatario depende de lo que pida el
              usuario" o "esperar a que cargue la lista"). Si no aporta, déjala "".
            - "app": el paquete Android donde ocurre el step (cópialo de la traza).
            - "description": qué tarea completa el workflow y cuándo conviene usarlo.
            - Si la traza NO completa ninguna tarea clara (navegación errática, muy pocos clics con
              sentido), responde {"worth": false} y nada más: guardar basura es peor que no guardar.

            Responde SOLO JSON:
            {"worth": true, "name": "nombre_snake_case", "description": "...",
             "steps": [{"app": "com.ejemplo", "action": "...", "target": "etiqueta exacta o \"\"",
                        "subconscious": true, "note": ""}]}
        """.trimIndent()

        runCatching {
            val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "workflow")
            if (o["worth"]?.jsonPrimitive?.booleanOrNull != true) return@withContext null
            val knownLabels = steps.mapNotNull { it.label.takeIf { l -> l.isNotBlank() }?.lowercase() }.toSet()
            val parsed = o["steps"]?.jsonArray?.mapNotNull { el -> step(el.jsonObject, knownLabels) }.orEmpty()
            Workflow(
                name = o.str("name").ifBlank { "workflow_aprendido" },
                description = o.str("description"),
                steps = parsed,
                source = source.id,
            ).takeIf { it.steps.isNotEmpty() && it.description.isNotBlank() }
        }.getOrElse { LogBus.log("workflow", "post-procesamiento falló: ${it.message}"); null }
    }

    /** Un step del JSON, con la red de seguridad: sin etiqueta REAL de la traza no hay subconsciente. */
    private fun step(o: JsonObject, knownLabels: Set<String>): WorkflowStep? {
        val action = o.str("action").ifBlank { return null }
        val target = o.str("target")
        // Solo es subconsciente si el modelo lo marcó Y el target es una etiqueta que de verdad se
        // detectó en el árbol de UI durante la enseñanza (nunca una alucinada): lo demás, consciente.
        val subconscious = o["subconscious"]?.jsonPrimitive?.booleanOrNull == true &&
            target.isNotBlank() && target.lowercase() in knownLabels
        return WorkflowStep(
            action = action,
            app = o.str("app"),
            target = if (subconscious) target else "",
            subconscious = subconscious,
            note = o.str("note"),
        )
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
}
