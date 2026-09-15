package graph.core.graph.learning

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Contrato JSON de aprendizaje y workflows con Graph. Espejo de
 * `U-Windows-App/windows-graph/src/Contracts.cs` (grabación y ejecución) y de los contratos de
 * `/teach/…` en `windows-client/src/Teach/TeachSession.cs`: los nombres de campo son los de allá, tal
 * cual — snake en la sesión y en el request del plan, camel en el paso, el plan y `/teach/…`.
 *
 * Dos reglas que Windows no tiene y que la spec 004 promete (401):
 *  - un texto vacío de Graph es AUSENTE. Graph serializa lo que no hay como `""`, y en C# `??` no cae
 *    con `""`. Aquí esos campos se leen con [VacioEsAusente] y llegan `null`: `?:` sí cae;
 *  - los campos del request que tienen que viajar siempre NO tienen valor por defecto (con
 *    `encodeDefaults = false` un campo igual a su default se omite); los opcionales son `null` y no viajan.
 */

/** El Json de aprendizaje: no manda nulos ni defaults, un campo nuevo de Graph no rompe y un `null` donde no cabe toma el default. */
val LearningJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** `""` (o solo blancos) es ausente: `null`. La trampa medida en Windows, en una función. */
fun String?.vacioEsAusente(): String? = this?.takeIf { it.isNotBlank() }

/** El texto de un elemento JSON si es un texto no vacío; si no, `null`. */
internal fun JsonElement?.textoNoVacio(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content.vacioEsAusente()

/**
 * Lee un texto de Graph con [vacioEsAusente]: `""`, blancos y `null` llegan como `null`. Un número donde
 * iba un texto se lee como su texto; un objeto o una lista, como ausente.
 */
object VacioEsAusente : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("graph.core.graph.learning.VacioEsAusente", PrimitiveKind.STRING).nullable

    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString().vacioEsAusente()
        return when (val e = json.decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> e.content.vacioEsAusente()
            else -> null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) =
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
}

/** Un JSON crudo de Graph (la interpretación, el workflow, las pistas): `null` y `""` llegan como `null`. */
object JsonVacioEsAusente : KSerializer<JsonElement?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.nullable

    override fun deserialize(decoder: Decoder): JsonElement? =
        when (val e = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonNull -> null
            is JsonPrimitive -> if (e.isString && e.content.isBlank()) null else e
            else -> e
        }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: JsonElement?) =
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(JsonElement.serializer(), value)
}

/* ────────────────────────── Grabación (learning) ────────────────────────── */

/** `POST /api/v1/learning/sessions` — abre una sesión de grabación. Las siete claves viajan siempre. */
@Serializable
class StartSessionRequest(
    val description: String,
    @SerialName("app_id") val appId: String,
    /** La identidad de la superficie: en Android, `android://paquete/Activity`. */
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("source_origin") val sourceOrigin: String,
    @SerialName("source_pathname") val sourcePathname: String,
    @SerialName("source_title") val sourceTitle: String,
    /** `surface` y `platform` (en Windows, `windows`; aquí `android`). */
    val context: Map<String, String>,
)

@Serializable
class StartSessionResponse(
    val session: SessionInfo? = null,
    @Serializable(with = VacioEsAusente::class) val error: String? = null,
)

@Serializable
class SessionInfo(
    @Serializable(with = VacioEsAusente::class) val id: String? = null,
    @SerialName("workflow_id") @Serializable(with = VacioEsAusente::class) val workflowId: String? = null,
    val recording: Boolean = false,
)

/**
 * Un paso observado. `POST /api/v1/learning/sessions/:id/steps`. El MISMO shape que manda la extensión
 * de Chrome desde el DOM y Windows desde UIA/SAP: Graph no sabe qué superficie hay al otro lado.
 */
@Serializable
class StepRequest(
    /**
     * `input` · `select` · `click` · `navigation`: el vocabulario de Graph (`WorkflowExecutor` descarta del
     * plan cualquier otro). `key` y `scroll` viajan como texto libre: qué hace Graph con ellos está en
     * los supuestos sin verificar de la spec 004.
     */
    val actionType: String,
    /** Selector re-ejecutable en esta superficie; para Graph, opaco. */
    val selector: String,
    /** Solo en `navigation`: sin url, Graph no considera ejecutable el paso. */
    val url: String? = null,
    val label: String,
    /** text · textarea · select · checkbox · radio · button · date … */
    val controlType: String,
    val value: String? = null,
    val explanation: String? = null,
    val selectedValue: String? = null,
    val selectedLabel: String? = null,
    val allowedOptions: List<FieldOption>? = null,
    val semanticTarget: String? = null,
    val surfaceSection: String? = null,
    /** La bolsa libre de la superficie; se arma con [SurfaceHint.build]. Graph la devuelve intacta en el plan. */
    val surfaceHints: JsonObject? = null,
)

@Serializable
class FieldOption(
    val value: String,
    val label: String,
    /** `Step.js` normaliza value · label · text. */
    val text: String? = null,
)

/**
 * Las pistas de superficie de un paso. `alternativeTargets` tiene significado YA establecido en Graph
 * (`WorkflowExecutionGuideBuilder` la lee como `string[]` de selectores de respaldo): viaja como lista.
 * El resto son textos que solo entiende esta superficie y Graph pasa opacos.
 */
object SurfaceHint {
    const val ALTERNATIVE_TARGETS = "alternativeTargets"
    /** `android://paquete/Activity`: el nodo del paso, para reanudar. */
    const val OBSERVED_SURFACE = "observedSurface"
    /** Cuántos elementos interactivos había listos al grabar. */
    const val READINESS = "readiness"
    /** Huella estructural: hash de los ids interactivos, nunca de valores. */
    const val FINGERPRINT = "fingerprint"
    /** `"relX,relY"`: respaldo por posición si el elemento no resuelve. */
    const val CLICK_POS = "clickPos"
    const val NODE_PATH = "nodePath"

    /** Como `BuildHints` de Windows: lo vacío no viaja, y sin nada que decir, `null` (`surfaceHints` no viaja). */
    fun build(alternativeTargets: List<String> = emptyList(), pistas: Map<String, String> = emptyMap()): JsonObject? {
        val hints = buildMap<String, JsonElement> {
            alternativeTargets.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.let { put(ALTERNATIVE_TARGETS, JsonArray(it.map { t -> JsonPrimitive(t) })) }
            pistas.forEach { (k, v) -> if (k != ALTERNATIVE_TARGETS && v.isNotBlank()) put(k, JsonPrimitive(v)) }
        }
        return if (hints.isEmpty()) null else JsonObject(hints)
    }
}

@Serializable
class StepResponse(
    val step: StepInfo? = null,
    @Serializable(with = VacioEsAusente::class) val error: String? = null,
)

@Serializable
class StepInfo(@SerialName("step_order") val stepOrder: Int = 0)

/** `POST /api/v1/learning/sessions/:id/context-notes`. */
@Serializable
class ContextNoteRequest(val note: ContextNote)

/** `role` y `mode` viajan siempre: sin default a propósito (ver la cabecera del archivo). */
@Serializable
class ContextNote(val role: String, val transcript: String, val mode: String) {
    companion object {
        /** La nota de lo que el usuario explica de viva voz mientras enseña, como la manda Windows. */
        fun deEntrenamiento(transcript: String) = ContextNote(role = "clinical_context", transcript = transcript, mode = "training")
    }
}

/** `POST /api/v1/learning/sessions/:id/finish` (cuerpo `{}`). */
@Serializable
class FinishResponse(
    @SerialName("workflow_id") @Serializable(with = VacioEsAusente::class) val workflowId: String? = null,
    @Serializable(with = VacioEsAusente::class) val summary: String? = null,
    /** El workflow persistido. Shape libre de Graph: no se modela, se pasa tal cual. */
    @Serializable(with = JsonVacioEsAusente::class) val workflow: JsonElement? = null,
    @Serializable(with = VacioEsAusente::class) val error: String? = null,
)

/* ────────────────────────── Ejecución (workflows) ────────────────────────── */

/** `GET /api/v1/workflows`. Cada workflow llega crudo; lo lee [WorkflowResumen.desdeJson]. */
@Serializable
class WorkflowListResponse(
    val workflows: List<JsonElement> = emptyList(),
    @Serializable(with = VacioEsAusente::class) val error: String? = null,
)

/** `POST /api/v1/workflows/:id/plan`. */
@Serializable
class PlanRequest(
    val variables: Map<String, String>,
    @SerialName("execution_intent") val executionIntent: Map<String, String>,
)

@Serializable
class PlanResponse(
    @SerialName("execution_plan") val executionPlan: ExecutionPlan? = null,
    @Serializable(with = VacioEsAusente::class) val error: String? = null,
)

/** El plan de `WorkflowExecutor.buildExecutionPlan`. Se modela lo que se usa y se deja pasar el resto. */
@Serializable
class ExecutionPlan(
    @Serializable(with = VacioEsAusente::class) val workflowId: String? = null,
    @Serializable(with = VacioEsAusente::class) val description: String? = null,
    @Serializable(with = VacioEsAusente::class) val appId: String? = null,
    /** Superficie donde se grabó: se compara con la actual antes de ejecutar a ciegas. */
    @Serializable(with = VacioEsAusente::class) val sourceUrl: String? = null,
    @Serializable(with = VacioEsAusente::class) val sourceOrigin: String? = null,
    @Serializable(with = VacioEsAusente::class) val sourcePathname: String? = null,
    @Serializable(with = VacioEsAusente::class) val sourceTitle: String? = null,
    /** Guía en texto que Graph construye para el runtime. Informativa. */
    @Serializable(with = VacioEsAusente::class) val executionGuide: String? = null,
    val variables: Map<String, String> = emptyMap(),
    /** Solo los pasos ejecutables: Graph ya filtró los que no lo son. */
    val steps: List<PlanStep> = emptyList(),
)

/** Espejo de `src/domain/entities/Step.js` (vía `Contracts.cs`). */
@Serializable
class PlanStep(
    val stepOrder: Int = 0,
    @Serializable(with = VacioEsAusente::class) val actionType: String? = null,
    @Serializable(with = VacioEsAusente::class) val selector: String? = null,
    @Serializable(with = VacioEsAusente::class) val url: String? = null,
    @Serializable(with = VacioEsAusente::class) val label: String? = null,
    @Serializable(with = VacioEsAusente::class) val controlType: String? = null,
    @Serializable(with = VacioEsAusente::class) val value: String? = null,
    @Serializable(with = VacioEsAusente::class) val explanation: String? = null,
    @Serializable(with = VacioEsAusente::class) val selectedValue: String? = null,
    @Serializable(with = VacioEsAusente::class) val selectedLabel: String? = null,
    val allowedOptions: List<FieldOption>? = null,
    @Serializable(with = VacioEsAusente::class) val semanticTarget: String? = null,
    @Serializable(with = VacioEsAusente::class) val surfaceSection: String? = null,
    /** Fila de un árbol (SAP GuiTree en Windows); duplicada en el selector. */
    @Serializable(with = VacioEsAusente::class) val nodeKey: String? = null,
    /** La ruta jerárquica de la fila; si no viene como campo, se busca en las pistas ([nodePathOrHint]). */
    @Serializable(with = VacioEsAusente::class) val nodePath: String? = null,
    /** Vuelve tal cual se mandó al grabar: de aquí salen superficie, huella, `clickPos` y respaldos. */
    @Serializable(with = JsonVacioEsAusente::class) val surfaceHints: JsonElement? = null,
    /** `fixed` (exacto, default) · `dynamic` (por contexto; [bindTo] lo ata a otra variable) · `flexible` (si no resuelve, se salta). */
    @Serializable(with = VacioEsAusente::class) val valueMode: String? = null,
    @Serializable(with = VacioEsAusente::class) val bindTo: String? = null,
) {
    /** La superficie donde se grabó el paso (`observedSurface`), o `null` en grabaciones viejas. */
    fun observedSurface(): String? = pista(SurfaceHint.OBSERVED_SURFACE).textoNoVacio()

    /** Elementos interactivos listos al grabar (número o texto); 0 = sin métrica. */
    fun readiness(): Int =
        (pista(SurfaceHint.READINESS) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.toIntOrNull() ?: 0

    /** La huella estructural de la pantalla al grabar, o `null`. */
    fun fingerprint(): String? = pista(SurfaceHint.FINGERPRINT).textoNoVacio()

    /** La posición del toque `"relX,relY"`, o `null` si no se grabó o no se lee. */
    fun clickPos(): Pair<Int, Int>? {
        val xy = pista(SurfaceHint.CLICK_POS).textoNoVacio()?.split(',') ?: return null
        if (xy.size != 2) return null
        val x = xy[0].trim().toIntOrNull() ?: return null
        val y = xy[1].trim().toIntOrNull() ?: return null
        return x to y
    }

    /** Los selectores de respaldo: solo textos no vacíos, en su orden. */
    fun alternativeTargets(): List<String> =
        (pista(SurfaceHint.ALTERNATIVE_TARGETS) as? JsonArray).orEmpty().mapNotNull { it.textoNoVacio() }

    /** El campo propio primero y, si no, `surfaceHints.nodePath`. */
    fun nodePathOrHint(): String? = nodePath ?: pista(SurfaceHint.NODE_PATH).textoNoVacio()

    private fun pista(nombre: String): JsonElement? = (surfaceHints as? JsonObject)?.get(nombre)
}

/* ────────────────────────── Enseñanza por video (/teach/…) ────────────────────────── */

/** `POST /api/v1/teach/upload-token`. */
@Serializable
class UploadTokenRequest(val contentLength: Long, val userId: String)

@Serializable
class UploadTokenResponse(
    @Serializable(with = VacioEsAusente::class) val geminiUploadUrl: String? = null,
    @Serializable(with = VacioEsAusente::class) val archiveUploadUrl: String? = null,
    @Serializable(with = VacioEsAusente::class) val archivePath: String? = null,
    @Serializable(with = VacioEsAusente::class) val archiveError: String? = null,
)

/** `POST /api/v1/teach/file-state`. */
@Serializable
class FileStateRequest(val fileUri: String)

@Serializable
class FileStateResponse(@Serializable(with = VacioEsAusente::class) val state: String? = null)

/** Un paso de la demo, tal como viaja a Graph. Los cuatro campos viajan siempre, también vacíos. */
@Serializable
class StepToRead(val order: Int, val field: String, val value: String, val said: String)

/** `POST /api/v1/teach/process-video`. [steps] `null` cuando no hay: el contrato viejo sigue vivo. */
@Serializable
class ProcessRequest(val fileUri: String, val userId: String, val steps: List<StepToRead>? = null)

@Serializable
class TeachNote(
    @Serializable(with = VacioEsAusente::class) val app: String? = null,
    @Serializable(with = VacioEsAusente::class) val note: String? = null,
)

@Serializable
class ProcessResult(
    @Serializable(with = VacioEsAusente::class) val summary: String? = null,
    /**
     * Lo que el modelo entendió: `{campos:[…], recuerdos:[…]}`. CRUDO a propósito: quien la entiende es
     * una pieza pura aparte; parsearla aquí sería un segundo lector del mismo hecho.
     */
    @Serializable(with = JsonVacioEsAusente::class) val interpretation: JsonElement? = null,
    val notes: List<TeachNote>? = null,
    val questions: List<String>? = null,
)

/** `POST /api/v1/teach/interpret-steps`: la demo sin video, solo pasos y narración. */
@Serializable
class InterpretRequest(val startsAt: String, val steps: List<StepToRead>)

@Serializable
class InterpretResult(@Serializable(with = JsonVacioEsAusente::class) val interpretation: JsonElement? = null)

/**
 * Un workflow de la lista, visto para elegirlo. Espejo de `WorkflowSummary.FromJson` de Windows: el shape
 * de `GET /api/v1/workflows` se midió contra el Graph vivo el 2026-09-02 (`id`, `description`,
 * `sourceOrigin`, `sourceTitle`, `totalSteps`, `createdAt` como entero Neo4j) y se tolera lo demás.
 */
class WorkflowResumen(
    val id: String,
    /** La descripción de Graph tal cual, o `null`; puede ser relleno (ver [nombre]). */
    val description: String?,
    val sourceOrigin: String?,
    val sourceTitle: String?,
    val totalSteps: Int,
    /** Milisegundos Unix, o `null` si Graph no mandó una fecha legible. */
    val creadoEnMs: Long?,
) {
    /**
     * Lo que se muestra: la descripción si es de verdad; si es relleno, lo que se sabe del workflow
     * (ver [NombreDeWorkflow]). [desfaseMs] da el desfase de la zona local para un instante.
     */
    fun nombre(desfaseMs: (Long) -> Long): String {
        val propia = description?.trim()
        if (propia != null && !NombreDeWorkflow.esRelleno(propia)) return propia
        return NombreDeWorkflow.derivar(NombreDeWorkflow.appDe(sourceOrigin), sourceTitle.orEmpty(), creadoEnMs, totalSteps, desfaseMs)
    }

    companion object {
        fun desdeJson(e: JsonElement): WorkflowResumen {
            val o = e as? JsonObject ?: JsonObject(emptyMap())
            fun texto(vararg claves: String) = claves.firstNotNullOfOrNull { o[it].textoNoVacio() }
            fun entero(vararg claves: String) = claves.firstNotNullOfOrNull {
                (o[it] as? JsonPrimitive)?.takeUnless { p -> p.isString || p is JsonNull }?.content?.toIntOrNull()
            }
            return WorkflowResumen(
                id = texto("id", "workflowId", "workflow_id").orEmpty(),
                description = texto("description", "title", "name"),
                sourceOrigin = texto("sourceOrigin", "source_origin"),
                sourceTitle = texto("sourceTitle", "source_title"),
                totalSteps = entero("totalSteps", "stepCount", "step_count") ?: (o["steps"] as? JsonArray)?.size ?: 0,
                creadoEnMs = creadoEn(o["createdAt"] ?: o["created_at"]),
            )
        }

        /**
         * El `createdAt` como llega de Graph: entero Neo4j `{low, high}` (lo normal) o un número llano de
         * milisegundos. Neo4j parte el int64 en dos int32: el valor es `high·2³² + low` con `low` SIN signo.
         */
        private fun creadoEn(v: JsonElement?): Long? = when (v) {
            is JsonObject -> {
                val low = (v["low"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
                val high = (v["high"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
                if (low == null || high == null) null else (high shl 32) or (low and 0xFFFFFFFFL)
            }
            is JsonNull -> null
            is JsonPrimitive -> if (v.isString) null else v.content.toLongOrNull()
            else -> null
        }
    }
}
