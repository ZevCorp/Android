using System.Threading.Channels;
using U.Graph.Surfaces;

namespace U.Graph;

/// <summary>Cómo va la grabación. La UI lo muestra; nadie decide nada con esto.</summary>
public sealed record RecordingStatus(bool Recording, string SessionId, int StepsSent, string? LastError);

/// <summary>
/// Graba lo que hace el usuario sobre una superficie y lo va enviando a Graph como pasos.
///
/// Es el equivalente de lo que hace la extensión de Chrome (chrome-extension-src/graph-trainer) sobre
/// el DOM, pero con SAP GUI o UIA debajo. Graph no nota la diferencia: recibe el mismo shape de step.
///
/// HILOS: los eventos de superficie llegan en un hilo que no es el nuestro y no se puede bloquear ahí
/// (UIA se ahoga si el handler tarda, y con SAP se arriesga la sesión del operador). Por eso el evento
/// solo ENCOLA, y un worker aparte hace el HTTP. La cola además garantiza el orden: Graph numera los
/// pasos por orden de llegada, así que mandarlos en paralelo los desordenaría.
/// </summary>
public sealed class WorkflowRecorder : IAsyncDisposable
{
    private readonly GraphClient _graph;
    private readonly GraphConfig _config;
    private readonly IUiSurface _surface;

    private Channel<ObservedStep>? _queue;
    private Task? _pump;
    private CancellationTokenSource? _cts;
    private string _sessionId = "";
    private int _stepsSent;
    private string? _lastError;

    /// <summary>Se dispara cuando un paso llega a Graph, o cuando falla. Para la UI.</summary>
    public event EventHandler<RecordingStatus>? Progress;

    public WorkflowRecorder(GraphClient graph, GraphConfig config, IUiSurface surface)
    {
        _graph = graph;
        _config = config;
        _surface = surface;
    }

    public bool IsRecording => _sessionId.Length > 0;

    public RecordingStatus Status => new(IsRecording, _sessionId, _stepsSent, _lastError);

    /// <summary>
    /// Abre la sesión en Graph y empieza a observar. Devuelve el workflowId (que es el id de sesión).
    /// Falla temprano y con motivo si la superficie no está disponible: es mejor que descubrirlo a
    /// mitad de la grabación del operador.
    /// </summary>
    public async Task<string> StartAsync(string description, CancellationToken ct)
    {
        if (IsRecording) throw new InvalidOperationException("Ya hay una grabación en curso.");

        var availability = _surface.Check();
        if (!availability.Available) throw new GraphException(availability.Reason);

        var identity = _surface.Identity();

        _sessionId = await _graph.StartSessionAsync(new StartSessionRequest
        {
            Description = string.IsNullOrWhiteSpace(description) ? "Workflow sin descripción" : description.Trim(),
            AppId = _config.AppId,
            SourceUrl = identity.Url,
            SourceOrigin = identity.Origin,
            SourcePathname = identity.Pathname,
            SourceTitle = identity.Title,
            Context = new Dictionary<string, string>
            {
                ["surface"] = _surface.Name,
                ["platform"] = "windows",
            },
        }, ct);

        _stepsSent = 0;
        _lastError = null;
        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _queue = Channel.CreateUnbounded<ObservedStep>(new UnboundedChannelOptions
        {
            SingleReader = true, // el orden de los pasos es el contrato con Graph
        });

        _pump = Task.Run(() => PumpAsync(_cts.Token));

        _surface.StepObserved += OnStepObserved;
        _surface.StartObserving();

        return _sessionId;
    }

    /// <summary>
    /// Cierra la grabación: deja de observar, termina de enviar lo encolado y le pide a Graph que
    /// post-procese y persista. Devuelve el workflow ya guardado.
    /// </summary>
    public async Task<FinishResponse> StopAsync(CancellationToken ct)
    {
        if (!IsRecording) throw new InvalidOperationException("No hay ninguna grabación en curso.");

        _surface.StopObserving();
        _surface.StepObserved -= OnStepObserved;

        // Se cierra la cola y se espera a que el worker vacíe lo pendiente: los últimos pasos del
        // operador son tan parte del workflow como los primeros.
        _queue?.Writer.TryComplete();
        if (_pump != null)
        {
            try { await _pump.WaitAsync(TimeSpan.FromSeconds(30), ct); }
            catch (TimeoutException) { _lastError = "Algunos pasos no llegaron a enviarse antes de cerrar."; }
            catch (OperationCanceledException) { }
        }

        string sessionId = _sessionId;
        _sessionId = "";

        try
        {
            return await _graph.FinishSessionAsync(sessionId, ct);
        }
        finally
        {
            _cts?.Cancel();
            _cts?.Dispose();
            _cts = null;
            _pump = null;
            _queue = null;
        }
    }

    /// <summary>Adjunta contexto en texto a la grabación (lo que el operador explica de viva voz).</summary>
    public async Task AddContextAsync(string transcript, CancellationToken ct)
    {
        if (!IsRecording) throw new InvalidOperationException("No hay ninguna grabación en curso.");
        await _graph.AddContextNoteAsync(_sessionId, transcript, ct);
    }

    private void OnStepObserved(object? sender, ObservedStep step)
    {
        // Corre en el hilo de la superficie: encolar y salir. Nada de I/O aquí.
        _queue?.Writer.TryWrite(step);
    }

    private async Task PumpAsync(CancellationToken ct)
    {
        var reader = _queue?.Reader;
        if (reader == null) return;

        try
        {
            await foreach (ObservedStep step in reader.ReadAllAsync(ct))
            {
                try
                {
                    await _graph.RecordStepAsync(_sessionId, ToRequest(step), ct);
                    _stepsSent++;
                    _lastError = null;
                }
                catch (OperationCanceledException) { throw; }
                catch (Exception e)
                {
                    // Un paso perdido no aborta la grabación: mejor un workflow con un hueco (que el
                    // post-procesado de Graph puede limpiar) que perder toda la sesión del operador.
                    _lastError = e.Message;
                }

                Progress?.Invoke(this, Status);
            }
        }
        catch (OperationCanceledException) { /* cierre normal */ }
    }

    private static StepRequest ToRequest(ObservedStep step) => new()
    {
        ActionType = step.ActionType,
        Selector = step.Selector,
        Label = step.Label,
        ControlType = step.ControlType,
        Value = step.Value,
        SelectedValue = step.SelectedValue,
        SelectedLabel = step.SelectedLabel,
        AllowedOptions = step.AllowedOptions?.ToList(),
        SurfaceSection = step.SurfaceSection,
        SurfaceHints = step.AlternativeTargets.Count > 0
            ? new Dictionary<string, object> { ["alternativeTargets"] = step.AlternativeTargets }
            : null,
    };

    public async ValueTask DisposeAsync()
    {
        if (IsRecording)
        {
            try { await StopAsync(CancellationToken.None); } catch { }
        }
        _cts?.Cancel();
        _cts?.Dispose();
    }
}
