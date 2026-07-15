using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using U.WindowsClient.Domain;

namespace U.WindowsClient.Backend;

/// <summary>
/// El único puente con el cerebro: <c>POST {BackendUrl}/api/agent/turn</c>. El cliente manda el estado
/// de pantalla y recibe las acciones a ejecutar. No hay otra llamada de red con inteligencia: si este
/// endpoint no responde, el cliente no sabe pensar por su cuenta — a propósito.
/// </summary>
public sealed class BackendClient
{
    private readonly HttpClient _http;
    private readonly string _baseUrl;
    private readonly string _userId;

    private static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    public BackendClient(Config config)
    {
        _baseUrl = config.BackendUrl.TrimEnd('/');
        _userId = config.UserId;
        _http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        if (!string.IsNullOrWhiteSpace(config.ClientToken))
            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", config.ClientToken);
    }

    public async Task<TurnResponse> TurnAsync(TurnRequest req, CancellationToken ct)
    {
        req.UserId = _userId;
        var body = JsonSerializer.Serialize(req, Json);
        using var content = new StringContent(body, Encoding.UTF8, "application/json");
        using var res = await _http.PostAsync($"{_baseUrl}/api/agent/turn", content, ct);
        var text = await res.Content.ReadAsStringAsync(ct);
        var parsed = JsonSerializer.Deserialize<TurnResponse>(text, Json);
        if (parsed == null) throw new InvalidOperationException($"respuesta vacía del backend (HTTP {(int)res.StatusCode})");
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException(parsed.Error ?? $"backend HTTP {(int)res.StatusCode}");
        return parsed;
    }

    /// <summary>POST genérico hacia cualquier endpoint del backend que devuelva JSON tipado.</summary>
    public async Task<T?> PostAsync<T>(string path, object req, CancellationToken ct) where T : class
    {
        var body = JsonSerializer.Serialize(req, Json);
        using var content = new StringContent(body, Encoding.UTF8, "application/json");
        using var res = await _http.PostAsync($"{_baseUrl}{path}", content, ct);
        var text = await res.Content.ReadAsStringAsync(ct);
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException($"backend HTTP {(int)res.StatusCode}: {text}");
        return JsonSerializer.Deserialize<T>(text, Json);
    }
}
