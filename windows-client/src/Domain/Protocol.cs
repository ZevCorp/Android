using System.Text.Json.Serialization;

namespace U.WindowsClient.Domain;

// Tipos del contrato con el backend. Espejo EXACTO de backend/src/domain/actions.ts y session.ts.
// El cliente solo conoce estos tipos del cerebro; nada más cruza la frontera.

/// <summary>Estado de pantalla que el cliente captura y envía cada turno.</summary>
public sealed class ScreenState
{
    [JsonPropertyName("screen")] public string Screen { get; set; } = "";
    [JsonPropertyName("uiContext")] public string UiContext { get; set; } = "";
    [JsonPropertyName("width")] public int Width { get; set; }
    [JsonPropertyName("height")] public int Height { get; set; }
    /// <summary>PNG en base64 SIN prefijo data-uri. Solo cuando el turno anterior pidió computer-use.</summary>
    [JsonPropertyName("screenshot")] public string? Screenshot { get; set; }
    /// <summary>Apps instaladas conocidas (resuelve list_apps y alimenta el prompt del cerebro).</summary>
    [JsonPropertyName("apps")] public string[]? Apps { get; set; }
}

/// <summary>Petición a POST /api/agent/turn.</summary>
public sealed class TurnRequest
{
    /// <summary>Blob opaco del turno anterior. Null en el primer turno.</summary>
    [JsonPropertyName("session")] public string? Session { get; set; }
    /// <summary>Objetivo del usuario. Solo en el primer turno.</summary>
    [JsonPropertyName("goal")] public string? Goal { get; set; }
    [JsonPropertyName("userId")] public string? UserId { get; set; }
    [JsonPropertyName("state")] public ScreenState State { get; set; } = new();
    /// <summary>Resultados de las acciones del turno anterior (mismo orden).</summary>
    [JsonPropertyName("results")] public string[] Results { get; set; } = Array.Empty<string>();
    /// <summary>Respuesta del usuario a una pregunta (ask_user) del turno anterior. Null si no hubo.</summary>
    [JsonPropertyName("inform")] public string? Inform { get; set; }
}

/// <summary>
/// Una acción decidida por el cerebro que el cliente ejecuta localmente. Unión discriminada por
/// <see cref="Kind"/>: computer-use (coordenadas de pantalla real) o llamada MCP (por nombre).
/// </summary>
public sealed class AgentAction
{
    [JsonPropertyName("kind")] public string Kind { get; set; } = "";
    [JsonPropertyName("x")] public int X { get; set; }
    [JsonPropertyName("y")] public int Y { get; set; }
    [JsonPropertyName("x1")] public int X1 { get; set; }
    [JsonPropertyName("y1")] public int Y1 { get; set; }
    [JsonPropertyName("x2")] public int X2 { get; set; }
    [JsonPropertyName("y2")] public int Y2 { get; set; }
    [JsonPropertyName("ms")] public int Ms { get; set; }
    [JsonPropertyName("text")] public string? Text { get; set; }
    [JsonPropertyName("key")] public string? Key { get; set; }
    [JsonPropertyName("down")] public bool Down { get; set; }
    [JsonPropertyName("tool")] public string? Tool { get; set; }
    [JsonPropertyName("args")] public Dictionary<string, string>? Args { get; set; }
}

/// <summary>Respuesta de POST /api/agent/turn: BrainTurn + la sesión opaca actualizada.</summary>
public sealed class TurnResponse
{
    [JsonPropertyName("session")] public string Session { get; set; } = "";
    [JsonPropertyName("actions")] public List<AgentAction> Actions { get; set; } = new();
    [JsonPropertyName("question")] public string? Question { get; set; }
    [JsonPropertyName("done")] public bool Done { get; set; }
    [JsonPropertyName("text")] public string Text { get; set; } = "";
    [JsonPropertyName("needsScreenshot")] public bool NeedsScreenshot { get; set; }
    [JsonPropertyName("narration")] public string Narration { get; set; } = "";
    [JsonPropertyName("speech")] public string? Speech { get; set; }
    [JsonPropertyName("intents")] public List<string> Intents { get; set; } = new();
    [JsonPropertyName("error")] public string? Error { get; set; }
}
