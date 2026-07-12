using System.Windows;
using System.Windows.Input;
using U.WindowsClient.Agent;
using U.WindowsClient.Backend;
using U.WindowsClient.Mcp;
using U.WindowsClient.SystemApi;
using U.WindowsClient.Uia;
using U.WindowsClient.Voice;

namespace U.WindowsClient.Ui;

/// <summary>
/// La carita flotante: el frontend completo. Recoge lo que el usuario pide (texto o voz), lanza el
/// <see cref="AgentLoop"/> (que consulta al cerebro remoto) y muestra narración/estado. Implementa
/// <see cref="IVoice"/> y <see cref="IUserChannel"/> para que el bucle hable y pregunte por esta UI.
/// No contiene ninguna lógica de decisión.
/// </summary>
public partial class FaceWindow : Window, IVoice, IUserChannel
{
    private Config _config = Config.Load();
    private readonly UiaReader _uia = new();
    private readonly VoiceIO _voice = new();
    private AgentLoop _loop = null!;
    private CancellationTokenSource? _cts;

    // Para resolver preguntas del asistente desde la caja de texto.
    private TaskCompletionSource<string>? _pendingAnswer;

    public FaceWindow()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object? sender, RoutedEventArgs e)
    {
        // Esquina inferior derecha por defecto.
        var wa = SystemParameters.WorkArea;
        Left = wa.Right - Width - 24;
        Top = wa.Bottom - Height - 24;

        BackendUrl.Text = _config.BackendUrl;
        ClientToken.Text = _config.ClientToken ?? "";

        var mcp = new LocalMcp(_uia);
        var backend = new BackendClient(_config);
        _loop = new AgentLoop(backend, _uia, mcp, this, this, InstalledApps.List);

        Header.MouseLeftButtonDown += (_, ev) => { if (ev.ButtonState == MouseButtonState.Pressed) DragMove(); };
    }

    // --- Entrada del usuario ---

    private void OnInputKey(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        string text = Input.Text.Trim();
        Input.Clear();
        if (text.Length == 0) return;

        // Si el asistente está esperando una respuesta a su pregunta, esto la resuelve.
        if (_pendingAnswer != null && !_pendingAnswer.Task.IsCompleted)
        {
            _pendingAnswer.TrySetResult(text);
            return;
        }
        _ = StartGoal(text);
    }

    private async void OnMic(object sender, RoutedEventArgs e)
    {
        SetStatus("Escuchando…");
        string heard = await _voice.ListenOnceAsync(CancellationToken.None);
        if (string.IsNullOrWhiteSpace(heard)) { SetStatus("No te escuché"); return; }
        if (_pendingAnswer != null && !_pendingAnswer.Task.IsCompleted) { _pendingAnswer.TrySetResult(heard); return; }
        _ = StartGoal(heard);
    }

    private void OnStop(object sender, RoutedEventArgs e)
    {
        _cts?.Cancel();
        _pendingAnswer?.TrySetResult("");
        SetStatus("Detenido");
        StopBtn.Visibility = Visibility.Collapsed;
    }

    private void OnSaveConfig(object sender, RoutedEventArgs e)
    {
        _config.BackendUrl = BackendUrl.Text.Trim();
        _config.ClientToken = string.IsNullOrWhiteSpace(ClientToken.Text) ? null : ClientToken.Text.Trim();
        _config.Save();
        // Recablea el cliente con la nueva URL/token.
        var mcp = new LocalMcp(_uia);
        _loop = new AgentLoop(new BackendClient(_config), _uia, mcp, this, this, InstalledApps.List);
        SetStatus("Backend guardado");
    }

    private async Task StartGoal(string goal)
    {
        _cts = new CancellationTokenSource();
        StopBtn.Visibility = Visibility.Visible;
        SetStatus("Pensando…");
        try
        {
            string summary = await _loop.RunAsync(goal, _cts.Token);
            SetStatus(summary);
        }
        catch (OperationCanceledException) { SetStatus("Detenido"); }
        catch (Exception ex) { SetStatus($"Error: {ex.Message}"); }
        finally { StopBtn.Visibility = Visibility.Collapsed; }
    }

    // --- IVoice ---
    public void Narrate(string text) => Dispatcher.Invoke(() => Bubble.Text = text);
    public void Speak(string text)
    {
        Dispatcher.Invoke(() => { Bubble.Text = text; SetStatus(text); });
        _voice.Speak(text);
    }

    // --- IUserChannel ---
    public Task<string> AskAsync(string question, CancellationToken ct)
    {
        Dispatcher.Invoke(() =>
        {
            SetStatus(question);
            Input.Focus();
        });
        _pendingAnswer = new TaskCompletionSource<string>();
        ct.Register(() => _pendingAnswer?.TrySetResult(""));
        return _pendingAnswer.Task;
    }

    private void SetStatus(string s) => Dispatcher.Invoke(() => Status.Text = s.Length > 120 ? s[..120] + "…" : s);
}
