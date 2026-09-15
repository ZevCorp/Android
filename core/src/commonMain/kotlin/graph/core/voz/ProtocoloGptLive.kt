package graph.core.voz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** El Json de la voz. Lo que emite es lo que viaja, y por eso es lo que se cuenta contra el tope de un resultado. */
internal val VozJson: Json = Json { ignoreUnknownKeys = true }

/**
 * HABLAR CON GPT-LIVE, sin socket (docs/specs/002). Traduce en los dos sentidos y NO GUARDA NADA: lo
 * que hay que recordar (si la sesión abrió, con qué modo, qué llamadas faltan) es de la conversación.
 * Por eso se juzga entero dándole mensajes y mirando qué sale. Comportamiento medido por
 * `U-Windows-App/voz/Realtime/ProtocoloGptLive.cs` contra el servidor de verdad (2026-09-11/12).
 *
 * NO ES REALTIME CON OTRO NOMBRE, y las diferencias no dan error: dan «no me responde».
 *  - SU PROPIA PUERTA: `/v1/live/sessions`, sin `?model=`, y se abre con `session.start`.
 *  - LA VOZ NO LLEVA HERRAMIENTAS: las lleva un modelo DELEGADO dentro de `session.delegation`, y las
 *    instrucciones completas van a él. La voz lleva una persona corta.
 *  - LA SESIÓN ES INMUTABLE SALVO LA DELEGACIÓN: cambiar de modo es `session.update` de la delegación
 *    y un `session.instructions.append` a la voz; otro `session.start` sería otra conversación.
 *  - NO HAY MARCAS DE TURNO ([marcaLosTurnos] = false): las pone [TurnosSinMarca].
 */
@OptIn(ExperimentalEncodingApi::class)
class ProtocoloGptLive(val modelo: String = MODELO, val delegado: String = DELEGADO) {

    companion object {
        const val URL = "wss://api.openai.com/v1/live/sessions"

        /** Clavados, nunca un alias: un alias se mueve solo. */
        const val MODELO = "gpt-live-1"
        const val DELEGADO = "gpt-5.6-luna"

        /** La misma voz que en Windows: Ü sonando distinto suena a otro. */
        const val VOZ = "marin"

        /** PCM16LE mono a 24 kHz, en los dos sentidos. Darle otro ritmo no da error: suena acelerado. */
        const val RITMO = 24_000

        /**
         * Lo que va delante de las reglas que se le añaden a la voz. SON LOS TEXTOS MEDIDOS, letra por
         * letra (2026-09-12): con ellos la voz asintió 3 de 3 en modo aprendiz y volvió a delegar 2 de 2.
         * Cambiarlos es volver a medir. Y el append tiene tope de 500 fichas: no se alargan.
         */
        const val AL_CAMBIAR_DE_MODO = "CAMBIO DE MODO. Desde ahora mandan estas reglas sobre cuándo y cómo hablas, por encima de las anteriores:\n"
        const val AL_VOLVER = "VUELVES A TU MODO DE SIEMPRE. Lo anterior sobre el modo especial ya no manda; desde ahora mandan estas reglas:\n"

        /** Con esto delante, la voz dijo la frase literal (medido). */
        const val AL_DICTAR = "Di exactamente esto, sin añadir nada ni comentarlo: "
    }

    val url: String = URL
    val ritmo: Int = RITMO

    /**
     * NO, aunque el delegado sabe ver: lo que no cabe es la foto. Una captura real no entra en «128 items
     * and 32768 UTF-8 bytes per session» y el delegado contestó como si la hubiera visto borrosa.
     */
    val mira: Boolean = false

    /** No manda ni speech_started ni response.done; `response.completed` es del delegado. */
    val marcaLosTurnos: Boolean = false

    /** Sí: `session.started`. Un error antes de él es que no abrió (sin crédito, instrucciones de más). */
    val confirmaQueAbrio: Boolean = true

    /** La clave va en la cabecera, NUNCA en la URL: una URL acaba en los logs. */
    fun cabeceras(clave: String): Map<String, String> = mapOf("Authorization" to "Bearer $clave")

    /** Un solo `session.start`. Las herramientas y las instrucciones completas van a la delegación. */
    fun apertura(instruccionesVoz: String, instruccionesDelegado: String, utensilios: List<Utensilio>): String = mensaje {
        put("type", "session.start")
        putJsonObject("session") {
            put("model", modelo)
            put("instructions", instruccionesVoz)
            putJsonObject("audio") {
                putJsonObject("format") {
                    put("type", "audio/pcm")
                    put("rate", RITMO)
                }
                putJsonObject("output") { put("voice", VOZ) }
            }
            put("delegation", delegacion(instruccionesDelegado, utensilios))
        }
    }

    /** Un trozo de micrófono (100 ms son 4800 B). */
    fun audio(pcm: ByteArray): String = mensaje {
        put("type", "session.input_audio.append")
        put("audio", Base64.encode(pcm))
    }

    /** Una frase escrita y SU `response.create`: sin él el servidor la acepta y no contesta (medido). */
    fun texto(texto: String): List<String> = listOf(
        mensaje {
            put("type", "response.item.create")
            putJsonObject("item") {
                put("type", "message")
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "input_text")
                        put("text", texto)
                    }
                }
            }
        },
        pedirRespuesta(),
    )

    /**
     * Un `function_call_output` por llamada, recortado al tope ([recortado]). NO pide respuesta: eso es
     * [pedirRespuesta], aparte, una vez por tanda cuando no queda llamada sin contestar (lo decide A2).
     */
    fun resultados(hechas: List<Resultado>): List<String> =
        hechas.map { r -> recortado(r.texto) { salida -> salidaDeLaLlamada(r.id, salida) } }

    /** Pedir turno. Del resultado a la primera voz, 9-72 ms (medido). */
    fun pedirRespuesta(): String = mensaje { put("type", "response.create") }

    /** Hacer decir a Ü una frase exacta con SU voz. `session.instructions.append` no provoca respuesta; esto sí. */
    fun dictar(texto: String): String = mensaje {
        put("type", "session.commentary.append")
        put("delegation_id", JsonNull)
        put("content", AL_DICTAR + texto.trim())
    }

    /**
     * EL `session.update` DE LA DELEGACIÓN Y DETRÁS EL APPEND A LA VOZ. Sin el append cambiaba el que
     * actúa y no el que habla: 3 de 3 la voz afirmó lo que nadie hizo (2026-09-12). Al volver al modo de
     * siempre ([vuelve]) la voz recibe su persona, no las instrucciones de operar: no las lleva y no caben.
     * Quien llama dice si vuelve, porque recordar con qué se abrió es estado, y el traductor no tiene.
     */
    fun cambiarDeModo(instrucciones: String, utensilios: List<Utensilio>, vuelve: Boolean, instruccionesVoz: String): List<String> = listOf(
        mensaje {
            put("type", "session.update")
            putJsonObject("session") { put("delegation", delegacion(instrucciones, utensilios)) }
        },
        mensaje {
            put("type", "session.instructions.append")
            put("delegation_id", JsonNull)
            put("content", if (vuelve) AL_VOLVER + instruccionesVoz else AL_CAMBIAR_DE_MODO + instrucciones)
        },
    )

    /** Qué dice el servidor, en hechos. Lo que no se entiende da lista vacía, NUNCA una excepción que se lleve el socket. */
    fun leer(json: String): List<Hecho> {
        return try {
            val m = VozJson.parseToJsonElement(json) as? JsonObject ?: return emptyList()
            traducir(m)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun traducir(m: JsonObject): List<Hecho> = when (m.cadena("type")) {
        // CONTINUO, también en silencio: un delta de 100 ms cada ~100-130 ms, y ese silencio son CEROS
        // EXACTOS. Tomado por sonido, la compuerta de eco no se reabría nunca. Ni vacío ni silencio suenan.
        "session.output_audio.delta" -> {
            val b64 = m.cadena("delta")
            val pcm = if (b64.isEmpty()) null else Base64.decode(b64)
            if (pcm == null || esSilencio(pcm)) emptyList() else listOf(Hecho.Suena(pcm))
        }

        "session.output_transcript.delta" -> m.cadena("delta").let { if (it.isEmpty()) emptyList() else listOf(Hecho.DiceU(it)) }

        "session.input_transcript.delta" -> m.cadena("delta").let { if (it.isEmpty()) emptyList() else listOf(Hecho.DiceElUsuario(it)) }

        // LA MISMA LLAMADA LLEGA TRES VECES: output_item.added (arguments vacío), function_call_arguments.done
        // y output_item.done. Atender más de una ejecuta la herramienta dos o tres veces, la primera sin
        // argumentos: se compara el tipo entero, no un prefijo.
        "response.event" -> {
            val evento = m["event"] as? JsonObject
            val item = evento?.get("item") as? JsonObject
            if (evento?.cadena("type") == "response.output_item.done" && item?.cadena("type") == "function_call") {
                listOf(Hecho.Pide(listOf(laLlamada(item))))
            } else {
                emptyList()
            }
        }

        "session.started" -> listOf(Hecho.Abierta)

        // El code tal cual y nunca el type: invalid_request_error lo traen todos, también los que no son fatales.
        "error" -> {
            val error = m["error"] as? JsonObject
            val que = when {
                error == null -> "error sin detalle"
                error.cadena("message").isNotEmpty() -> error.cadena("message")
                else -> VozJson.encodeToString(JsonObject.serializer(), error)
            }
            listOf(Hecho.Falla(que, error?.cadena("code") ?: ""))
        }

        // LO QUE DURA, acumulado (12.0 y luego 25.0). Solo un número: un «seconds» vacío o en texto no es cero.
        "session.usage.updated" -> {
            val segundos = ((m["usage"] as? JsonObject)?.get("seconds") as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            if (segundos == null) emptyList() else listOf(Hecho.Duracion(segundos))
        }

        // Callarlo deja una sesión muerta con el micrófono en rojo y ninguna pista de por qué no contesta.
        "session.closed" -> listOf(Hecho.Falla("sesión cerrada: " + m.cadena("reason").ifEmpty { "sin motivo" }))

        else -> emptyList()
    }

    /**
     * Los argumentos vienen como TEXTO con un JSON dentro. Es el pinchazo obvio y no da error: da un mapa
     * vacío y una herramienta que hace otra cosa. Un valor que no es texto viaja como su JSON crudo; un
     * JSON ilegible deja la llamada sin argumentos antes que reventar.
     */
    private fun laLlamada(item: JsonObject): Llamada {
        val crudo = item.cadena("arguments")
        val args = if (crudo.isEmpty()) emptyMap() else try {
            (VozJson.parseToJsonElement(crudo) as? JsonObject)
                ?.mapValues { (_, v) -> if (v is JsonPrimitive && v.isString) v.content else v.toString() }
                ?: emptyMap()
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
        return Llamada(item.cadena("call_id"), item.cadena("name"), args)
    }

    private fun delegacion(instrucciones: String, utensilios: List<Utensilio>): JsonObject = buildJsonObject {
        put("type", "responses")
        putJsonObject("responses") {
            put("model", delegado)
            put("instructions", instrucciones)
            putJsonArray("tools") { for (u in utensilios) add(comoFuncion(u)) }
            put("tool_choice", "auto")
        }
    }

    /** Una herramienta vestida de `function`, con todos los argumentos de texto y ninguno obligatorio. */
    private fun comoFuncion(u: Utensilio): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", u.nombre)
        put("description", u.descripcion)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                for (a in u.args) putJsonObject(a.nombre) {
                    put("type", "string")
                    put("description", a.que)
                }
            }
            putJsonArray("required") {}
        }
    }

    private fun salidaDeLaLlamada(id: String, salida: String): String = mensaje {
        put("type", "response.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", id)
            put("output", salida)
        }
    }

    private fun mensaje(cuerpo: JsonObjectBuilder.() -> Unit): String =
        VozJson.encodeToString(JsonObject.serializer(), buildJsonObject(cuerpo))

    /** El campo si es texto; vacío si falta o es de otra forma. Lo que viene de la red se normaliza aquí. */
    private fun JsonObject.cadena(campo: String): String =
        (this[campo] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
}
