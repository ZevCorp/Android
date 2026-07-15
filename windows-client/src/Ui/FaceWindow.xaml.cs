using System.Windows;
using System.Windows.Input;
using U.WindowsClient.Agent;
using U.WindowsClient.Backend;
using U.WindowsClient.Diagnostics;
using U.WindowsClient.Mcp;
using U.WindowsClient.SystemApi;
using U.WindowsClient.Teach;
using U.WindowsClient.Uia;
using U.WindowsClient.Update;
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
    private Updater? _updater;
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
        GeminiApiKey.Text = _config.GeminiApiKey ?? "";
        SetMuted(_config.Muted); // si lo silenciaron en una sesión anterior, sigue mudo

        var mcp = new LocalMcp(_uia);
        _backend = new BackendClient(_config);
        _loop = new AgentLoop(_backend, _uia, mcp, this, this, InstalledApps.List);

        Header.MouseLeftButtonDown += (_, ev) => { if (ev.ButtonState == MouseButtonState.Pressed) DragMove(); };

        StartUpdater();
    }

    // --- Auto-actualización ---

    /// <summary>
    /// Arranca el sondeo de versiones nuevas. El usuario no toca nada: si aparece una, se descarga en
    /// segundo plano y recién ahí asoma la pastilla. Ver <see cref="Updater"/>.
    /// </summary>
    private void StartUpdater()
    {
        _updater = new Updater(_config.UpdateFeedUrl);
        VersionText.Text = $"Versión {_updater.CurrentVersion}";
        // UpdateReady llega desde un hilo del pool, no del Dispatcher: tocar la UI directo reventaría.
        _updater.UpdateReady += version => Dispatcher.Invoke(() =>
        {
            UpdateBtn.Content = $"⬇ Versión {version} lista — reiniciar";
            UpdateBtn.Visibility = Visibility.Visible;
        });
        _updater.Start();
    }

    private void OnApplyUpdate(object sender, RoutedEventArgs e)
    {
        SetStatus("Actualizando Ü…");
        _updater?.ApplyAndRestart(); // no retorna: reinicia el proceso
    }

    /// <summary>
    /// Si el usuario nunca tocó la pastilla, la versión descargada se instala al cerrar: el próximo
    /// arranque ya es la nueva, sin que él haya hecho nada.
    /// </summary>
    protected override void OnClosed(EventArgs e)
    {
        _updater?.ApplyOnExit();
        base.OnClosed(e);
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
        // Cancelar el bucle no callaba lo ya encolado en el TTS: se pedía parar y Ü seguía hablando
        // hasta terminar la frase. Detener es detener, también la voz.
        _voice.Silence();
        SetStatus("Detenido");
        StopBtn.Visibility = Visibility.Collapsed;
    }

    /// <summary>
    /// Callar/dejar hablar a Ü. Un solo clic corta la frase en curso y lo deja mudo — antes, si arrancaba
    /// a hablar en mal momento, no había forma de pararlo salvo cerrar la aplicación.
    /// </summary>
    private void OnToggleMute(object sender, RoutedEventArgs e)
    {
        SetMuted(!_voice.Muted);
        _config.Save();
        SetStatus(_voice.Muted ? "Ü en silencio" : "Ü vuelve a hablar");
    }

    private void SetMuted(bool muted)
    {
        _voice.Muted = muted;   // el setter ya corta en seco lo que estuviera diciendo
        _config.Muted = muted;
        MuteBtn.Content = muted ? "🔇" : "🔊";
        MuteBtn.ToolTip = muted ? "Ü está en silencio — clic para que vuelva a hablar" : "Callar a Ü ahora mismo";
    }

    // --- Enseñanza activa (grabar pantalla+voz) ---

    private async void OnToggleTeach(object sender, RoutedEventArgs e)
    {
        if (_teaching) await StopTeachingAsync();
        else await StartTeachingAsync();
    }

    /// <summary>
    /// ANTES esto llamaba a `_ = _teachSession.StartAsync()` sin esperar ni capturar la excepción: si
    /// GeminiApiKey estaba vacía, StartAsync lanzaba de inmediato pero el error se perdía en el aire
    /// (fire-and-forget) — no quedaba grabación, y el usuario recién se enteraba minutos después con
    /// un "No hay grabación para procesar" que no explicaba nada. Ahora se espera de verdad y, si
    /// falla, el botón vuelve a su estado normal y el error queda visible (estado + LogBus).
    /// </summary>
    private async Task StartTeachingAsync()
    {
        _teachSession = new TeachSession(_backend!, _videoLibrary, _config.UserId,
            _config.GeminiApiKey ?? "", _config.GeminiModel);
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

        SetTeachingUi(true);
    }

    /// <summary>Botón 🎓 en rojo y 🔄 a la vista mientras se graba; todo de vuelta a lo normal si no.</summary>
    private void SetTeachingUi(bool teaching)
    {
        _teaching = teaching;
        TeachBtn.Content = teaching ? "⏸ Enseñar" : "🎓 Enseñar";
        TeachBtn.Background = new System.Windows.Media.SolidColorBrush(teaching
            ? System.Windows.Media.Color.FromRgb(255, 59, 48)  // rojo, como StopBtn
            : System.Windows.Media.Color.FromRgb(34, 34, 34)); // gris normal
        RestartTeachBtn.Visibility = teaching ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>
    /// "Me equivoqué": tira lo grabado y vuelve a grabar desde cero. Sin esto, un error a mitad de la
    /// demostración solo se podía resolver deteniendo — y detener manda el video malo a Gemini y sus
    /// notas al cerebro, que es exactamente lo que no queremos que aprenda.
    /// </summary>
    private async void OnRestartTeach(object sender, RoutedEventArgs e)
    {
        if (!_teaching || _teachSession == null) return;

        // Descartar y rearrancar la grabación no es instantáneo (hay que esperar a que el mp4 se cierre
        // en disco). Se bloquean AMBOS botones mientras tanto: un segundo toque en 🔄 dejaría grabadores
        // huérfanos, y un ⏸ en medio intentaría detener una grabación que ya no existe.
        RestartTeachBtn.IsEnabled = false;
        TeachBtn.IsEnabled = false;
        SetStatus("Descartando lo grabado y empezando de nuevo…");
        try
        {
            await _teachSession.RestartAsync();
        }
        catch (Exception ex)
        {
            // Se descartó lo viejo pero no arrancó lo nuevo: no hay grabación en curso, así que la UI
            // no puede seguir diciendo que sí.
            LogBus.Log("teach", $"no se pudo reiniciar la enseñanza: {ex}");
            SetStatus($"No se pudo volver a grabar: {ex.Message}");
            await _teachSession.DisposeAsync();
            _teachSession = null;
            SetTeachingUi(false);
        }
        finally
        {
            RestartTeachBtn.IsEnabled = true;
            TeachBtn.IsEnabled = true;
        }
    }

    private async Task StopTeachingAsync()
    {
        SetTeachingUi(false);
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
        _config.GeminiApiKey = string.IsNullOrWhiteSpace(GeminiApiKey.Text) ? null : GeminiApiKey.Text.Trim();
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
