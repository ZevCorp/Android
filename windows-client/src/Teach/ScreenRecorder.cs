using ScreenRecorderLib;
using U.WindowsClient.Diagnostics;

namespace U.WindowsClient.Teach;

/// <summary>
/// Captura de pantalla + micrófono a mp4 (H.264 video + AAC audio). Usa <c>ScreenRecorderLib</c>
/// (MIT, mantenida activamente) porque Media Foundation nativo es demasiado bajo nivel (SinkWriter,
/// topología manual, sync A/V) y ffmpeg bundleado añade ~50 MB + una zona gris de licencia.
///
/// <c>Recorder.Record()</c> arranca la captura en un hilo propio de la librería y vuelve enseguida;
/// el fin real de la grabación se conoce por el evento <c>OnRecordingComplete</c> (se dispara tras
/// <see cref="StopAsync"/>, cuando el mp4 ya quedó cerrado y finalizado en disco).
///
/// <c>OnRecordingFailed</c> se dispara desde un hilo nativo de la librería, fuera de la pila de
/// llamadas de quien invocó <see cref="StartAsync"/>: lanzar una excepción ahí NO la propaga al
/// caller (se pierde, o en el peor caso derriba el proceso). Por eso se guarda en <see cref="LastError"/>
/// y se registra en <see cref="LogBus"/>; quien llama debe revisarlo si algo no cuadra.
/// </summary>
public sealed class ScreenRecorder : IAsyncDisposable
{
    private readonly string _outputPath;
    private Recorder? _recorder;

    public event EventHandler<RecorderStatus>? StatusChanged;

    /// <summary>Último error reportado por la librería, si lo hubo. Null mientras todo va bien.</summary>
    public string? LastError { get; private set; }

    public ScreenRecorder(string outputPath)
    {
        _outputPath = outputPath;
    }

    /// <summary>Inicia grabación de pantalla (monitor principal) + micrófono + audio del sistema.</summary>
    public Task StartAsync(CancellationToken ct = default)
    {
        LogBus.Log("teach", $"iniciando grabación → {_outputPath}");

        try
        {
            // Construir las opciones va DENTRO del try: SourceOptions.MainMonitor hace llamadas nativas
            // (enumeración de pantallas vía DXGI) que también pueden fallar, y antes quedaban fuera de
            // este manejo de errores.
            var options = new RecorderOptions
            {
                SourceOptions = SourceOptions.MainMonitor,
                VideoEncoderOptions = new VideoEncoderOptions
                {
                    Encoder = new H264VideoEncoder { EncoderProfile = H264Profile.Main },
                    Bitrate = 8_000_000,
                    Framerate = 30,
                    IsMp4FastStartEnabled = true, // reproductor no espera a que termine de "cerrar" el archivo
                },
                // Sin especificar AudioInputDevice/AudioOutputDevice, la librería usa los dispositivos
                // predeterminados del sistema (mic + salida), que es justo lo que pide enseñar hablando
                // mientras se usa la pantalla.
                AudioOptions = new AudioOptions { IsAudioEnabled = true },
            };

            _recorder = Recorder.CreateRecorder(options);
            _recorder.OnStatusChanged += (_, args) =>
            {
                LogBus.Log("teach", $"estado de grabación: {args.Status}");
                StatusChanged?.Invoke(this, args.Status);
            };
            _recorder.OnRecordingFailed += (_, args) =>
            {
                LastError = args.Error;
                LogBus.Log("teach", $"ScreenRecorderLib falló: {args.Error}");
            };

            _recorder.Record(_outputPath);
        }
        catch (Exception ex)
        {
            // Esto SÍ está en la pila del caller (falla sincrónica al validar dispositivo/codec):
            // propagar es seguro y correcto.
            LastError = ex.Message;
            LogBus.Log("teach", $"no se pudo iniciar la grabación: {ex}");
            throw;
        }

        return Task.CompletedTask;
    }

    /// <summary>Detiene la grabación y espera a que el mp4 quede finalizado en disco.</summary>
    public Task StopAsync()
    {
        if (_recorder == null) return Task.CompletedTask;

        if (LastError != null)
        {
            // La grabación ya había fallado antes de llegar aquí (p.ej. justo al arrancar): no hay
            // nada que "detener" — OnRecordingComplete nunca va a llegar y esperarlo colgaría.
            LogBus.Log("teach", $"StopAsync: la grabación ya había fallado ({LastError}), no se llama a Stop()");
            return Task.CompletedTask;
        }

        LogBus.Log("teach", "deteniendo grabación…");
        var tcs = new TaskCompletionSource();
        _recorder.OnRecordingComplete += (_, args) =>
        {
            LogBus.Log("teach", $"grabación finalizada en disco: {args.FilePath}");
            tcs.TrySetResult();
        };
        _recorder.OnRecordingFailed += (_, args) =>
        {
            LastError = args.Error;
            LogBus.Log("teach", $"ScreenRecorderLib falló al detener: {args.Error}");
            tcs.TrySetException(new InvalidOperationException($"ScreenRecorderLib falló al detener: {args.Error}"));
        };

        _recorder.Stop();
        return tcs.Task;
    }

    public ValueTask DisposeAsync()
    {
        _recorder?.Dispose();
        _recorder = null;
        return ValueTask.CompletedTask;
    }
}
