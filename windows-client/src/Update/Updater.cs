using U.WindowsClient.Diagnostics;
using Velopack;

namespace U.WindowsClient.Update;

/// <summary>
/// Auto-actualización del cliente. Es la contraparte de `RELEASING-WINDOWS.md`: nosotros publicamos con
/// `vpk pack` + subir al bucket, y esto lo recoge sin que el usuario haga nada.
///
/// Por qué existe: el cerebro (backend en Vercel) se actualiza solo con un push, pero la carita vive
/// como .exe en la máquina del cliente. Sin esto, cada cambio del cliente exige mandarle un zip y que
/// lo reemplace a mano — insostenible mientras estemos iterando.
///
/// Cómo funciona: sondea el feed al arrancar y cada <see cref="PollInterval"/>, descarga en segundo
/// plano y avisa por <see cref="UpdateReady"/>. La carita muestra entonces una pastilla; si el usuario
/// la toca, reinicia ya (<see cref="ApplyAndRestart"/>); si la ignora, <see cref="ApplyOnExit"/> deja
/// la versión nueva instalada al cerrar. Nunca interrumpe lo que el usuario esté haciendo.
///
/// NO hace nada en desarrollo (`dotnet run`): sin instalación de Velopack detrás, <c>IsInstalled</c> es
/// false y esta clase se apaga entera. Ver <see cref="Enabled"/>.
/// </summary>
public sealed class Updater
{
    /// <summary>Cada cuánto se vuelve a mirar el feed. Igual que Android (RELEASING.md): ~30 min.</summary>
    public static readonly TimeSpan PollInterval = TimeSpan.FromMinutes(30);

    private readonly UpdateManager _mgr;
    private VelopackAsset? _ready;

    /// <summary>Se dispara (con el número de versión) cuando hay una versión ya descargada y lista.</summary>
    public event Action<string>? UpdateReady;

    /// <param name="feedUrl">Base del feed estático; ver <see cref="Config.UpdateFeedUrl"/>.</param>
    public Updater(string feedUrl)
    {
        // Sin canal explícito a propósito: Velopack usa el mismo con el que se empaquetó ("win"), y
        // pasarle uno distinto haría que pidiera un releases.<canal>.json que no existe → 404.
        _mgr = new UpdateManager(feedUrl);
    }

    /// <summary>False en desarrollo o si se corre la carpeta suelta sin instalar: ahí no hay nada que actualizar.</summary>
    public bool Enabled => _mgr.IsInstalled;

    /// <summary>Versión instalada, para mostrar en el panel (soporte: "¿qué versión tenés?").</summary>
    public string CurrentVersion => _mgr.CurrentVersion?.ToString() ?? "dev";

    /// <summary>Arranca el sondeo en segundo plano. No lanza: los fallos de red son normales y se loguean.</summary>
    public void Start()
    {
        if (!Enabled)
        {
            LogBus.Log("update", "auto-update desactivado (no es una instalación Velopack; normal en dotnet run)");
            return;
        }
        _ = PollLoopAsync();
    }

    private async Task PollLoopAsync()
    {
        // Se corta en cuanto hay una lista: ya no hay nada que sondear hasta que se aplique.
        while (_ready == null)
        {
            try
            {
                await CheckOnceAsync();
            }
            catch (Exception ex)
            {
                // Quedarse sin internet, o el bucket caído, no es motivo para molestar al usuario:
                // la carita sigue funcionando con la versión que tiene.
                LogBus.Log("update", $"no se pudo comprobar actualizaciones: {ex.Message}");
            }
            if (_ready != null) break;
            await Task.Delay(PollInterval);
        }
    }

    private async Task CheckOnceAsync()
    {
        UpdateInfo? info = await _mgr.CheckForUpdatesAsync();
        if (info == null) return; // null = estamos al día. No es error.

        string version = info.TargetFullRelease.Version.ToString();
        LogBus.Log("update", $"versión nueva disponible: {version} — descargando…");
        await _mgr.DownloadUpdatesAsync(info);

        _ready = info.TargetFullRelease;
        LogBus.Log("update", $"versión {version} descargada y lista para aplicar");
        UpdateReady?.Invoke(version);
    }

    /// <summary>Aplica ya y relanza la carita. Lo que hace la pastilla al tocarla.</summary>
    public void ApplyAndRestart()
    {
        if (_ready == null) return;
        LogBus.Log("update", "aplicando actualización y reiniciando");
        _mgr.ApplyUpdatesAndRestart(_ready); // no retorna: mata el proceso
    }

    /// <summary>
    /// Si hay una versión descargada que el usuario nunca aplicó, la instala al cerrar la carita, sin
    /// ventanas ni relanzar. El siguiente arranque ya es la versión nueva. Llamar al salir.
    /// </summary>
    public void ApplyOnExit()
    {
        if (_ready == null) return;
        try
        {
            LogBus.Log("update", "aplicando actualización pendiente al salir");
            _mgr.WaitExitThenApplyUpdates(_ready, silent: true, restart: false);
        }
        catch (Exception ex)
        {
            // Fallar aquí solo significa que seguirá en la versión vieja y lo reintentará al arrancar.
            LogBus.Log("update", $"no se pudo dejar la actualización aplicándose al salir: {ex.Message}");
        }
    }
}
