using System.IO;
using System.Text.Json;

namespace U.WindowsClient;

/// <summary>
/// Configuración del cliente. Lo ÚNICO que necesita saber: dónde está el backend y (opcional) el token
/// para hablarle. Ninguna key de modelo, ningún prompt, ningún parámetro del cerebro vive aquí — todo
/// eso es del servidor, incluida la key de Gemini que usa la enseñanza por video (🎓): el backend
/// firma las subidas y hace las llamadas al modelo, así el usuario no configura nada y no hay ninguna
/// key que extraer del .exe. Se persiste en %APPDATA%\U\config.json.
/// </summary>
public sealed class Config
{
    public string BackendUrl { get; set; } = "https://u-windows-backend.vercel.app";
    public string? ClientToken { get; set; } = "e86d4ec981bba9889aaf69d5ac37a781db288bb2f0d22b0c";
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
