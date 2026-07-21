using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using U.Graph;
using U.WindowsClient.Domain;

namespace U.WindowsClient.Backend;

/// <summary>
/// El único puente con el cerebro: <c>POST {BackendUrl}/api/v1/agent/turn</c> (Graph, el backend
/// central) más las rutas de enseñanza <c>/api/v1/teach/*</c>. El cliente manda el estado de pantalla
/// y recibe las acciones a ejecutar. No hay otra llamada de red con inteligencia: si este endpoint no
/// responde, el cliente no sabe pensar por su cuenta — a propósito.
///
/// La credencial es la MISMA que usa el módulo de workflows (windows-graph): la X-API-Key de
/// <see cref="GraphConfig"/> (%APPDATA%\U\graph.json o env GRAPH_API_KEY). Una sola fuente a
/// propósito: dos keys para el mismo backend era justo el lío que el port a Graph vino a eliminar.
///
/// Compatibilidad de emergencia: si <see cref="Config.BackendUrl"/> apunta de vuelta al backend viejo
/// (u-windows-backend, vía env U_BACKEND_URL o el panel Backend), este cliente vuelve solo al
/// contrato viejo — prefijo <c>/api</c> sin versionar y <c>Authorization: Bearer ClientToken</c> —
/// para que la vuelta atrás no requiera recompilar.
/// </summary>
public sealed class BackendClient
{
    private readonly HttpClient _http;
    private readonly string _baseUrl;
    private readonly string _userId;

    /// <summary>"/api/v1" contra Graph, "/api" contra el backend viejo. Ver comentario de la clase.</summary>
    private readonly string _apiPrefix;
    private readonly bool _legacy;

    private static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    public BackendClient(Config config, GraphConfig graphConfig)
    {
        _baseUrl = config.BackendUrl.TrimEnd('/');
        _userId = config.UserId;
        _http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };

        // La detección por host es deliberadamente tonta: el modo legacy existe SOLO para volver al
        // backend viejo en emergencia, y ese backend tiene un único dominio conocido.
        _legacy = _baseUrl.Contains("u-windows-backend", StringComparison.OrdinalIgnoreCase);
        _apiPrefix = _legacy ? "/api" : "/api/v1";

        if (_legacy)
        {
            // Contrato viejo: Bearer con el ClientToken de config.json.
            if (!string.IsNullOrWhiteSpace(config.ClientToken))
                _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", config.ClientToken);
        }
        else if (!string.IsNullOrWhiteSpace(graphConfig.ApiKey))
        {
            // Contrato Graph: la misma X-API-Key (miracle_…) que ya usa windows-graph.
            _http.DefaultRequestHeaders.Add("X-API-Key", graphConfig.ApiKey);
        }
    }

    public async Task<TurnResponse> TurnAsync(TurnRequest req, CancellationToken ct)
    {
        req.UserId = _userId;
        var body = JsonSerializer.Serialize(req, Json);
        using var content = new StringContent(body, Encoding.UTF8, "application/json");
        using var res = await _http.PostAsync($"{_baseUrl}{_apiPrefix}/agent/turn", content, ct);
        var text = await res.Content.ReadAsStringAsync(ct);
        if (IsAuthFailure(res.StatusCode))
            throw new InvalidOperationException(AuthErrorMessage(res.StatusCode));
        var parsed = JsonSerializer.Deserialize<TurnResponse>(text, Json);
        if (parsed == null) throw new InvalidOperationException($"respuesta vacía del backend (HTTP {(int)res.StatusCode})");
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException(parsed.Error ?? $"backend HTTP {(int)res.StatusCode}");
        return parsed;
    }

    /// <summary>
    /// POST genérico hacia cualquier endpoint del backend que devuelva JSON tipado. El <paramref name="path"/>
    /// va SIN el prefijo de API (p.ej. <c>/teach/upload-token</c>): el prefijo lo pone este cliente,
    /// porque es lo único que cambia entre Graph (/api/v1) y el backend viejo (/api).
    /// </summary>
    public async Task<T?> PostAsync<T>(string path, object req, CancellationToken ct) where T : class
    {
        var body = JsonSerializer.Serialize(req, Json);
        using var content = new StringContent(body, Encoding.UTF8, "application/json");
        using var res = await _http.PostAsync($"{_baseUrl}{_apiPrefix}{path}", content, ct);
        var text = await res.Content.ReadAsStringAsync(ct);
        if (IsAuthFailure(res.StatusCode))
            throw new InvalidOperationException(AuthErrorMessage(res.StatusCode));
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException($"backend HTTP {(int)res.StatusCode}: {text}");
        return JsonSerializer.Deserialize<T>(text, Json);
    }

    private static bool IsAuthFailure(HttpStatusCode status) =>
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden;

    /// <summary>
    /// Un 401/403 ya no significa "ClientToken malo": desde el port a Graph, la causa típica es que la
    /// máquina no tiene la API key de Graph configurada. El mensaje le dice al usuario exactamente
    /// dónde ponerla, porque "backend HTTP 401" no le daba nada que hacer.
    /// </summary>
    private string AuthErrorMessage(HttpStatusCode status) => _legacy
        ? $"el backend rechazó el ClientToken (HTTP {(int)status}). Revisa el token en el panel Backend."
        : $"falta la API key de Graph o no es válida (HTTP {(int)status}). Configúrala en " +
          @"%APPDATA%\U\graph.json (campo ApiKey, key miracle_…) o en la variable de entorno GRAPH_API_KEY.";
}
