package com.zevcorp.graph.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * EL DOCTOR DE CLICS: cuando la capa de diagnóstico detecta que replicar el clic por su etiqueta
 * caería en OTRO elemento (IDs duplicados, p.ej. todas las filas de chats con el mismo
 * "contact_row_container"), este LLM recibe el snapshot COMPLETO de la pantalla + el elemento que se
 * tocó + el que el agente resolvería, y:
 *   1) halla el ID/etiqueta ÚNICO correcto —presente en el snapshot— con el que el agente SÍ habría
 *      acertado ese elemento (el resultado de "mejora continua").
 *   2) escribe un resumen detallado del TIPO de error y de cómo endurecer la detección NATIVA de
 *      elementos, para que el equipo mejore la base con el tiempo.
 * Es transversal: sirve para cualquier app, no solo WhatsApp.
 */
class GeminiClickDoctor(
    private val apiKey: () -> String,
    private val model: () -> String,
) {
    class Diagnosis(val correctId: String, val errorType: String, val fixSummary: String) {
        /** Línea compacta para el panel de desarrollador. */
        fun panelLine() = buildString {
            if (correctId.isNotBlank()) append("ID correcto: \"$correctId\". ")
            if (errorType.isNotBlank()) append("[$errorType] ")
            append(fixSummary)
        }
    }

    suspend fun diagnose(app: String, screen: String, label: String, snapshot: String): Diagnosis? =
        withContext(Dispatchers.IO) {
            val prompt = """
                Eres Ü, un agente que controla un teléfono Android. Aprendes clics del usuario para
                repetirlos: capturas la ETIQUETA de un elemento (por orden: contentDescription → text →
                viewIdResourceName) y al ejecutar buscas el PRIMER nodo del árbol de UI con esa etiqueta
                (findByLabel) y tocas su ancestro clickable. PROBLEMA: cuando varios elementos comparten
                la misma etiqueta (típico en LISTAS), el primero gana y se toca el elemento EQUIVOCADO.

                Se detectó justo ese fallo:
                - App: $app
                - Pantalla: $screen
                - Etiqueta capturada (ambigua): "$label"
                - El usuario/agente tocó el elemento marcado [TOCADO], pero replicar esa etiqueta cae en
                  el marcado [AGENTE], que es OTRO elemento.

                SNAPSHOT de los elementos accionables de la pantalla (con sus atributos; entre corchetes
                los descendientes de texto que contiene cada uno):
                $snapshot

                Tu tarea, con dos salidas:
                1) "correctId": el identificador ÚNICO —EXACTO y PRESENTE en el snapshot de arriba (una
                   etiqueta propia o un texto descendiente del elemento [TOCADO])— con el que el agente
                   SÍ acertaría ESE elemento y ningún otro. Si de verdad no hay ninguno único, "".
                2) "errorType": categoría corta del fallo (p.ej. "id-de-contenedor-compartido-en-lista",
                   "texto-dinámico", "sin-etiqueta-propia").
                3) "fixSummary": 1-3 frases MUY concretas de cómo endurecer la DETECCIÓN NATIVA de
                   elementos para evitar esta clase de error a futuro (p.ej. "en filas de lista cuyo
                   viewId se repite, preferir como identificador el texto único del título de la fila;
                   al resolver por etiqueta, si hay empate, desambiguar por el texto descendiente").

                Responde SOLO JSON:
                {"correctId": "...", "errorType": "...", "fixSummary": "..."}
            """.trimIndent()

            runCatching {
                val o = GeminiJson.ask(apiKey(), model(), prompt, tag = "bug-ui")
                Diagnosis(
                    correctId = o["correctId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    errorType = o["errorType"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                    fixSummary = o["fixSummary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                ).takeIf { it.fixSummary.isNotBlank() || it.correctId.isNotBlank() }
            }.getOrElse { LogBus.log("bug-ui", "diagnóstico falló: ${it.message}"); null }
        }
}
