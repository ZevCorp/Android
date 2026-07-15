using System.IO;
using System.Linq;

namespace U.WindowsClient.Teach;

/// <summary>Un video de enseñanza grabado localmente.</summary>
public sealed record TeachVideo(string Path, string FileName, DateTime RecordedAt, long SizeBytes, string? Summary);

/// <summary>
/// Guarda y lista los videos de enseñanza EN EL DISCO DEL USUARIO, sin límite ni borrado automático:
/// a diferencia de Android (que sube el mp4, lo procesa y lo borra), aquí el usuario pidió poder
/// verlos después, así que se conservan hasta que él mismo los borre.
///
/// Carpeta: %LOCALAPPDATA%\U\teach-videos, NO %APPDATA% (Roaming). Roaming (y buena parte de
/// Documentos, como demuestra que este propio repo vive bajo OneDrive\Documentos) puede sincronizarse
/// con la nube; LocalAppData está pensado explícitamente para datos que se quedan en la máquina, que
/// es justo el requisito: video de pantalla del usuario, potencialmente pesado y sensible.
/// </summary>
public sealed class VideoLibrary
{
    public string Folder { get; }

    public VideoLibrary(string? folder = null)
    {
        Folder = folder ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "U", "teach-videos");
        Directory.CreateDirectory(Folder);
    }

    /// <summary>Nombre de archivo para una grabación nueva. La fecha en el nombre hace la carpeta hojeable sin abrir nada.</summary>
    public string NewRecordingPath() =>
        Path.Combine(Folder, $"teach_{DateTime.Now:yyyy-MM-dd_HH-mm-ss}.mp4");

    /// <summary>Todos los videos guardados, del más reciente al más antiguo.</summary>
    public IReadOnlyList<TeachVideo> List()
    {
        if (!Directory.Exists(Folder)) return Array.Empty<TeachVideo>();

        var videos = new List<TeachVideo>();
        foreach (string path in Directory.EnumerateFiles(Folder, "*.mp4"))
        {
            try
            {
                var info = new FileInfo(path);
                if (info.Length == 0) continue; // grabación que quedó a medias
                videos.Add(new TeachVideo(
                    Path: path,
                    FileName: info.Name,
                    RecordedAt: info.LastWriteTime,
                    SizeBytes: info.Length,
                    Summary: ReadSummary(path)));
            }
            catch { /* archivo bloqueado o borrado entre el enumerate y el stat */ }
        }
        return videos.OrderByDescending(v => v.RecordedAt).ToList();
    }

    /// <summary>Guarda el resumen que devolvió el backend junto al video, en un .txt homónimo.</summary>
    public void SaveSummary(string videoPath, string summary)
    {
        if (string.IsNullOrWhiteSpace(summary)) return;
        try { File.WriteAllText(SummaryPathFor(videoPath), summary); } catch { }
    }

    private static string? ReadSummary(string videoPath)
    {
        try
        {
            string p = SummaryPathFor(videoPath);
            return File.Exists(p) ? File.ReadAllText(p).Trim() : null;
        }
        catch { return null; }
    }

    private static string SummaryPathFor(string videoPath) =>
        Path.ChangeExtension(videoPath, ".txt");

    public bool Delete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
            string summary = SummaryPathFor(path);
            if (File.Exists(summary)) File.Delete(summary);
            return true;
        }
        catch { return false; }
    }
}
