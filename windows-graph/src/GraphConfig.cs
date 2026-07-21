using System.IO;
using System.Text.Json;

namespace U.Graph;

/// <summary>
/// Lo único que el módulo necesita saber: dónde está Graph y con qué API key hablarle.
///
/// Se persiste en %APPDATA%\U\graph.json, SEPARADO del config.json del asistente: son dos backends
/// distintos con credenciales distintas, y mezclarlos ataría este módulo al resto de la app.
///
/// La key NO tiene default hardcodeado a propósito. Un token en el código fuente queda en el binario
/// (y en el historial de Git) para siempre; cualquiera que descompile U.exe se lo lleva. Se configura
/// en la máquina del cliente, o por la variable de entorno GRAPH_API_KEY.
/// </summary>
public sealed class GraphConfig
{
    /// <summary>
    /// Base del backend Graph. Sin barra final. El dominio viejo (graph-five-orpin) quedó degradado;
    /// el deploy sano vive en graph-eight-pied. Sigue siendo sobreescribible por %APPDATA%\U\graph.json.
    /// </summary>
    public string BaseUrl { get; set; } = "https://graph-eight-pied.vercel.app";

    /// <summary>API key permanente. Viaja como X-API-Key en todas las rutas /api/v1.</summary>
    public string? ApiKey { get; set; }

    /// <summary>Identifica esta instalación en los workflows que graba. Útil con varios clientes.</summary>
    public string AppId { get; set; } = "windows-u";

    /// <summary>Pausa entre pasos al ejecutar un plan. La UI de destino necesita respirar.</summary>
    public int StepDelayMs { get; set; } = 250;

    private static string Path =>
        System.IO.Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "U", "graph.json");

    public static GraphConfig Load()
    {
        GraphConfig cfg;
        try
        {
            cfg = File.Exists(Path)
                ? JsonSerializer.Deserialize<GraphConfig>(File.ReadAllText(Path)) ?? new GraphConfig()
                : new GraphConfig();
        }
        catch
        {
            cfg = new GraphConfig();
        }

        // La variable de entorno gana: permite instalar sin escribir la key en disco.
        string? fromEnv = Environment.GetEnvironmentVariable("GRAPH_API_KEY");
        if (!string.IsNullOrWhiteSpace(fromEnv)) cfg.ApiKey = fromEnv.Trim();

        // Migración silenciosa: los graph.json guardados antes del cambio de dominio traen el deploy
        // degradado (graph-five-orpin) persistido, y sin esto ninguna instalación existente se
        // movería sola al deploy sano. Solo se toca el valor si es EXACTAMENTE el default viejo:
        // una URL puesta a mano (p.ej. un entorno de pruebas) se respeta.
        if (string.Equals(cfg.BaseUrl?.TrimEnd('/'), "https://graph-five-orpin.vercel.app",
                StringComparison.OrdinalIgnoreCase))
        {
            cfg.BaseUrl = "https://graph-eight-pied.vercel.app";
        }

        return cfg;
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
            File.WriteAllText(Path, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* si %APPDATA% no es escribible, se sigue con lo que haya en memoria */ }
    }

    public bool IsConfigured => !string.IsNullOrWhiteSpace(BaseUrl) && !string.IsNullOrWhiteSpace(ApiKey);
}
