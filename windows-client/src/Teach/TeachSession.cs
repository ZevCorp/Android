using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Serialization;
using U.WindowsClient.Backend;
using U.WindowsClient.Diagnostics;

namespace U.WindowsClient.Teach;

/// <summary>
/// Orquesta la sesión de enseñanza activa: graba pantalla+voz, sube el mp4, lo procesa y guarda.
///
/// EL CLIENTE NO TIENE NINGUNA KEY. Antes hablaba directo con Gemini y eso obligaba a repartir una
/// key de Google dentro del .exe: cada usuario nuevo tenía que pegarla a mano y era extraíble
/// descompilando. Ahora el backend firma las subidas y hace las llamadas al modelo; el cliente solo
/// mueve bytes. El video igual NO pasa por el backend (no cabe en el límite de payload de Vercel):
/// sube directo a Google y a Supabase con URLs firmadas de corta duración, que es lo mejor de los
/// dos mundos.
///
/// Flujo:
/// 1. /api/teach/upload-token  → URL de subida a Gemini + URL de archivo (Supabase Storage)
/// 2. PUT del mp4 directo a Gemini (bytes, sin key) y también al archivo, para que lo veamos después
/// 3. /api/teach/file-state en bucle → hasta que Gemini deja el video ACTIVE
/// 4. /api/teach/process-video → el backend procesa con el prompt médico y guarda el conocimiento
/// 5. El mp4 y el resumen quedan además en el disco del usuario (VideoLibrary)
/// </summary>
public sealed class TeachSession : IAsyncDisposable
{
    private readonly BackendClient _backend;
    private readonly VideoLibrary _library;
    private readonly string _userId;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromMinutes(30) };

    private ScreenRecorder? _recorder;
    private string? _recordingPath;
    private string? _recorderError;

    public event EventHandler<string>? StatusChanged;

    public TeachSession(BackendClient backend, VideoLibrary library, string userId)
    {
        _backend = backend;
        _library = library;
        _userId = userId;
    }

    /// <summary>Inicia grabación. El usuario ve la pantalla tal como la usa.</summary>
    public async Task StartAsync(CancellationToken ct = default)
    {
        LogBus.Log("teach", "TeachSession.StartAsync");
        _recordingPath = _library.NewRecordingPath();
        _recorder = new ScreenRecorder(_recordingPath);
        try
        {
            await _recorder.StartAsync(ct);
            StatusChanged?.Invoke(this, "Grabando pantalla + voz…");
        }
        catch (Exception ex)
        {
            LogBus.Log("teach", $"StartAsync falló: {ex}");
            StatusChanged?.Invoke(this, $"Error al iniciar grabación: {ex.Message}");
            throw;
        }
    }

    /// <summary>Detiene la grabación. El mp4 queda finalizado en disco, listo para procesar.</summary>
    public async Task StopAsync()
    {
        if (_recorder == null) return;
        try
        {
            await _recorder.StopAsync();
            _recorderError = _recorder.LastError;
            StatusChanged?.Invoke(this, _recorderError == null
                ? "Grabación detenida. Procesando…"
                : $"La grabación falló: {_recorderError}");
        }
        finally
        {
            await _recorder.DisposeAsync();
            _recorder = null;
        }
    }

    /// <summary>Sube el mp4, lo procesa vía backend y guarda el resumen. Devuelve lo que Ü aprendió.</summary>
    public async Task<string> ProcessAsync(CancellationToken ct = default)
    {
        if (string.IsNullOrEmpty(_recordingPath) || !File.Exists(_recordingPath))
        {
            string reason = _recorderError != null
                ? $"la grabación falló antes de producir un archivo: {_recorderError}"
                : "no se generó ningún archivo de video (¿se detuvo antes de que empezara a grabar?)";
            LogBus.Log("teach", $"ProcessAsync: no hay grabación para procesar — {reason}");
            throw new InvalidOperationException($"No hay grabación para procesar: {reason}");
        }

        long length = new FileInfo(_recordingPath).Length;
        LogBus.Log("teach", $"ProcessAsync: {_recordingPath} ({length / 1024 / 1024.0:0.0} MB)");

        StatusChanged?.Invoke(this, "Preparando subida…");
        var token = await _backend.PostAsync<UploadTokenResponse>(
            "/api/teach/upload-token", new UploadTokenRequest { ContentLength = length, UserId = _userId }, ct);
        if (token?.GeminiUploadUrl == null)
            throw new InvalidOperationException("el backend no devolvió URL de subida para Gemini");
        if (token.ArchiveError != null)
            LogBus.Log("teach", $"archivo del video no disponible: {token.ArchiveError}");

        StatusChanged?.Invoke(this, "Subiendo video…");
        string fileUri = await UploadToGeminiAsync(token.GeminiUploadUrl, _recordingPath, length, ct);
        LogBus.Log("teach", $"subido a Gemini: {fileUri}");

        // Archivar en Supabase es "best effort": si falla, el usuario no se queda sin enseñanza —
        // solo perdemos poder revisar ese video desde desarrollo.
        if (token.ArchiveUploadUrl != null)
        {
            try
            {
                await ArchiveAsync(token.ArchiveUploadUrl, _recordingPath, ct);
                LogBus.Log("teach", $"video archivado en {token.ArchivePath}");
            }
            catch (Exception ex)
            {
                LogBus.Log("teach", $"no se pudo archivar el video: {ex.Message}");
            }
        }

        StatusChanged?.Invoke(this, "Esperando a que el video se procese…");
        await WaitActiveAsync(fileUri, ct);

        StatusChanged?.Invoke(this, "Analizando lo que enseñaste…");
        var result = await _backend.PostAsync<ProcessResult>(
            "/api/teach/process-video", new ProcessRequest { FileUri = fileUri, UserId = _userId }, ct);
        string summary = result?.Summary ?? "";
        LogBus.Log("teach", $"procesado: {result?.Notes?.Count ?? 0} nota(s). summary=\"{summary}\"");

        _library.SaveSummary(_recordingPath, summary);
        StatusChanged?.Invoke(this, summary.Length > 0 ? summary : "No encontré nada que aprender en el video.");
        return summary;
    }

    /// <summary>Paso 2 del resumable upload de Gemini. Sin key: la URL ya trae su token embebido.</summary>
    private async Task<string> UploadToGeminiAsync(string uploadUrl, string videoPath, long length, CancellationToken ct)
    {
        using var file = new FileStream(videoPath, FileMode.Open, FileAccess.Read);
        using var content = new StreamContent(file);
        content.Headers.ContentType = new MediaTypeHeaderValue("video/mp4");
        content.Headers.ContentLength = length;

        using var req = new HttpRequestMessage(HttpMethod.Put, uploadUrl) { Content = content };
        req.Headers.Add("X-Goog-Upload-Offset", "0");
        req.Headers.Add("X-Goog-Upload-Command", "upload, finalize");

        using var res = await _http.SendAsync(req, ct);
        string body = await res.Content.ReadAsStringAsync(ct);
        if (!res.IsSuccessStatusCode)
            throw new InvalidOperationException($"subida a Gemini HTTP {(int)res.StatusCode}: {body}");

        using var doc = JsonDocument.Parse(body);
        if (doc.RootElement.TryGetProperty("file", out var fileObj) && fileObj.TryGetProperty("uri", out var uri))
            return uri.GetString() ?? throw new InvalidOperationException("Gemini devolvió una uri vacía");

        throw new InvalidOperationException("respuesta de Gemini sin uri del archivo");
    }

    /// <summary>Sube el mismo mp4 al archivo (Supabase Storage) con la URL firmada por el backend.</summary>
    private async Task ArchiveAsync(string uploadUrl, string videoPath, CancellationToken ct)
    {
        using var file = new FileStream(videoPath, FileMode.Open, FileAccess.Read);
        using var content = new StreamContent(file);
        content.Headers.ContentType = new MediaTypeHeaderValue("video/mp4");

        using var res = await _http.PutAsync(uploadUrl, content, ct);
        if (!res.IsSuccessStatusCode)
        {
            string body = await res.Content.ReadAsStringAsync(ct);
            throw new InvalidOperationException($"HTTP {(int)res.StatusCode}: {body}");
        }
    }

    /// <summary>Espera a que Gemini deje el video ACTIVE, preguntándole al backend (que sí tiene la key).</summary>
    private async Task WaitActiveAsync(string fileUri, CancellationToken ct)
    {
        for (int i = 0; i < 90; i++)
        {
            var res = await _backend.PostAsync<FileStateResponse>(
                "/api/teach/file-state", new FileStateRequest { FileUri = fileUri }, ct);
            string state = res?.State ?? "UNKNOWN";

            if (state == "ACTIVE") return;
            if (state == "FAILED") throw new InvalidOperationException("el video quedó FAILED en Gemini");
            await Task.Delay(2000, ct);
        }
        throw new InvalidOperationException("el video sigue procesándose tras el tiempo máximo de espera");
    }

    public async ValueTask DisposeAsync()
    {
        if (_recorder != null) await _recorder.DisposeAsync();
        _http.Dispose();
    }
}

// ── Contratos con el backend ────────────────────────────────────────

public sealed record UploadTokenRequest
{
    [JsonPropertyName("contentLength")]
    public long ContentLength { get; set; }
    [JsonPropertyName("userId")]
    public required string UserId { get; set; }
}

public sealed record UploadTokenResponse
{
    [JsonPropertyName("geminiUploadUrl")]
    public string? GeminiUploadUrl { get; set; }
    [JsonPropertyName("archiveUploadUrl")]
    public string? ArchiveUploadUrl { get; set; }
    [JsonPropertyName("archivePath")]
    public string? ArchivePath { get; set; }
    [JsonPropertyName("archiveError")]
    public string? ArchiveError { get; set; }
}

public sealed record FileStateRequest
{
    [JsonPropertyName("fileUri")]
    public required string FileUri { get; set; }
}

public sealed record FileStateResponse
{
    [JsonPropertyName("state")]
    public string? State { get; set; }
}

public sealed record ProcessRequest
{
    [JsonPropertyName("fileUri")]
    public required string FileUri { get; set; }
    [JsonPropertyName("userId")]
    public required string UserId { get; set; }
}

public sealed record TeachNote
{
    [JsonPropertyName("app")]
    public string? App { get; set; }
    [JsonPropertyName("note")]
    public string? Note { get; set; }
}

public sealed record ProcessResult
{
    [JsonPropertyName("summary")]
    public string? Summary { get; set; }
    [JsonPropertyName("notes")]
    public List<TeachNote>? Notes { get; set; }
    [JsonPropertyName("questions")]
    public List<string>? Questions { get; set; }
}
