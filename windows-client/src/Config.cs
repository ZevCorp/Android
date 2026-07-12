using System.IO;
using System.Text.Json;

namespace U.WindowsClient;

/// <summary>
/// Configuración del cliente. Lo ÚNICO que necesita saber: dónde está el backend y (opcional) el token
/// para hablarle. Ninguna key de modelo, ningún prompt, ningún parámetro del cerebro vive aquí — todo
/// eso es del servidor. Se persiste en %APPDATA%\U\config.json.
/// </summary>
public sealed class Config
{
    public string BackendUrl { get; set; } = "http://localhost:3000";
    public string? ClientToken { get; set; }
    public string UserId { get; set; } = "anon";

    private static string Path =>
        System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "U", "config.json");

    public static Config Load()
    {
        try
        {
            if (File.Exists(Path))
                return JsonSerializer.Deserialize<Config>(File.ReadAllText(Path)) ?? new Config();
        }
        catch { }
        return new Config();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
            File.WriteAllText(Path, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }
}
