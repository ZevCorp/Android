using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace U.Graph;

/// <summary>Falla del backend Graph, ya con el mensaje que Graph devolvió en <c>error</c>.</summary>
public sealed class GraphException : Exception
{
    public int StatusCode { get; }
    public GraphException(string message, int statusCode = 0) : base(message) => StatusCode = statusCode;
}

/// <summary>
/// El único puente con Graph. Habla EXACTAMENTE el contrato público /api/v1 documentado en
/// docs/API.md del repo Graph — ni una ruta interna (/api/workflows sin versionar) se toca desde aquí:
/// esas son del dashboard de Graph y pueden cambiar sin aviso.
///
/// Graph decide, este cliente ejecuta. El backend nunca toca la UI del cliente: devuelve pasos y
/// matches, y quien los aplica sobre SAP GUI o UIA es este proceso.
/// </summary>
public sealed class GraphClient
{
    private readonly HttpClient _http;
    private readonly GraphConfig _config;

    private static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    public GraphClient(GraphConfig config, HttpClient? http = null)
    {
        _config = config;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(90) };
        _http.DefaultRequestHeaders.Remove("X-API-Key");
        if (!string.IsNullOrWhiteSpace(config.ApiKey))
            _http.DefaultRequestHeaders.Add("X-API-Key", config.ApiKey);
    }

    private string Url(string path) => $"{_config.BaseUrl.TrimEnd('/')}{path}";

    // ── Grabación ────────────────────────────────────────────────────────────

    /// <summary>Abre la sesión de grabación. Devuelve el id, que ES el workflowId.</summary>
    public async Task<string> StartSessionAsync(StartSessionRequest req, CancellationToken ct)
    {
        var res = await PostAsync<StartSessionResponse>("/api/v1/learning/sessions", req, ct);
        string id = res.Session?.Id ?? "";
        if (string.IsNullOrWhiteSpace(id)) throw new GraphException("Graph no devolvió un id de sesión.");
        return id;
    }

    /// <summary>
    /// Registra un paso observado. El id de sesión va SIEMPRE en la ruta, nunca implícito.
    ///
    /// Es deliberado: Graph guarda las sesiones activas en memoria (LearningSessionService) y corre en
    /// serverless, así que la "sesión activa" del servidor no sobrevive de forma fiable entre
    /// peticiones. Con el id explícito, la grabación funciona aunque la instancia sea otra.
    /// </summary>
    public async Task<int> RecordStepAsync(string sessionId, StepRequest step, CancellationToken ct)
    {
        var res = await PostAsync<StepResponse>($"/api/v1/learning/sessions/{Escape(sessionId)}/steps", step, ct);
        return res.Step?.StepOrder ?? 0;
    }

    public async Task AddContextNoteAsync(string sessionId, string transcript, CancellationToken ct)
    {
        var body = new ContextNoteRequest { Note = new ContextNote { Transcript = transcript } };
        await PostAsync<JsonElement>($"/api/v1/learning/sessions/{Escape(sessionId)}/context-notes", body, ct);
    }

    /// <summary>Cierra la sesión: Graph post-procesa y persiste el workflow en Neo4j.</summary>
    public async Task<FinishResponse> FinishSessionAsync(string sessionId, CancellationToken ct) =>
        await PostAsync<FinishResponse>($"/api/v1/learning/sessions/{Escape(sessionId)}/finish", new { }, ct);

    // ── Ejecución ────────────────────────────────────────────────────────────

    /// <summary>Workflows privados de esta API key + los globales.</summary>
    public async Task<List<JsonElement>> ListWorkflowsAsync(CancellationToken ct)
    {
        var res = await GetAsync<WorkflowListResponse>("/api/v1/workflows", ct);
        return res.Workflows;
    }

    /// <summary>Pide el plan ejecutable. Graph ya filtra los steps no ejecutables.</summary>
    public async Task<ExecutionPlan> GetPlanAsync(
        string workflowId, Dictionary<string, string>? variables, Dictionary<string, string>? intent,
        CancellationToken ct)
    {
        var body = new PlanRequest
        {
            Variables = variables ?? new(),
            ExecutionIntent = intent ?? new(),
        };
        var res = await PostAsync<PlanResponse>($"/api/v1/workflows/{Escape(workflowId)}/plan", body, ct);
        if (res.ExecutionPlan == null) throw new GraphException("Graph no devolvió un plan de ejecución.");
        return res.ExecutionPlan;
    }

    // ── Autofill ─────────────────────────────────────────────────────────────

    /// <summary>Mapea una nota organizada contra los campos detectados en la superficie.</summary>
    public async Task<AutofillResult> MatchAsync(AutofillRequest req, CancellationToken ct)
    {
        var res = await PostAsync<AutofillResponse>("/api/v1/autofill/match", req, ct);
        return res.Autofill ?? new AutofillResult();
    }

    // ── Diagnóstico ──────────────────────────────────────────────────────────

    /// <summary>
    /// GET /api/v1: el manifiesto de capacidades. Sirve de prueba de vida y de key: si responde 200,
    /// la URL y la API key son correctas. Úsalo al instalar en la máquina del cliente.
    /// </summary>
    public async Task<JsonElement> ManifestAsync(CancellationToken ct) =>
        await GetAsync<JsonElement>("/api/v1", ct);

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private static string Escape(string segment) => Uri.EscapeDataString(segment ?? "");

    private async Task<T> GetAsync<T>(string path, CancellationToken ct)
    {
        using var res = await SendAsync(() => new HttpRequestMessage(HttpMethod.Get, Url(path)), ct);
        return await ReadAsync<T>(res, ct);
    }

    private async Task<T> PostAsync<T>(string path, object body, CancellationToken ct)
    {
        string payload = JsonSerializer.Serialize(body, Json);
        using var res = await SendAsync(() => new HttpRequestMessage(HttpMethod.Post, Url(path))
        {
            Content = new StringContent(payload, Encoding.UTF8, "application/json"),
        }, ct);
        return await ReadAsync<T>(res, ct);
    }

    private async Task<HttpResponseMessage> SendAsync(Func<HttpRequestMessage> build, CancellationToken ct)
    {
        if (!_config.IsConfigured)
            throw new GraphException("Graph no está configurado: falta la URL o la API key.");
        try
        {
            return await _http.SendAsync(build(), ct);
        }
        catch (OperationCanceledException) { throw; }
        catch (Exception e)
        {
            throw new GraphException($"No se pudo contactar con Graph: {e.Message}");
        }
    }

    private static async Task<T> ReadAsync<T>(HttpResponseMessage res, CancellationToken ct)
    {
        string text = await res.Content.ReadAsStringAsync(ct);

        if (!res.IsSuccessStatusCode)
            throw new GraphException(ErrorFrom(text, res.StatusCode), (int)res.StatusCode);

        try
        {
            var parsed = JsonSerializer.Deserialize<T>(text, Json);
            if (parsed == null) throw new GraphException("Graph devolvió una respuesta vacía.");
            return parsed;
        }
        catch (JsonException e)
        {
            throw new GraphException($"Graph devolvió un JSON inesperado: {e.Message}");
        }
    }

    /// <summary>Graph reporta los fallos como <c>{"error": "..."}</c>; si no, nos quedamos con el status.</summary>
    private static string ErrorFrom(string body, System.Net.HttpStatusCode status)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind == JsonValueKind.Object &&
                doc.RootElement.TryGetProperty("error", out var err) &&
                err.ValueKind == JsonValueKind.String)
            {
                return $"{err.GetString()} (HTTP {(int)status})";
            }
        }
        catch { /* no era JSON: caemos al genérico */ }

        return status == System.Net.HttpStatusCode.Unauthorized || status == System.Net.HttpStatusCode.Forbidden
            ? $"Graph rechazó la API key (HTTP {(int)status})."
            : $"Graph respondió HTTP {(int)status}.";
    }
}
