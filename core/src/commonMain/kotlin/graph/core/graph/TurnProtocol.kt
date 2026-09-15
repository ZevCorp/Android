package graph.core.graph

import graph.core.domain.AgentAction
import graph.core.domain.BrainTurn
import graph.core.domain.ScreenState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Contrato JSON con Graph (`POST /api/v1/agent/turn`). Espejo EXACTO de
 * `U-Windows-App/windows-client/src/Domain/Protocol.cs`: los nombres de campo son los de allá, tal cual.
 * El cliente solo conoce estos tipos del cerebro; nada más cruza la frontera.
 *
 * Los campos obligatorios NO tienen valor por defecto a propósito: con `encodeDefaults = false` un
 * campo igual a su default se omite, y `results: []` o `width: 0` tienen que viajar siempre.
 */

/** Estado de pantalla que el cliente captura y manda cada turno. */
@Serializable
class TurnScreenState(
    val screen: String,
    val uiContext: String,
    val width: Int,
    val height: Int,
    /** PNG en base64 SIN prefijo data-uri. Solo cuando el turno anterior pidió computer-use. */
    val screenshot: String? = null,
    /** Apps instaladas conocidas (resuelve list_apps y alimenta el prompt del cerebro). */
    val apps: List<String>? = null,
    /** Dónde está parado el usuario (ver [AndroidSurface]); con esto Graph scopea los workflows por MCP. */
    val surfaceId: String? = null,
    val surfaceOrigin: String? = null,
    val surfacePathname: String? = null,
)

/** Petición a `POST /api/v1/agent/turn`. Solo estos seis campos: el cliente no manda modelo, prompt ni herramientas. */
@Serializable
class TurnRequest(
    /** Blob opaco del turno anterior. Null en el primer turno. */
    val session: String? = null,
    /** Objetivo del usuario. Solo en el primer turno. */
    val goal: String? = null,
    val userId: String? = null,
    val state: TurnScreenState,
    /** Resultados de las acciones del turno anterior (mismo orden). */
    val results: List<String>,
    /** Respuesta del usuario a una pregunta (ask_user) del turno anterior. Null si no hubo. */
    val inform: String? = null,
)

/** Una acción decidida por Graph. Unión discriminada por [kind]: computer-use por coordenadas o llamada MCP por nombre. */
@Serializable
class TurnAction(
    val kind: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val x1: Int = 0,
    val y1: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
    val ms: Int = 0,
    val text: String? = null,
    val key: String? = null,
    val down: Boolean = false,
    val tool: String? = null,
    val args: Map<String, String>? = null,
)

/** Respuesta de `POST /api/v1/agent/turn`: un BrainTurn más la sesión opaca actualizada. */
@Serializable
class TurnResponse(
    val session: String = "",
    val actions: List<TurnAction> = emptyList(),
    val question: String? = null,
    val done: Boolean = false,
    val text: String = "",
    val needsScreenshot: Boolean = false,
    val narration: String = "",
    val speech: String? = null,
    val intents: List<String> = emptyList(),
    /** Si Graph falló, el texto del fallo; el turno termina con él. */
    val error: String? = null,
)

/** El Json del contrato: no manda nulos ni defaults, y un campo nuevo de Graph no lo rompe. */
val TurnJson: Json = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = true
}

/** Traduce una acción de Graph a la acción local equivalente; un `kind` desconocido queda como [AgentAction.Unknown]. */
fun TurnAction.toAgentAction(): AgentAction = when (kind) {
    "tap" -> AgentAction.Tap(x, y)
    "type" -> AgentAction.Type(x, y, text ?: "")
    "scroll" -> AgentAction.Scroll(down)
    "swipe" -> AgentAction.Swipe(x1, y1, x2, y2, ms.toLong())
    "key" -> AgentAction.Key(key ?: "")
    "wait" -> AgentAction.Wait(ms.toLong())
    "mcp" -> AgentAction.Mcp(tool ?: "", args ?: emptyMap())
    else -> AgentAction.Unknown(kind)
}

/** El turno tal cual lo entiende el motor: acciones traducidas y el resto de campos sin tocar. */
fun TurnResponse.toBrainTurn(): BrainTurn = BrainTurn(
    actions = actions.map { it.toAgentAction() },
    question = question,
    done = done,
    text = text,
    needsScreenshot = needsScreenshot,
    narration = narration,
    speech = speech,
    intents = intents,
)

/**
 * El estado local en el formato del contrato. `withScreenshot` decide si el PNG viaja (base64, sin
 * prefijo data-uri): el cerebro solo lo adjunta cuando el turno anterior lo pidió.
 */
@OptIn(ExperimentalEncodingApi::class)
fun ScreenState.toTurnState(
    apps: List<String>?,
    surface: Surface?,
    withScreenshot: Boolean = true,
): TurnScreenState = TurnScreenState(
    screen = screen,
    uiContext = uiContext,
    width = width,
    height = height,
    screenshot = screenshotPng?.takeIf { withScreenshot }?.let { Base64.encode(it) },
    apps = apps,
    surfaceId = surface?.id,
    surfaceOrigin = surface?.origin,
    surfacePathname = surface?.pathname,
)
