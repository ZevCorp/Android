package com.zevcorp.graph.platform

import com.zevcorp.graph.GraphApp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * EL BACKEND (repo Graph) visto desde el teléfono.
 *
 * La web clínica no organiza notas por su cuenta: manda la transcripción al backend y él devuelve
 * la nota. La app hace lo mismo, contra los mismos endpoints, para que la nota de un médico salga
 * igual en el navegador y en el teléfono — y para que mejorar el prompt allá mejore las dos.
 *
 * Dos caminos, según quién sea el usuario:
 *  · médico          → POST /api/v1/pipeline           (el motor clínico de siempre)
 *  · otra profesión  → POST /api/v1/organizer/organize  (su system prompt, hecho a su medida)
 *
 * La base y la key salen de la config distribuida (graph_client_config), igual que las de OpenAI,
 * Gemini y Deepgram: nadie digita nada al instalar y se pueden rotar sin publicar un APK.
 */
object MiracleApi {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs get() = GraphApp.instance.prefs

    /** URL base del backend. Vacía = la app aún no sabe a dónde hablar. */
    fun base(): String = RemoteConfig
        .resolve(prefs, "miracleBase", "remoteMiracleBase", "")
        .trimEnd('/')

    private fun apiKey(): String = RemoteConfig.resolve(prefs, "miracleKey", "remoteMiracleKey", "")

    /** ¿Está el backend configurado en este teléfono? */
    fun isConfigured(): Boolean = base().isNotBlank() && apiKey().isNotBlank()

    class ApiError(val status: Int, val code: String, message: String) : Exception(message)

    /** Un reporte ya organizado, sea la nota médica o el reporte de cualquier otro oficio. */
    class Report(
        val title: String,
        val sections: List<Section>,
        val warnings: List<String>,
        /** Texto plano listo para enviar por WhatsApp o pegar donde sea. */
        val text: String,
    ) {
        class Section(val key: String, val label: String, val content: String)
    }

    /** La configuración del usuario que NO es médico, tal como la conoce el backend. */
    class Profile(
        val id: String,
        val occupation: String,
        val sections: List<String>,
        val sampleCount: Int,
    )

    /* ---------- Médicos: el mismo pipeline de la web ---------- */

    /**
     * Transcripción → nota médica organizada. Es el `POST /api/v1/pipeline` con la etapa de nota
     * encendida: exactamente lo que consume la web clínica.
     */
    suspend fun medicalNote(transcript: String, sessionId: String): Report = post(
        "/api/v1/pipeline",
        buildJsonObject {
            put("session_id", sessionId)
            put("transcript", transcript)
            put("client_id", "android_app")
            put("language", "es")
            putJsonObject("stages") {
                put("transcription", true)
                put("note", true)
                put("autofill", false)
            }
        }
    ).let { body ->
        val note = body["note"]?.jsonObject
        val content = note?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (content.isBlank()) {
            val reason = note?.get("reason")?.jsonPrimitive?.contentOrNull
                ?: note?.get("error")?.jsonPrimitive?.contentOrNull
                ?: "sin_nota"
            throw ApiError(502, reason, "El backend no devolvió la nota organizada.")
        }
        Report(
            title = "Nota clínica",
            sections = listOf(Report.Section("nota", "Nota clínica", content)),
            warnings = emptyList(),
            text = content
        )
    }

    /* ---------- Quien no es médico: su propia hoja en blanco ---------- */

    /** ¿Ya está configurado este teléfono? `null` = todavía no. */
    suspend fun organizerProfile(): Profile? = try {
        parseProfile(get("/api/v1/organizer/profiles/${Telemetry.deviceId}")["profile"]?.jsonObject)
    } catch (error: ApiError) {
        if (error.status == 404) null else throw error
    }

    /**
     * Configuración inicial: lo que la persona contó por voz (y, si mandó, las capturas de cómo
     * organiza hoy) se convierten en SU system prompt, que queda guardado en el backend.
     *
     * @param screenshots data URLs (`data:image/jpeg;base64,…`).
     * @return el perfil y la frase con la que la carita confirma en voz alta.
     */
    suspend fun createOrganizerProfile(
        description: String,
        screenshots: List<String> = emptyList(),
    ): Pair<Profile, String> {
        val body = post("/api/v1/organizer/profiles", buildJsonObject {
            put("device_id", Telemetry.deviceId)
            put("profession", "otra")
            put("description", description)
            put("screenshots", buildJsonArray {
                screenshots.forEach { add(JsonPrimitive(it)) }
            })
        })
        val profile = parseProfile(body["profile"]?.jsonObject)
            ?: throw ApiError(502, "sin_perfil", "El backend no devolvió la configuración.")
        return profile to body["confirmation"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    /** Capturas añadidas después: reescriben el system prompt con el formato real de la persona. */
    suspend fun addOrganizerSamples(screenshots: List<String>): Pair<Profile, String> {
        val body = post("/api/v1/organizer/profiles/${Telemetry.deviceId}/samples", buildJsonObject {
            put("screenshots", buildJsonArray {
                screenshots.forEach { add(JsonPrimitive(it)) }
            })
        })
        val profile = parseProfile(body["profile"]?.jsonObject)
            ?: throw ApiError(502, "sin_perfil", "El backend no devolvió la configuración.")
        return profile to body["confirmation"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    /** Transcripción → reporte organizado con el system prompt de ESTA persona. */
    suspend fun organize(transcript: String): Report {
        val body = post("/api/v1/organizer/organize", buildJsonObject {
            put("device_id", Telemetry.deviceId)
            put("transcript", transcript)
        })
        val report = body["report"]?.jsonObject
            ?: throw ApiError(502, "sin_reporte", "El backend no devolvió el reporte.")
        val sections = report["sections"]?.jsonArray.orEmpty().map { element ->
            val section = element.jsonObject
            Report.Section(
                key = section["key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                label = section["label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                content = section["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
        return Report(
            title = report["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Reporte" },
            sections = sections,
            warnings = report["warnings"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) },
            text = report["markdown"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    /* ---------- HTTP ---------- */

    private fun parseProfile(row: JsonObject?): Profile? {
        if (row == null) return null
        return Profile(
            id = row["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            occupation = row["occupation"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sections = row["sections"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonObject["label"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            },
            sampleCount = row["sample_count"]?.jsonPrimitive?.intOrNull ?: 0
        )
    }

    private suspend fun get(path: String): JsonObject = request("GET", path, null)

    private suspend fun post(path: String, body: JsonObject): JsonObject =
        request("POST", path, body.toString())

    private suspend fun request(method: String, path: String, body: String?): JsonObject =
        withContext(Dispatchers.IO) {
            val base = base()
            val key = apiKey()
            if (base.isBlank() || key.isBlank()) {
                throw ApiError(503, "sin_configurar",
                    "Todavía no tengo a dónde mandar esto: falta configurar el backend.")
            }
            val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                // Organizar una nota larga puede tomar bastante: el modelo escribe todo el reporte.
                readTimeout = 120_000
                setRequestProperty("X-API-Key", key)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Miracle-App", "android_app")
                setRequestProperty("X-Miracle-Device-Id", Telemetry.deviceId)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            body?.let { connection.outputStream.use { out -> out.write(it.toByteArray()) } }

            val status = connection.responseCode
            val text = (if (status < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            connection.disconnect()

            val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (status >= 400) {
                // El backend responde {error:{code,message}} en el organizador y {error:"…"} en el
                // resto: los dos llegan aquí como el mismo ApiError.
                val error = parsed?.get("error")
                val code = error?.jsonObjectOrNull()?.get("code")?.jsonPrimitive?.contentOrNull
                val message = error?.jsonObjectOrNull()?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: error?.jsonPrimitive?.contentOrNull
                throw ApiError(status, code ?: "http_$status", message ?: "El backend respondió $status.")
            }
            parsed ?: throw ApiError(status, "respuesta_invalida", "El backend respondió algo que no entiendo.")
        }

    /** El error del backend llega como objeto {code,message} o como texto suelto. */
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
}
