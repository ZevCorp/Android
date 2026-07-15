using System.IO;
using System.Text.Json;

namespace U.WindowsClient;

/// <summary>
/// Configuración del cliente. Para el bucle de ejecución normal, lo ÚNICO que necesita saber es dónde
/// está el backend y (opcional) el token para hablarle — ninguna key de modelo ni prompt vive aquí.
///
/// EXCEPCIÓN, a propósito: <see cref="GeminiApiKey"/>. La enseñanza por video (grabar pantalla → subir
/// a Gemini → procesar) habla directo con Gemini desde el cliente, sin pasar por el backend, porque el
/// video no cabe en el límite de payload de las funciones de Vercel y esperar el procesamiento excede
/// cómodamente su límite de duración. Eso obliga a que el cliente tenga su propia key de Gemini y el
/// prompt de enseñanza embebido — rompe la separación frontend-tonto/backend-cerebro SOLO para esta
/// función. Se acepta el trade-off: la key vive en la máquina del usuario y es extraíble descompilando
/// el .exe. Las notas ya extraídas SÍ vuelven al backend (JSON pequeño, cabe sin problema) para
/// guardarse en el MemoryStore del servidor.
///
/// Se persiste en %APPDATA%\U\config.json.
/// </summary>
public sealed class Config
{
    public string BackendUrl { get; set; } = "https://u-windows-backend.vercel.app";
    public string? ClientToken { get; set; } = "e86d4ec981bba9889aaf69d5ac37a781db288bb2f0d22b0c";
    public string UserId { get; set; } = "anon";

    /// <summary>
    /// Asistente mudo (botón 🔇 de la carita). Se persiste a propósito: quien lo silencia suele estar
    /// en una consulta o una reunión, y que volviera a hablar solo por reiniciar Ü sería justo el
    /// problema que el botón viene a resolver.
    /// </summary>
    public bool Muted { get; set; }

    /// <summary>
    /// De dónde baja la carita sus propias actualizaciones (ver <see cref="Update.Updater"/> y
    /// RELEASING-WINDOWS.md). Es el bucket PÚBLICO `windows` de Supabase — público a propósito: el
    /// updater tiene que poder leerlo sin credenciales, igual que el bucket `apks` de Android. Aquí solo
    /// viajan binarios del cliente, que no contienen secretos del cerebro.
    /// </summary>
    public string UpdateFeedUrl { get; set; } =
        "https://zyvfamlhlmztliexvmej.supabase.co/storage/v1/object/public/windows";

    /// <summary>
    /// Key de Gemini usada SOLO por la enseñanza por video (ver comentario de clase). NUNCA
    /// hardcodear una key real acá: GitHub la detecta como secreto y bloquea el push (ya pasó). Para
    /// desarrollo/pruebas, fijala con la variable de entorno GEMINI_API_KEY (no queda en el repo); en
    /// producción, cada usuario la pega una vez en el panel "Backend" de la carita y se persiste en su
    /// %APPDATA%\U\config.json local, nunca en el código fuente.
    /// </summary>
    public string? GeminiApiKey { get; set; } = Environment.GetEnvironmentVariable("GEMINI_API_KEY");
    public string GeminiModel { get; set; } = "gemini-3.5-flash";

    private static string Path =>
        System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "U", "config.json");

    public static Config Load()
    {
        var loaded = LoadFromDisk();
        // Si ya existía un config.json de una sesión anterior (p.ej. guardado con el panel de ajustes
        // vacío antes de fijar el default), trae "GeminiApiKey": null explícito, que pisaría el nuevo
        // valor por defecto de la clase. Sin esto, hardcodear el default no alcanza a usuarios que ya
        // guardaron configuración una vez.
        if (string.IsNullOrWhiteSpace(loaded.GeminiApiKey))
            loaded.GeminiApiKey = new Config().GeminiApiKey;
        return loaded;
    }

    private static Config LoadFromDisk()
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
