using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using U.WindowsClient.Backend;
using U.WindowsClient.Diagnostics;

namespace U.WindowsClient.Teach;

/// <summary>
/// Orquesta la sesión de enseñanza activa: graba pantalla+voz, sube DIRECTO A GEMINI, procesa con el
/// prompt médico embebido, guarda el video, y reporta las notas extraídas al backend.
///
/// POR QUÉ NO PASA POR EL BACKEND (ver <see cref="Config.GeminiApiKey"/>): el mp4 no cabe en el límite
/// de payload de una función de Vercel, y esperar a que Gemini termine de procesarlo (subida + estado
/// ACTIVE + generateContent) excede cómodamente su límite de duración. La única forma de no depender
/// del plan/límites de Vercel para esta función es que el cliente hable directo con Gemini.
///
/// Flujo:
/// 1. Start (protocolo resumable de Gemini: reserva el archivo con la key)
/// 2. Sube los bytes del mp4 directo a Gemini (segundo paso del resumable upload; sin la key, la URL
///    de subida ya la trae embebida)
/// 3. Espera a que el archivo salga de PROCESSING → ACTIVE
/// 4. generateContent con el prompt médico + fileUri del video
/// 5. Guarda el video localmente (VideoLibrary) con el resumen
/// 6. Reporta las notas extraídas (JSON pequeño) al backend, que las persiste en su MemoryStore
/// </summary>
public sealed class TeachSession : IAsyncDisposable
{
    private const string GeminiBase = "https://generativelanguage.googleapis.com";

    private readonly BackendClient _backend;
    private readonly VideoLibrary _library;
    private readonly string _userId;
    private readonly string _geminiApiKey;
    private readonly string _geminiModel;
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromMinutes(10) };

    private ScreenRecorder? _recorder;
    private string? _recordingPath;
    private string? _recorderError;

    public event EventHandler<string>? StatusChanged;

    public TeachSession(BackendClient backend, VideoLibrary library, string userId, string geminiApiKey, string geminiModel)
    {
        _backend = backend;
        _library = library;
        _userId = userId;
        _geminiApiKey = geminiApiKey;
        _geminiModel = geminiModel;
    }

    /// <summary>Inicia grabación. El usuario ve la pantalla tal como la usa.</summary>
    public async Task StartAsync(CancellationToken ct = default)
    {
        LogBus.Log("teach", "TeachSession.StartAsync");
        if (string.IsNullOrWhiteSpace(_geminiApiKey))
        {
            const string msg = "Falta configurar GeminiApiKey en el panel de ajustes (flecha junto a "
                + "\"Backend\"): la enseñanza por video habla directo con Gemini.";
            LogBus.Log("teach", msg);
            StatusChanged?.Invoke(this, msg);
            throw new InvalidOperationException(msg);
        }

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

    /// <summary>
    /// Tira a la basura la grabación en curso y empieza una nueva, sin subir ni procesar nada.
    ///
    /// Existe porque equivocarse enseñando (meterse en la plataforma que no era, hacer un paso al
    /// revés) era irreversible: la única salida era detener, y detener SUBE el video a Gemini y manda
    /// sus notas al MemoryStore del backend. Es decir, el error quedaba aprendido — justo lo contrario
    /// de lo que el médico quería. Reiniciar corta eso antes de que salga nada de la máquina.
    /// </summary>
    public async Task RestartAsync(CancellationToken ct = default)
    {
        await DiscardAsync();
        await StartAsync(ct);
    }

    /// <summary>
    /// Detiene la grabación en curso, si la hay, y borra su mp4. No sube ni procesa nada: lo grabado
    /// hasta aquí desaparece.
    /// </summary>
    private async Task DiscardAsync()
    {
        if (_recorder != null)
        {
            try
            {
                // Hay que esperar el Stop aunque el video se vaya a la basura: hasta que no llega
                // OnRecordingComplete, el mp4 sigue abierto y el File.Delete de abajo fallaría.
                await _recorder.StopAsync();
            }
            catch (Exception ex)
            {
                // Da igual por qué falló al detener; el archivo se descarta igual.
                LogBus.Log("teach", $"fallo al detener la grabación que se iba a descartar: {ex.Message}");
            }
            finally
            {
                await _recorder.DisposeAsync();
                _recorder = null;
            }
        }

        if (_recordingPath != null && !_library.Delete(_recordingPath))
            LogBus.Log("teach", $"no se pudo borrar la grabación descartada, quedará en 🎞 Videos: {_recordingPath}");
        else if (_recordingPath != null)
            LogBus.Log("teach", $"grabación descartada: {_recordingPath}");

        _recordingPath = null;
        _recorderError = null;
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

    /// <summary>Sube a Gemini, procesa con el prompt médico, guarda video+resumen y reporta notas al backend.</summary>
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

        LogBus.Log("teach", $"ProcessAsync: subiendo {_recordingPath}");
        StatusChanged?.Invoke(this, "Subiendo video a Gemini…");
        string fileUri = await UploadAsync(_recordingPath, ct);
        LogBus.Log("teach", $"video subido, fileUri={fileUri}");

        StatusChanged?.Invoke(this, "Esperando a que Gemini procese el video…");
        await WaitActiveAsync(fileUri, ct);
        LogBus.Log("teach", "video ACTIVE en Gemini");

        StatusChanged?.Invoke(this, "Procesando video con IA…");
        var result = await GenerateAsync(fileUri, ct);
        LogBus.Log("teach", $"generateContent devolvió {result.Notes.Count} nota(s). summary=\"{result.Summary}\"");

        _library.SaveSummary(_recordingPath, result.Summary);

        // Las notas extraídas son texto pequeño: esto SÍ cabe cómodo en una función de Vercel.
        // El backend las persiste en su MemoryStore (el mismo que ya inyecta contexto en cada turno).
        await ReportNotesAsync(result.Notes, ct);

        StatusChanged?.Invoke(this, result.Summary);
        return result.Summary;
    }

    // ── Subida (resumable upload de Gemini Files API, hecho de punta a punta desde el cliente) ──

    private async Task<string> UploadAsync(string videoPath, CancellationToken ct)
    {
        long length = new FileInfo(videoPath).Length;

        using var startReq = new HttpRequestMessage(HttpMethod.Post, $"{GeminiBase}/upload/v1beta/files");
        startReq.Headers.Add("x-goog-api-key", _geminiApiKey);
        startReq.Headers.Add("X-Goog-Upload-Protocol", "resumable");
        startReq.Headers.Add("X-Goog-Upload-Command", "start");
        startReq.Headers.Add("X-Goog-Upload-Header-Content-Length", length.ToString());
        startReq.Headers.Add("X-Goog-Upload-Header-Content-Type", "video/mp4");
        startReq.Content = new StringContent("""{"file":{"display_name":"graph_teach"}}""", Encoding.UTF8, "application/json");

        using var startRes = await _http.SendAsync(startReq, ct);
        if (!startRes.IsSuccessStatusCode)
        {
            string body = await startRes.Content.ReadAsStringAsync(ct);
            throw new InvalidOperationException($"Gemini upload start HTTP {(int)startRes.StatusCode}: {body}");
        }
        if (!startRes.Headers.TryGetValues("X-Goog-Upload-URL", out var urls))
            throw new InvalidOperationException("Gemini no devolvió X-Goog-Upload-URL");
        string uploadUrl = urls.First();

        using var file = new FileStream(videoPath, FileMode.Open, FileAccess.Read);
        using var content = new StreamContent(file);
        content.Headers.ContentType = new MediaTypeHeaderValue("video/mp4");
        content.Headers.ContentLength = length;

        using var uploadReq = new HttpRequestMessage(HttpMethod.Put, uploadUrl) { Content = content };
        // Sin API key: la upload_url ya trae el token de sesión embebido (protocolo resumable estándar).
        uploadReq.Headers.Add("X-Goog-Upload-Offset", "0");
        uploadReq.Headers.Add("X-Goog-Upload-Command", "upload, finalize");

        using var uploadRes = await _http.SendAsync(uploadReq, ct);
        string uploadBody = await uploadRes.Content.ReadAsStringAsync(ct);
        if (!uploadRes.IsSuccessStatusCode)
            throw new InvalidOperationException($"Gemini upload HTTP {(int)uploadRes.StatusCode}: {uploadBody}");

        using var doc = JsonDocument.Parse(uploadBody);
        if (doc.RootElement.TryGetProperty("file", out var fileObj) && fileObj.TryGetProperty("uri", out var uri))
            return uri.GetString() ?? throw new InvalidOperationException("Gemini devolvió una uri vacía");

        throw new InvalidOperationException("Respuesta de Gemini sin uri del archivo");
    }

    // ── Espera a que el archivo salga de PROCESSING ──

    private async Task WaitActiveAsync(string fileUri, CancellationToken ct)
    {
        // fileUri es del tipo https://generativelanguage.googleapis.com/v1beta/files/abc123
        string name = fileUri[(fileUri.IndexOf("/v1beta/", StringComparison.Ordinal) + 1)..];

        for (int i = 0; i < 45; i++)
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, $"{GeminiBase}/{name}");
            req.Headers.Add("x-goog-api-key", _geminiApiKey);
            using var res = await _http.SendAsync(req, ct);
            string body = await res.Content.ReadAsStringAsync(ct);

            string? state = null;
            try
            {
                using var doc = JsonDocument.Parse(body);
                if (doc.RootElement.TryGetProperty("state", out var s)) state = s.GetString();
            }
            catch { }

            if (state == "ACTIVE") return;
            if (state == "FAILED") throw new InvalidOperationException("el video quedó FAILED en el servidor de Gemini");
            await Task.Delay(2000, ct);
        }
        throw new InvalidOperationException("el video sigue en PROCESSING tras el tiempo máximo de espera");
    }

    // ── Procesamiento: video → conocimiento del sistema (prompt médico) ──

    private const string MedicalTeachPrompt = """
        Eres Ü, un asistente que ayudará a operar el sistema informático de un hospital (HIS/EHR u otro
        software clínico). Un MÉDICO acaba de grabar su pantalla mientras USA ese sistema, narrando en
        voz alta lo que hace — te está ENSEÑANDO cómo se opera, para que después tú puedas ayudar a
        otros usuarios con las mismas tareas.

        Mira TODO el video (imagen + audio) y extrae CONOCIMIENTO SOBRE EL SISTEMA, organizado POR
        APLICACIÓN/MÓDULO. Buscamos hechos operativos reutilizables, NO datos de un caso concreto.
        Ejemplos del tipo de nota que sí sirve:
        - "Para admitir un paciente se usa el botón 'Nuevo ingreso' en la pantalla principal, no el
          menú 'Pacientes'."
        - "El campo 'Diagnóstico principal' solo acepta códigos CIE-10; hay un buscador si se escribe
          texto."
        - "Las órdenes de laboratorio se firman digitalmente desde la pestaña 'Pendientes', abajo a la
          derecha."

        REGLA DE PRIVACIDAD, ABSOLUTA Y SIN EXCEPCIÓN:
        NUNCA registres en una nota ningún dato que identifique o describa a una persona concreta:
        nombres de pacientes, números de historia clínica o documento, fechas de nacimiento,
        diagnósticos específicos de un caso, resultados de laboratorio, medicaciones recetadas, o
        cualquier dato clínico ligado a un caso real que aparezca en pantalla durante la demostración.
        Si un ejemplo en el video usa datos de un paciente (real o de prueba), IGNORA esos datos por
        completo y quédate solo con EL PROCEDIMIENTO. Ante cualquier duda de si un dato es
        identificable, OMÍTELO.

        REGLAS ESTRICTAS (calidad sobre cantidad):
        - Cada nota: UNA frase, auto-contenida, sobre CÓMO FUNCIONA o CÓMO SE USA el sistema.
        - Incluye solo lo que entiendas con certeza muy alta y tenga valor real. Ante la duda, fuera.
        - "app": el nombre visible del sistema o módulo (p.ej. "HIS - Admisiones", "Laboratorio"). Si
          la nota es general, usa "".
        - Si algo quedó ambiguo y conviene confirmarlo, agrégalo en "questions" (máximo 3).
        - Si el video no contiene nada confiable que guardar, devuelve items y questions vacíos.

        Además, escribe un "summary": un resumen CORTO (1-3 frases), en primera persona y en tono
        profesional, de lo que ENTENDISTE sobre cómo se usa el sistema.

        Responde SOLO JSON:
        {"summary": "...", "items": [{"app": "HIS - Admisiones", "note": "..."}], "questions": ["..."]}
        """;

    /// <summary>
    /// Gemini devuelve 429/5xx ("This model is currently overloaded") cuando está saturado — Google los
    /// documenta como temporales. Sin reintento, un bache de demanda tira toda la enseñanza y el video
    /// del médico se pierde después de haberlo subido. Backoff exponencial 0.8s → 8s, igual que hace la
    /// versión Android (GeminiHttp.withRetry).
    /// </summary>
    private static bool IsTransient(System.Net.HttpStatusCode s) =>
        (int)s == 429 || (int)s >= 500;

    private async Task<TeachResult> GenerateAsync(string fileUri, CancellationToken ct)
    {
        var reqBody = new
        {
            contents = new[]
            {
                new
                {
                    role = "user",
                    parts = new object[]
                    {
                        new { fileData = new { mimeType = "video/mp4", fileUri } },
                        new { text = MedicalTeachPrompt },
                    },
                },
            },
            generationConfig = new { responseMimeType = "application/json" },
        };
        string payload = JsonSerializer.Serialize(reqBody);

        string body = "";
        System.Net.HttpStatusCode status = 0;
        // 5 intentos: 0.8s, 1.6s, 3.2s, 6.4s. Un 5xx significa que Gemini no llegó a generar nada, así
        // que reintentar el mismo POST no duplica ningún efecto.
        for (int attempt = 0; attempt < 5; attempt++)
        {
            if (attempt > 0)
            {
                int waitMs = (int)(800 * Math.Pow(2, attempt - 1));
                StatusChanged?.Invoke(this, $"Gemini saturado ({(int)status}); reintentando en {waitMs / 1000.0:0.#}s…");
                LogBus.Log("teach", $"generateContent HTTP {(int)status}, reintento {attempt}/4 en {waitMs}ms");
                await Task.Delay(waitMs, ct);
            }

            using var req = new HttpRequestMessage(HttpMethod.Post, $"{GeminiBase}/v1beta/models/{_geminiModel}:generateContent");
            req.Headers.Add("x-goog-api-key", _geminiApiKey);
            req.Content = new StringContent(payload, Encoding.UTF8, "application/json");

            using var res = await _http.SendAsync(req, ct);
            body = await res.Content.ReadAsStringAsync(ct);
            status = res.StatusCode;

            if (res.IsSuccessStatusCode) break;
            if (!IsTransient(status))
                throw new InvalidOperationException($"Gemini generateContent HTTP {(int)status}: {body}");
        }

        if (!((int)status >= 200 && (int)status < 300))
            throw new InvalidOperationException(
                $"Gemini generateContent HTTP {(int)status} tras 5 intentos (sigue saturado): {body}");

        using var doc = JsonDocument.Parse(body);
        string text = doc.RootElement
            .GetProperty("candidates")[0]
            .GetProperty("content")
            .GetProperty("parts")[0]
            .GetProperty("text")
            .GetString() ?? throw new InvalidOperationException("Gemini no devolvió texto");

        return ParseResult(text);
    }

    private static TeachResult ParseResult(string text)
    {
        int start = text.IndexOf('{');
        int end = text.LastIndexOf('}');
        if (start < 0 || end < start) throw new InvalidOperationException("respuesta de Gemini sin JSON reconocible");

        using var doc = JsonDocument.Parse(text[start..(end + 1)]);
        var root = doc.RootElement;

        string summary = root.TryGetProperty("summary", out var s) ? s.GetString() ?? "" : "";
        var notes = new List<TeachNote>();
        if (root.TryGetProperty("items", out var items) && items.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in items.EnumerateArray())
            {
                string app = item.TryGetProperty("app", out var a) ? a.GetString() ?? "" : "";
                string note = item.TryGetProperty("note", out var n) ? n.GetString() ?? "" : "";
                if (note.Trim().Length > 0) notes.Add(new TeachNote { App = app.Trim(), Note = note.Trim() });
            }
        }

        return new TeachResult { Summary = summary.Trim(), Notes = notes };
    }

    // ── Reporte al backend: solo texto, cabe cómodo en una función de Vercel ──

    private async Task ReportNotesAsync(List<TeachNote> notes, CancellationToken ct)
    {
        if (notes.Count == 0) return;
        try
        {
            var req = new SaveNotesRequest { UserId = _userId, Notes = notes };
            await _backend.PostAsync<SaveNotesResponse>("/api/teach/save-notes", req, ct);
        }
        catch (Exception ex)
        {
            // No es fatal: el video y el resumen ya quedaron guardados localmente. Solo se pierde
            // que el cerebro recuerde esto en futuros turnos.
            LogBus.Log("teach", $"ReportNotesAsync falló: {ex}");
            StatusChanged?.Invoke(this, $"Video procesado, pero no se pudo guardar en el backend: {ex.Message}");
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_recorder != null) await _recorder.DisposeAsync();
        _http.Dispose();
    }
}

// ── Tipos internos ────────────────────────────────────────────────

internal sealed class TeachResult
{
    public required string Summary { get; init; }
    public required List<TeachNote> Notes { get; init; }
}

// ── Contrato con el backend (solo para reportar notas ya extraídas) ────────────────

public sealed record TeachNote
{
    [JsonPropertyName("app")]
    public required string App { get; set; }
    [JsonPropertyName("note")]
    public required string Note { get; set; }
}

public sealed record SaveNotesRequest
{
    [JsonPropertyName("userId")]
    public required string UserId { get; set; }
    [JsonPropertyName("notes")]
    public required List<TeachNote> Notes { get; set; }
}

public sealed record SaveNotesResponse
{
    [JsonPropertyName("ok")]
    public bool Ok { get; set; }
}
