using ScreenRecorderLib;
using U.WindowsClient.Diagnostics;

namespace U.WindowsClient.Teach;

/// <summary>
/// Captura de pantalla + micrófono + audio del PC a mp4 (H.264 video + AAC audio). Usa
/// <c>ScreenRecorderLib</c> (MIT, mantenida activamente) porque Media Foundation nativo es demasiado
/// bajo nivel (SinkWriter, topología manual, sync A/V) y ffmpeg bundleado añade ~50 MB + una zona
/// gris de licencia.
///
/// LAS DOS FUENTES DE AUDIO SE PIDEN EXPLÍCITAMENTE (ver StartAsync): <c>IsAudioEnabled</c> es solo
/// el interruptor maestro, y confiar en los defaults de la librería para el resto dejaba fuera el
/// audio del PC.
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
            LogAudioDevices();

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
                AudioOptions = new AudioOptions
                {
                    IsAudioEnabled = true,

                    // Las dos fuentes, EXPLÍCITAS. IsAudioEnabled es solo el interruptor maestro: deja
                    // qué se graba en manos de los defaults de la librería, y así el audio del PC no
                    // entraba en la grabación. Enseñar necesita ambas: la voz del médico explicando
                    // (entrada) y lo que suena en el sistema — avisos, alertas, videos (salida).
                    IsInputDeviceEnabled = true,   // micrófono
                    IsOutputDeviceEnabled = true,  // audio del PC (loopback de la salida)

                    // Multiplicador, no porcentaje: 1.0f = 100%. Se bajan un punto porque las dos
                    // fuentes se mezclan en una sola pista y sumar dos señales al 100% satura.
                    InputVolume = 1.0f,
                    OutputVolume = 0.8f,

                    // El mp4 lleva una sola pista mezclada; en estéreo la voz se oye más natural.
                    Channels = AudioChannels.Stereo,
                    Bitrate = AudioBitrate.bitrate_128kbps,
                },

                // Sin AudioInputDevice/AudioOutputDevice: la librería usa los predeterminados de
                // Windows, que es lo que el usuario espera (los mismos que oye y con los que habla).
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

    /// <summary>
    /// Deja en el registro qué dispositivos de audio ve Windows. Sin esto, "no se grabó el audio del
    /// PC" es indistinguible de "esta máquina no tiene dispositivo de salida activo" (pasa con
    /// auriculares desconectados, o en escritorio remoto / máquina virtual, donde a menudo no hay
    /// salida sobre la que hacer loopback).
    /// </summary>
    private static void LogAudioDevices()
    {
        try
        {
            var outputs = Recorder.GetSystemAudioDevices(AudioDeviceSource.OutputDevices);
            var inputs = Recorder.GetSystemAudioDevices(AudioDeviceSource.InputDevices);

            LogBus.Log("teach", $"audio — salidas (PC): {Describe(outputs)}");
            LogBus.Log("teach", $"audio — entradas (mic): {Describe(inputs)}");

            if (outputs.Count == 0)
                LogBus.Log("teach", "OJO: sin dispositivo de salida, el audio del PC no se puede grabar.");
            if (inputs.Count == 0)
                LogBus.Log("teach", "OJO: sin micrófono, la narración del médico no se va a grabar.");
        }
        catch (Exception ex)
        {
            // Solo diagnóstico: que falle no debe impedir grabar.
            LogBus.Log("teach", $"no se pudieron enumerar los dispositivos de audio: {ex.Message}");
        }
    }

    private static string Describe(IReadOnlyList<AudioDevice> devices) =>
        devices.Count == 0 ? "(ninguno)" : string.Join(", ", devices.Select(d => d.FriendlyName));

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
