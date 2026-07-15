using System.Text.Json;
using System.Text.Json.Serialization;

namespace U.Graph;

// Espejo del contrato público de Graph: web/api/registerPublicApiRoutes.js (y docs/API.md).
// Graph acepta snake_case y camelCase en puntos críticos; aquí escribimos snake_case (lo que documenta)
// y al LEER toleramos ambos, porque las respuestas devuelven las dos formas en algunos campos.

// ─────────────────────────────────────────────────────────────────────────────
// Grabación (learning)
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>POST /api/v1/learning/sessions — abre una sesión de grabación.</summary>
public sealed class StartSessionRequest
{
    [JsonPropertyName("description")] public string Description { get; set; } = "";
    [JsonPropertyName("app_id")] public string AppId { get; set; } = "";

    /// <summary>
    /// Identidad de la superficie. En web es la URL; aquí la sintetizamos:
    /// <c>sapgui://SID/TCODE</c> o <c>uia://proceso.exe/ventana</c>. Ver SurfaceIdentity.
    /// </summary>
    [JsonPropertyName("source_url")] public string SourceUrl { get; set; } = "";
    [JsonPropertyName("source_origin")] public string SourceOrigin { get; set; } = "";
    [JsonPropertyName("source_pathname")] public string SourcePathname { get; set; } = "";
    [JsonPropertyName("source_title")] public string SourceTitle { get; set; } = "";
    [JsonPropertyName("context")] public Dictionary<string, string> Context { get; set; } = new();
}

public sealed class StartSessionResponse
{
    [JsonPropertyName("session")] public SessionInfo? Session { get; set; }
    [JsonPropertyName("error")] public string? Error { get; set; }
}

public sealed class SessionInfo
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("workflow_id")] public string WorkflowId { get; set; } = "";
    [JsonPropertyName("recording")] public bool Recording { get; set; }
}

/// <summary>
/// Un paso observado. POST /api/v1/learning/sessions/:id/steps.
/// Es el MISMO shape que manda la extensión de Chrome desde el DOM — de ahí que Graph no necesite
/// saber si al otro lado hay una página web, SAP GUI o una ventana Win32.
/// </summary>
public sealed class StepRequest
{
    /// <summary>
    /// input · select · click · navigation. Vocabulario cerrado de Graph: WorkflowExecutor descarta
    /// del plan cualquier step con otro actionType (isExecutableStep), así que no inventamos tipos.
    /// </summary>
    [JsonPropertyName("actionType")] public string ActionType { get; set; } = "";

    /// <summary>Selector re-ejecutable en esta superficie. Ver SurfaceSelector para el formato.</summary>
    [JsonPropertyName("selector")] public string Selector { get; set; } = "";

    /// <summary>Solo para actionType=navigation: sin url, Graph no considera ejecutable el step.</summary>
    [JsonPropertyName("url")] public string? Url { get; set; }

    /// <summary>Texto visible o nombre semántico del campo.</summary>
    [JsonPropertyName("label")] public string Label { get; set; } = "";

    /// <summary>text · textarea · select · checkbox · radio · button · date …</summary>
    [JsonPropertyName("controlType")] public string ControlType { get; set; } = "";

    [JsonPropertyName("value")] public string? Value { get; set; }
    [JsonPropertyName("explanation")] public string? Explanation { get; set; }

    /// <summary>Para selects: qué opción quedó elegida (value interno) y su texto visible.</summary>
    [JsonPropertyName("selectedValue")] public string? SelectedValue { get; set; }
    [JsonPropertyName("selectedLabel")] public string? SelectedLabel { get; set; }

    [JsonPropertyName("allowedOptions")] public List<FieldOption>? AllowedOptions { get; set; }
    [JsonPropertyName("semanticTarget")] public string? SemanticTarget { get; set; }
    [JsonPropertyName("surfaceSection")] public string? SurfaceSection { get; set; }

    /// <summary>
    /// Bolsa libre de metadatos de superficie. Graph la persiste como JSON y la devuelve intacta en el
    /// plan, así que es el sitio correcto para lo que solo entiende esta superficie (el Id de SAP, el
    /// AutomationId/RuntimeId de UIA, la clase de ventana…).
    ///
    /// Una clave tiene significado YA establecido en Graph: <c>alternativeTargets</c> (string[]), que
    /// WorkflowExecutionGuideBuilder y WorkflowDecisionNormalizer leen como selectores de respaldo.
    /// La usamos con ese mismo sentido — ver SurfaceHints.
    /// </summary>
    [JsonPropertyName("surfaceHints")] public Dictionary<string, object>? SurfaceHints { get; set; }
}

public sealed class FieldOption
{
    [JsonPropertyName("value")] public string Value { get; set; } = "";
    [JsonPropertyName("label")] public string Label { get; set; } = "";
    /// <summary>Step.js normaliza value · label · text. En SAP el texto visible suele diferir del Key.</summary>
    [JsonPropertyName("text")] public string? Text { get; set; }
}

public sealed class StepResponse
{
    [JsonPropertyName("step")] public StepInfo? Step { get; set; }
    [JsonPropertyName("error")] public string? Error { get; set; }
}

public sealed class StepInfo
{
    [JsonPropertyName("step_order")] public int StepOrder { get; set; }
}

/// <summary>POST /api/v1/learning/sessions/:id/context-notes.</summary>
public sealed class ContextNoteRequest
{
    [JsonPropertyName("note")] public ContextNote Note { get; set; } = new();
}

public sealed class ContextNote
{
    [JsonPropertyName("role")] public string Role { get; set; } = "clinical_context";
    [JsonPropertyName("transcript")] public string Transcript { get; set; } = "";
    [JsonPropertyName("mode")] public string Mode { get; set; } = "training";
}

/// <summary>POST /api/v1/learning/sessions/:id/finish.</summary>
public sealed class FinishResponse
{
    [JsonPropertyName("workflow_id")] public string WorkflowId { get; set; } = "";
    [JsonPropertyName("summary")] public string Summary { get; set; } = "";
    /// <summary>El workflow persistido. Shape libre de Graph: no lo modelamos, lo pasamos tal cual.</summary>
    [JsonPropertyName("workflow")] public JsonElement? Workflow { get; set; }
    [JsonPropertyName("error")] public string? Error { get; set; }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ejecución (workflows)
// ─────────────────────────────────────────────────────────────────────────────

public sealed class WorkflowListResponse
{
    [JsonPropertyName("workflows")] public List<JsonElement> Workflows { get; set; } = new();
    [JsonPropertyName("error")] public string? Error { get; set; }
}

/// <summary>POST /api/v1/workflows/:id/plan.</summary>
public sealed class PlanRequest
{
    [JsonPropertyName("variables")] public Dictionary<string, string> Variables { get; set; } = new();
    [JsonPropertyName("execution_intent")] public Dictionary<string, string> ExecutionIntent { get; set; } = new();
}

public sealed class PlanResponse
{
    [JsonPropertyName("execution_plan")] public ExecutionPlan? ExecutionPlan { get; set; }
    [JsonPropertyName("error")] public string? Error { get; set; }
}

/// <summary>
/// El plan que devuelve WorkflowExecutor.buildExecutionPlan. Modelamos lo que usamos y dejamos pasar
/// el resto: el planner puede añadir campos y este cliente no debe romperse por eso.
/// </summary>
public sealed class ExecutionPlan
{
    [JsonPropertyName("workflowId")] public string WorkflowId { get; set; } = "";
    [JsonPropertyName("description")] public string Description { get; set; } = "";
    [JsonPropertyName("appId")] public string AppId { get; set; } = "";

    /// <summary>Superficie donde se grabó. La comparamos con la actual antes de ejecutar a ciegas.</summary>
    [JsonPropertyName("sourceUrl")] public string SourceUrl { get; set; } = "";
    [JsonPropertyName("sourceOrigin")] public string SourceOrigin { get; set; } = "";
    [JsonPropertyName("sourcePathname")] public string SourcePathname { get; set; } = "";
    [JsonPropertyName("sourceTitle")] public string SourceTitle { get; set; } = "";

    /// <summary>Guía en texto que Graph construye para el runtime. Informativa para el operador.</summary>
    [JsonPropertyName("executionGuide")] public string ExecutionGuide { get; set; } = "";

    [JsonPropertyName("variables")] public Dictionary<string, string> Variables { get; set; } = new();

    /// <summary>Solo los steps ejecutables: Graph ya filtró los que no lo son.</summary>
    [JsonPropertyName("steps")] public List<PlanStep> Steps { get; set; } = new();
}

/// <summary>Espejo de src/domain/entities/Step.js.</summary>
public sealed class PlanStep
{
    [JsonPropertyName("stepOrder")] public int StepOrder { get; set; }
    [JsonPropertyName("actionType")] public string ActionType { get; set; } = "";
    [JsonPropertyName("selector")] public string Selector { get; set; } = "";
    [JsonPropertyName("url")] public string? Url { get; set; }
    [JsonPropertyName("label")] public string? Label { get; set; }
    [JsonPropertyName("controlType")] public string? ControlType { get; set; }
    [JsonPropertyName("value")] public string? Value { get; set; }
    [JsonPropertyName("explanation")] public string? Explanation { get; set; }
    [JsonPropertyName("selectedValue")] public string? SelectedValue { get; set; }
    [JsonPropertyName("selectedLabel")] public string? SelectedLabel { get; set; }
    [JsonPropertyName("allowedOptions")] public List<FieldOption>? AllowedOptions { get; set; }
    [JsonPropertyName("semanticTarget")] public string? SemanticTarget { get; set; }
    [JsonPropertyName("surfaceSection")] public string? SurfaceSection { get; set; }

    /// <summary>Vuelve tal cual la mandamos al grabar. De aquí salen los alternativeTargets.</summary>
    [JsonPropertyName("surfaceHints")] public JsonElement? SurfaceHints { get; set; }

    /// <summary>Selectores de respaldo si el principal no resuelve. Convención de Graph.</summary>
    public IReadOnlyList<string> AlternativeTargets()
    {
        if (SurfaceHints is not { ValueKind: JsonValueKind.Object } hints) return Array.Empty<string>();
        if (!hints.TryGetProperty("alternativeTargets", out var alts)) return Array.Empty<string>();
        if (alts.ValueKind != JsonValueKind.Array) return Array.Empty<string>();
        return alts.EnumerateArray()
                   .Where(e => e.ValueKind == JsonValueKind.String)
                   .Select(e => e.GetString()!)
                   .Where(s => !string.IsNullOrWhiteSpace(s))
                   .ToList();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Autofill (POST /api/v1/autofill/match)
// ─────────────────────────────────────────────────────────────────────────────

/// <summary>Un campo detectado en la superficie, tal como Graph espera recibirlo.</summary>
public sealed class DetectedField
{
    [JsonPropertyName("stepOrder")] public int StepOrder { get; set; }
    [JsonPropertyName("actionType")] public string ActionType { get; set; } = "";
    [JsonPropertyName("selector")] public string Selector { get; set; } = "";
    [JsonPropertyName("label")] public string Label { get; set; } = "";
    [JsonPropertyName("controlType")] public string ControlType { get; set; } = "";
    [JsonPropertyName("allowedOptions")] public List<FieldOption>? AllowedOptions { get; set; }
    [JsonPropertyName("currentValue")] public string? CurrentValue { get; set; }
}

public sealed class AutofillRequest
{
    [JsonPropertyName("session_id")] public string SessionId { get; set; } = "";
    [JsonPropertyName("page_url")] public string PageUrl { get; set; } = "";
    [JsonPropertyName("note_content")] public string NoteContent { get; set; } = "";
    [JsonPropertyName("fields")] public List<DetectedField> Fields { get; set; } = new();
    [JsonPropertyName("already_fulfilled")] public List<FulfilledField> AlreadyFulfilled { get; set; } = new();
}

public sealed class FulfilledField
{
    [JsonPropertyName("stepOrder")] public int StepOrder { get; set; }
    [JsonPropertyName("value")] public string Value { get; set; } = "";
}

public sealed class AutofillResponse
{
    [JsonPropertyName("autofill")] public AutofillResult? Autofill { get; set; }
    [JsonPropertyName("error")] public string? Error { get; set; }
}

public sealed class AutofillResult
{
    [JsonPropertyName("matches")] public List<FieldMatch> Matches { get; set; } = new();
    [JsonPropertyName("ready_to_submit")] public bool ReadyToSubmit { get; set; }
    [JsonPropertyName("submit_reason")] public string SubmitReason { get; set; } = "";
}

public sealed class FieldMatch
{
    [JsonPropertyName("stepOrder")] public int StepOrder { get; set; }
    [JsonPropertyName("value")] public string Value { get; set; } = "";
    [JsonPropertyName("confidence")] public double Confidence { get; set; }
    [JsonPropertyName("evidence")] public string Evidence { get; set; } = "";
}
