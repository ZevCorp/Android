using System.IO;

namespace U.WindowsClient.SystemApi;

/// <summary>
/// Enumera las apps instaladas leyendo los accesos directos del menú Inicio. El cliente incluye esta
/// lista en cada estado para que el cerebro pueda resolver <c>list_apps</c> y elegir qué abrir. Es
/// información puramente del dispositivo (qué hay instalado) — no inteligencia.
/// </summary>
public static class InstalledApps
{
    private static string[]? _cache;

    public static string[] List()
    {
        if (_cache != null) return _cache;
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var root in Roots())
        {
            try
            {
                if (!Directory.Exists(root)) continue;
                foreach (var lnk in Directory.EnumerateFiles(root, "*.lnk", SearchOption.AllDirectories))
                    names.Add(Path.GetFileNameWithoutExtension(lnk));
            }
            catch { }
        }
        _cache = names.OrderBy(n => n).Take(300).ToArray();
        return _cache;
    }

    private static IEnumerable<string> Roots()
    {
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu), "Programs");
        yield return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs");
    }
}
