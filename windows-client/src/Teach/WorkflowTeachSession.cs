using U.Graph;
using U.Graph.Surfaces;
using U.WindowsClient.Backend;
using U.WindowsClient.Diagnostics;

namespace U.WindowsClient.Teach;

/// <summary>
/// Orquesta "Enseñar workflow": graba pasos estructurados (UIA o SAP GUI, detectado por la ventana en
/// primer plano) Y video de contexto (<see cref="TeachSession"/>) EN PARALELO, sobre la misma acción
/// del operador. Son dos grabadoras completamente independientes que solo esta clase sincroniza.
///
/// ORDEN CRÍTICO al detener: <see cref="WorkflowRecorder.StopAsync"/> ya cierra la sesión en Graph
/// (post-procesa y persiste el workflow) como parte de sí misma. Por eso el resumen del video debe
/// llegar a Graph vía <see cref="WorkflowRecorder.AddContextAsync"/> ANTES de llamar a
/// <c>StopAsync</c> — después ya no hay sesión abierta a la que adjuntarlo.
/// </summary>
public sealed class WorkflowTeachSession : IAsyncDisposable
{
    private readonly GraphClient _graph;
    private readonly GraphConfig _graphConfig;
    private readonly IUiSurface _uia;
    private readonly IUiSurface _sap;
    private readonly BackendClient _backend;
    private readonly VideoLibrary _videoLibrary;
    private readonly string _userId;

    private WorkflowRecorder? _recorder;
    private TeachSession? _teach;

    /// <summary>Progreso legible para la UI: countdown, superficie detectada, pasos enviados, errores.</summary>
    public event EventHandler<string>? StatusChanged;

    public bool IsRecording => _recorder != null;

    public WorkflowTeachSession(
        GraphClient graph, GraphConfig graphConfig, IUiSurface uia, IUiSurface sap,
        BackendClient backend, VideoLibrary videoLibrary, string userId)
    {
        _graph = graph;
        _graphConfig = graphConfig;
        _uia = uia;
        _sap = sap;
        _backend = backend;
        _videoLibrary = videoLibrary;
        _userId = userId;
    }

    /// <summary>
    /// Inicia la enseñanza. <paramref name="description"/> es lo que Graph guarda como descripción del
    /// workflow — obligatoria porque WorkflowRecorder.StartAsync la necesita para abrir la sesión.
    /// </summary>
    public async Task StartAsync(string description, CancellationToken ct)
    {
        if (IsRecording) throw new InvalidOperationException("Ya hay una enseñanza de workflow en curso.");
        if (!_graphConfig.IsConfigured)
            throw new InvalidOperationException(
                "Graph no está configurado: falta la URL o la API key (panel Workflows → Conexión).");

        // Cuenta regresiva a propósito: si detectáramos la superficie ahora mismo, resolvería a ESTA
        // MISMA ventana de Ü, no a la app que el operador va a enseñar. Le da tiempo a cambiar de foco.
        for (int s = 3; s > 0; s--)
        {
            StatusChanged?.Invoke(this, $"Cambia a la ventana que vas a enseñar… grabando en {s}s");
            await Task.Delay(1000, ct);
        }

        IUiSurface surface = SurfaceDetector.Detect(_uia, _sap);
        var availability = surface.Check();
        if (!availability.Available)
            throw new InvalidOperationException($"[{surface.Name}] {availability.Reason}");

        StatusChanged?.Invoke(this, $"Grabando pasos sobre «{surface.Name}»…");

        var recorder = new WorkflowRecorder(_graph, _graphConfig, surface);
        recorder.Progress += (_, status) => StatusChanged?.Invoke(this,
            $"Grabando pasos… {status.StepsSent} enviados" +
            (status.LastError != null ? $" (último error: {status.LastError})" : ""));

        await recorder.StartAsync(description, ct);
        _recorder = recorder;

        var teach = new TeachSession(_backend, _videoLibrary, _userId);
        teach.StatusChanged += (_, msg) => StatusChanged?.Invoke(this, msg);
        try
        {
            await teach.StartAsync(ct);
            _teach = teach;
        }
        catch (Exception ex)
        {
            // El video es parte del requisito ("UIA + video en paralelo"): si no arranca, no dejamos una
            // grabación de pasos a medias sin su contexto — se aborta todo y se reporta el motivo real.
            LogBus.Log("workflow-teach", $"el video no arrancó, se cancela la grabación de pasos: {ex.Message}");
            await recorder.StopAsync(CancellationToken.None);
            _recorder = null;
            throw;
        }
    }

    /// <summary>Detiene ambas grabaciones y devuelve el workflow ya post-procesado y persistido por Graph.</summary>
    public async Task<FinishResponse> StopAsync(CancellationToken ct)
    {
        if (_recorder == null) throw new InvalidOperationException("No hay ninguna enseñanza de workflow en curso.");
        WorkflowRecorder recorder = _recorder;
        _recorder = null;

        string? summary = null;
        if (_teach != null)
        {
            try
            {
                await _teach.StopAsync();
                StatusChanged?.Invoke(this, "Procesando video de contexto…");
                summary = await _teach.ProcessAsync(ct);
            }
            catch (Exception ex)
            {
                LogBus.Log("workflow-teach", $"el video falló, el workflow se guarda solo con los pasos: {ex.Message}");
                StatusChanged?.Invoke(this, $"El video falló ({ex.Message}); el workflow se guarda solo con los pasos.");
            }
            finally
            {
                await _teach.DisposeAsync();
                _teach = null;
            }
        }

        if (!string.IsNullOrWhiteSpace(summary))
        {
            try { await recorder.AddContextAsync(summary!, ct); }
            catch (Exception ex)
            {
                LogBus.Log("workflow-teach", $"no se pudo adjuntar el contexto de video: {ex.Message}");
            }
        }

        StatusChanged?.Invoke(this, "Cerrando la grabación y pidiéndole a Graph que la estructure…");
        return await recorder.StopAsync(ct);
    }

    public async ValueTask DisposeAsync()
    {
        if (_teach != null) { await _teach.DisposeAsync(); _teach = null; }
        if (_recorder != null) { await _recorder.DisposeAsync(); _recorder = null; }
    }
}
