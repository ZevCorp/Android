using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;
using U.Graph;
using U.Graph.Surfaces;
using U.WindowsClient.Backend;
using U.WindowsClient.Diagnostics;
using U.WindowsClient.Teach;

namespace U.WindowsClient.Ui;

/// <summary>
/// Enseñar/ejecutar workflows contra Graph (windows-graph) + autofill en vivo por nota. Es la única
/// ventana que sabe de <c>U.Graph</c>: FaceWindow solo la abre, no conoce nada de lo que hay dentro.
/// </summary>
public partial class WorkflowLibraryWindow : Window
{
    private readonly GraphConfig _graphConfig;
    private readonly BackendClient _backend;
    private readonly VideoLibrary _videoLibrary;
    private readonly string _userId;

    private GraphClient _graphClient;
    private readonly UiaSurface _uia = new();
    private readonly SapGuiSurface _sap = new();
    private WorkflowPlayer _player;

    private WorkflowTeachSession? _teachSession;
    private bool _teaching;
    private CancellationTokenSource? _runCts;

    private readonly DispatcherTimer _noteDebounce;
    private readonly List<FulfilledField> _fulfilled = new();
    private readonly string _autofillSessionId = Guid.NewGuid().ToString();
    private readonly List<AutofillRow> _rows = new();

    public WorkflowLibraryWindow(
        GraphConfig graphConfig, BackendClient backend, VideoLibrary videoLibrary, string userId)
    {
        InitializeComponent();
        _graphConfig = graphConfig;
        _backend = backend;
        _videoLibrary = videoLibrary;
        _userId = userId;

        _graphClient = new GraphClient(_graphConfig);
        _player = new WorkflowPlayer(_graphClient, _graphConfig, _uia, _sap);
        _player.StepDone += (_, outcome) => Dispatcher.Invoke(() => AppendProgress(outcome));

        _noteDebounce = new DispatcherTimer(DispatcherPriority.Background) { Interval = TimeSpan.FromMilliseconds(700) };
        _noteDebounce.Tick += async (_, _) => { _noteDebounce.Stop(); await RunAutofillMatchAsync(); };

        Loaded += (_, _) =>
        {
            GraphUrl.Text = _graphConfig.BaseUrl;
            GraphApiKey.Text = _graphConfig.ApiKey ?? "";
            _ = ReloadWorkflowsAsync();
        };
    }

    // ── Conexión ─────────────────────────────────────────────────────────────

    private void OnSaveConnection(object sender, RoutedEventArgs e)
    {
        _graphConfig.BaseUrl = GraphUrl.Text.Trim();
        _graphConfig.ApiKey = string.IsNullOrWhiteSpace(GraphApiKey.Text) ? null : GraphApiKey.Text.Trim();
        _graphConfig.Save();
        // GraphClient fija el header X-API-Key al construirse: si la key cambió, hay que recrearlo.
        _graphClient = new GraphClient(_graphConfig);
        _player = new WorkflowPlayer(_graphClient, _graphConfig, _uia, _sap);
        _player.StepDone += (_, outcome) => Dispatcher.Invoke(() => AppendProgress(outcome));
        ConnStatus.Text = "Guardado.";
    }

    private async void OnTestConnection(object sender, RoutedEventArgs e)
    {
        TestConnBtn.IsEnabled = false;
        ConnStatus.Text = "Probando…";
        try
        {
            JsonElement manifest = await _graphClient.ManifestAsync(CancellationToken.None);
            ConnStatus.Text = $"OK — {manifest}";
            LogBus.Log("workflow-ui", $"ManifestAsync OK: {manifest}");
        }
        catch (Exception ex)
        {
            ConnStatus.Text = $"Falló: {ex.Message}";
            LogBus.Log("workflow-ui", $"ManifestAsync falló: {ex}");
        }
        finally { TestConnBtn.IsEnabled = true; }
    }

    // ── Enseñar ──────────────────────────────────────────────────────────────

    private async void OnToggleTeach(object sender, RoutedEventArgs e)
    {
        if (_teaching) await StopTeachingAsync();
        else await StartTeachingAsync();
    }

    private async Task StartTeachingAsync()
    {
        string description = TeachDescription.Text.Trim();
        if (description.Length == 0)
        {
            StatusLine.Text = "Escribe una descripción corta del workflow antes de enseñar.";
            return;
        }

        _teachSession = new WorkflowTeachSession(
            _graphClient, _graphConfig, _uia, _sap, _backend, _videoLibrary, _userId);
        _teachSession.StatusChanged += (_, msg) => Dispatcher.Invoke(() => { StatusLine.Text = msg; AppendProgressLine(msg); });

        TeachBtn.IsEnabled = false;
        try
        {
            await _teachSession.StartAsync(description, CancellationToken.None);
            SetTeachingUi(true);
        }
        catch (Exception ex)
        {
            LogBus.Log("workflow-ui", $"no se pudo iniciar la enseñanza de workflow: {ex}");
            StatusLine.Text = $"No se pudo iniciar: {ex.Message}";
            await _teachSession.DisposeAsync();
            _teachSession = null;
        }
        finally { TeachBtn.IsEnabled = true; }
    }

    private async Task StopTeachingAsync()
    {
        SetTeachingUi(false);
        if (_teachSession == null) return;

        StatusLine.Text = "Deteniendo y estructurando el workflow…";
        try
        {
            FinishResponse finish = await _teachSession.StopAsync(CancellationToken.None);
            StatusLine.Text = string.IsNullOrWhiteSpace(finish.Error)
                ? $"Workflow guardado: {finish.Summary}"
                : $"Graph reportó un error al cerrar: {finish.Error}";
            AppendProgressLine(StatusLine.Text);
            TeachDescription.Text = "";
            await ReloadWorkflowsAsync();
        }
        catch (Exception ex)
        {
            LogBus.Log("workflow-ui", $"error al detener la enseñanza de workflow: {ex}");
            StatusLine.Text = $"Error al detener: {ex.Message}";
        }
        finally
        {
            await _teachSession.DisposeAsync();
            _teachSession = null;
        }
    }

    private void SetTeachingUi(bool teaching)
    {
        _teaching = teaching;
        TeachBtn.Content = teaching ? "⏸ Detener enseñanza" : "● Enseñar workflow";
        TeachBtn.Background = new System.Windows.Media.SolidColorBrush(teaching
            ? System.Windows.Media.Color.FromRgb(255, 59, 48)
            : System.Windows.Media.Color.FromRgb(34, 34, 34));
        TeachDescription.IsEnabled = !teaching;
    }

    // ── Lista + ejecución ────────────────────────────────────────────────────

    private async void OnReload(object sender, RoutedEventArgs e) => await ReloadWorkflowsAsync();

    private async Task ReloadWorkflowsAsync()
    {
        ReloadBtn.IsEnabled = false;
        try
        {
            var raw = await _graphClient.ListWorkflowsAsync(CancellationToken.None);
            if (raw.Count > 0)
                LogBus.Log("workflow-ui", $"primer workflow crudo (para ajustar el parseo si hace falta): {raw[0]}");

            WorkflowList.ItemsSource = raw.Select(WorkflowSummary.FromJson).ToList();
            StatusLine.Text = $"{raw.Count} workflow(s) cargado(s).";
        }
        catch (Exception ex)
        {
            LogBus.Log("workflow-ui", $"ListWorkflowsAsync falló: {ex}");
            StatusLine.Text = $"No se pudo listar workflows: {ex.Message}";
        }
        finally { ReloadBtn.IsEnabled = true; }
    }

    private async void OnRunSelected(object sender, RoutedEventArgs e)
    {
        if (WorkflowList.SelectedItem is not WorkflowSummary wf)
        {
            StatusLine.Text = "Selecciona un workflow de la lista primero.";
            return;
        }

        ProgressLog.Text = "";
        RunBtn.IsEnabled = false;
        StatusLine.Text = $"Ejecutando «{wf.Title}»…";
        _runCts = new CancellationTokenSource();
        try
        {
            RunResult result = await _player.RunAsync(wf.Id, null, ForceSurface.IsChecked != true, _runCts.Token);
            StatusLine.Text = result.Ok
                ? $"«{wf.Title}» terminó bien: {result.Completed}/{result.Steps.Count} pasos."
                : $"«{wf.Title}» se detuvo: {result.Error}";
        }
        catch (Exception ex)
        {
            LogBus.Log("workflow-ui", $"RunAsync falló: {ex}");
            StatusLine.Text = $"Error ejecutando: {ex.Message}";
        }
        finally { RunBtn.IsEnabled = true; _runCts = null; }
    }

    private void AppendProgress(StepOutcome o) =>
        AppendProgressLine($"{(o.Ok ? "✅" : "❌")} paso {o.StepOrder} · {o.ActionType} «{o.Label}»" +
            (o.Ok ? "" : $" — {o.Error}"));

    private void AppendProgressLine(string line) => ProgressLog.Text += line + "\n";

    // ── Autofill en vivo ─────────────────────────────────────────────────────

    private void OnNoteChanged(object sender, TextChangedEventArgs e)
    {
        _noteDebounce.Stop();
        _noteDebounce.Start();
    }

    private async Task RunAutofillMatchAsync()
    {
        string note = NoteBox.Text;
        if (string.IsNullOrWhiteSpace(note)) return;

        IUiSurface surface = SurfaceDetector.Detect(_uia, _sap);
        AutofillStatus.Text = $"Leyendo campos de «{surface.Name}»…";
        List<DetectedField> fields;
        try { fields = await Task.Run(() => surface.ReadFields().ToList()); }
        catch (Exception ex) { AutofillStatus.Text = $"No se pudieron leer los campos: {ex.Message}"; return; }

        if (fields.Count == 0) { AutofillStatus.Text = "No se detectaron campos en la pantalla actual."; return; }

        try
        {
            var result = await _graphClient.MatchAsync(new AutofillRequest
            {
                SessionId = _autofillSessionId,
                PageUrl = surface.Identity().Url,
                NoteContent = note,
                Fields = fields,
                AlreadyFulfilled = _fulfilled,
            }, CancellationToken.None);

            _rows.Clear();
            foreach (var m in result.Matches)
            {
                var field = fields.FirstOrDefault(f => f.StepOrder == m.StepOrder);
                if (field != null) _rows.Add(new AutofillRow(field, m));
            }
            MatchesList.ItemsSource = null;
            MatchesList.ItemsSource = _rows;
            AutofillStatus.Text = $"{_rows.Count} campo(s) propuesto(s) — nunca se aplican solos.";
        }
        catch (Exception ex)
        {
            LogBus.Log("workflow-ui", $"MatchAsync falló: {ex}");
            AutofillStatus.Text = $"No se pudo pedir el match a Graph: {ex.Message}";
        }
    }

    private async void OnApplyMatch(object sender, RoutedEventArgs e)
    {
        if (((Button)sender).Tag is not AutofillRow row) return;

        IUiSurface surface = SurfaceDetector.Detect(_uia, _sap);
        var step = PlanStep.ForAutofill(row.Field, row.Match);

        bool ok = false; string err = "";
        await Task.Run(() => ok = surface.Execute(step, out err));

        if (ok)
        {
            _fulfilled.Add(new FulfilledField { StepOrder = row.Match.StepOrder, Value = row.Match.Value });
            AutofillStatus.Text = $"Aplicado: {row.Label}";
        }
        else
        {
            AutofillStatus.Text = $"No se pudo aplicar «{row.Label}»: {err}";
        }

        RemoveRow(row);
    }

    private void OnRejectMatch(object sender, RoutedEventArgs e)
    {
        if (((Button)sender).Tag is AutofillRow row) RemoveRow(row);
    }

    private void RemoveRow(AutofillRow row)
    {
        _rows.Remove(row);
        MatchesList.ItemsSource = null;
        MatchesList.ItemsSource = _rows;
    }

    protected override void OnClosed(EventArgs e)
    {
        _noteDebounce.Stop();
        _runCts?.Cancel();
        if (_teaching) _ = StopTeachingAsync();
        base.OnClosed(e);
    }
}

/// <summary>
/// Fila liviana para el panel de autofill: el campo detectado (de dónde tocar) + el match propuesto
/// (qué poner). Se reconstruye en un PlanStep solo cuando el operador aprieta "Aplicar".
/// </summary>
public sealed class AutofillRow
{
    public DetectedField Field { get; }
    public FieldMatch Match { get; }

    public AutofillRow(DetectedField field, FieldMatch match) { Field = field; Match = match; }

    public string Label => Field.Label;
    public string Value => Match.Value;
    public string ConfidenceLabel => $"confianza {Match.Confidence:P0}" +
        (string.IsNullOrWhiteSpace(Match.Evidence) ? "" : $" — \"{Match.Evidence}\"");
}

/// <summary>
/// Vista liviana de un workflow para la lista. El shape exacto de GET /api/v1/workflows no está
/// documentado en este repo (vive en el backend externo) — el parseo tolera varias formas razonables
/// y el primer resultado crudo se loguea a LogBus para ajustar esto con datos reales.
/// </summary>
public sealed class WorkflowSummary
{
    public string Id { get; init; } = "";
    public string Title { get; init; } = "";
    public int StepCount { get; init; }

    public override string ToString() => $"{Title} · {StepCount} paso(s)";

    public static WorkflowSummary FromJson(JsonElement e)
    {
        string id = TryGetString(e, "id") ?? TryGetString(e, "workflowId") ?? TryGetString(e, "workflow_id") ?? "";
        string title = TryGetString(e, "description") ?? TryGetString(e, "title") ?? TryGetString(e, "name") ?? "(sin nombre)";
        int steps = TryGetInt(e, "stepCount") ?? TryGetInt(e, "step_count") ?? CountArray(e, "steps") ?? 0;
        return new WorkflowSummary { Id = id, Title = title, StepCount = steps };
    }

    private static string? TryGetString(JsonElement e, string prop) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(prop, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() : null;

    private static int? TryGetInt(JsonElement e, string prop) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(prop, out var v) && v.ValueKind == JsonValueKind.Number
            ? v.GetInt32() : null;

    private static int? CountArray(JsonElement e, string prop) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(prop, out var v) && v.ValueKind == JsonValueKind.Array
            ? v.GetArrayLength() : null;
}
