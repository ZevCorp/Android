using System.Windows;
using System.Windows.Input;
using U.WindowsClient.Agent;
using U.WindowsClient.Backend;
using U.WindowsClient.Diagnostics;
using U.WindowsClient.Mcp;
using U.WindowsClient.SystemApi;
using U.WindowsClient.Teach;
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
    private readonly VideoLibrary _videoLibrary = new();
    private AgentLoop _loop = null!;
    private BackendClient? _backend;
    private CancellationTokenSource? _cts;
    private VideoLibraryWindow? _videoWindow;
    private LogWindow? _logWindow;
    private TeachSession? _teachSession;
    private bool _teaching;

    // Para resolver preguntas del asistente desde la caja de texto.
    private TaskCompletionSource<string>? _pendingAnswer;

    public FaceWindow()
    {
        InitializeComponent();
        Loaded += OnLoaded;
    }

    private void OnLoaded(object? sender, RoutedEventArgs e)
    {
        // Esquina inferior derecha por defecto. El panel crece hacia arriba (ver OnSizeChanged) cuando
        // se abre un Expander (p.ej. "Backend"): sin esto, ResizeMode="NoResize" con altura fija
        // recortaba el contenido expandido y quedaba invisible.
        var wa = SystemParameters.WorkArea;
        Left = wa.Right - Width - 24;
        Top = wa.Bottom - ActualHeight - 24;
        SizeChanged += OnSizeChanged;

        BackendUrl.Text = _config.BackendUrl;
        ClientToken.Text = _config.ClientToken ?? "";

        var mcp = new LocalMcp(_uia);
        _backend = new BackendClient(_config);
        _loop = new AgentLoop(_backend, _uia, mcp, this, this, InstalledApps.List);

        Header.MouseLeftButtonDown += (_, ev) => { if (ev.ButtonState == MouseButtonState.Pressed) DragMove(); };
    }

    /// <summary>Mantiene fija la esquina inferior derecha: si la ventana crece (Expander abierto), crece hacia arriba.</summary>
    private void OnSizeChanged(object sender, SizeChangedEventArgs e)
    {
        var wa = SystemParameters.WorkArea;
        Top = wa.Bottom - ActualHeight - 24;
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

    // --- Enseñanza activa (grabar pantalla+voz) ---

    private async void OnToggleTeach(object sender, RoutedEventArgs e)
    {
        if (_teaching) await StopTeachingAsync();
        else await StartTeachingAsync();
    }

    /// <summary>
    /// Se espera de verdad y se capturan los errores: si arrancar la grabación falla (p.ej. la
    /// librería nativa no carga), el botón vuelve a su estado normal y el error queda visible en el
    /// estado y en el LogBus. Cuando esto era `_ = StartAsync()` (fire-and-forget), la excepción se
    /// perdía en el aire y el usuario solo se enteraba minutos después, al detener, con un "no hay
    /// grabación para procesar" que no explicaba nada.
    /// </summary>
    private async Task StartTeachingAsync()
    {
        _teachSession = new TeachSession(_backend!, _videoLibrary, _config.UserId);
        _teachSession.StatusChanged += (_, msg) => SetStatus(msg);

        try
        {
            await _teachSession.StartAsync();
        }
        catch (Exception ex)
        {
            LogBus.Log("teach", $"no se pudo iniciar la enseñanza: {ex}");
            SetStatus($"No se pudo iniciar la grabación: {ex.Message}");
            await _teachSession.DisposeAsync();
            _teachSession = null;
            return;
        }

        _teaching = true;
        TeachBtn.Content = "⏸ Enseñar";
        TeachBtn.Background = new System.Windows.Media.SolidColorBrush(
            System.Windows.Media.Color.FromRgb(255, 59, 48)); // rojo, como StopBtn
    }

    private async Task StopTeachingAsync()
    {
        _teaching = false;
        TeachBtn.Content = "🎓 Enseñar";
        TeachBtn.Background = new System.Windows.Media.SolidColorBrush(
            System.Windows.Media.Color.FromRgb(34, 34, 34)); // gris normal
        SetStatus("Procesando video grabado…");

        if (_teachSession != null)
        {
            try
            {
                await _teachSession.StopAsync();
                string summary = await _teachSession.ProcessAsync();
                // ProcessAsync ya llama a SetStatus con el resumen.
                Speak($"He aprendido: {summary}");
            }
            catch (Exception ex)
            {
                SetStatus($"Error en enseñanza: {ex.Message}");
            }
            finally
            {
                await _teachSession.DisposeAsync();
                _teachSession = null;
            }
        }
    }

    private void OnOpenVideos(object sender, RoutedEventArgs e)
    {
        if (_videoWindow == null || !_videoWindow.IsLoaded)
            _videoWindow = new VideoLibraryWindow(_videoLibrary);
        else
            _videoWindow.Reload();

        _videoWindow.Show();
        _videoWindow.Activate();
    }

    private void OnOpenLogs(object sender, RoutedEventArgs e)
    {
        if (_logWindow == null || !_logWindow.IsLoaded)
            _logWindow = new LogWindow();

        _logWindow.Show();
        _logWindow.Activate();
    }

    private void OnSaveConfig(object sender, RoutedEventArgs e)
    {
        _config.BackendUrl = BackendUrl.Text.Trim();
        _config.ClientToken = string.IsNullOrWhiteSpace(ClientToken.Text) ? null : ClientToken.Text.Trim();
        _config.Save();
        // Recablea el cliente con la nueva URL/token.
        var mcp = new LocalMcp(_uia);
        _backend = new BackendClient(_config);
        _loop = new AgentLoop(_backend, _uia, mcp, this, this, InstalledApps.List);
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
