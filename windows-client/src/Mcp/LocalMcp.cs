using U.WindowsClient.Actions;
using U.WindowsClient.SystemApi;
using U.WindowsClient.Uia;

namespace U.WindowsClient.Mcp;

/// <summary>
/// Registro <c>nombre → ejecutor local</c>. El cerebro (backend) DECLARA el catálogo MCP al modelo y
/// decide QUÉ herramienta llamar; este registro solo sabe CÓMO ejecutarla en Windows. No conoce
/// descripciones, esquemas ni prompts — solo el mapeo. Ese es el corte de la separación.
///
/// Cubre las dos familias base (gestos + sistema). Las herramientas APRENDIDAS llegan con un arg
/// <c>taps</c> y se reproducen por árbol de UI (UIA) sin coordenadas fijas.
/// </summary>
public sealed class LocalMcp
{
    private readonly UiaReader _uia;
    public LocalMcp(UiaReader uia) => _uia = uia;

    /// <summary>Ejecuta una llamada MCP y devuelve un resultado legible para el modelo ("ok" / detalle).</summary>
    public string Call(string tool, IReadOnlyDictionary<string, string> args)
    {
        string A(string k) => args.TryGetValue(k, out var v) ? v.Trim() : "";
        int I(string k, int def) => int.TryParse(A(k), out var n) ? n : def;

        // Herramienta aprendida: llega con `taps` y no es una del catálogo base. Devuelve su propio
        // detalle (qué pasos fallaron), igual que las learned tools de Android.
        if (!IsKnown(tool) && args.ContainsKey("taps"))
            return RunLearnedTaps(tool, A("taps"));

        bool ok = tool switch
        {
            // --- Gestos de Windows ---
            "go_home" => Gestures.ShowDesktop(),
            "open_app_drawer" => Gestures.StartMenu(),
            "open_notifications" => Gestures.NotificationCenter(),
            "switch_window" => Gestures.SwitchWindow(A("direction") != "previous"),
            "scroll_menu" => InputExecutor.Scroll(A("direction") != "up"),

            // --- Acciones del sistema ---
            "launch_app" => WindowsSystemApi.LaunchApp(A("app")),
            "set_alarm" => WindowsSystemApi.SetAlarm(I("hour", 8), I("minute", 0), A("message")),
            "set_timer" => WindowsSystemApi.SetTimer(I("seconds", 60), A("message")),
            "create_event" => WindowsSystemApi.CreateEvent(A("title"), A("start"), A("location")),
            "dial" => WindowsSystemApi.Dial(A("number")),
            "send_sms" => WindowsSystemApi.SendSms(A("number"), A("message")),
            "send_email" => WindowsSystemApi.SendEmail(A("to"), A("subject"), A("body")),
            "web_search" => WindowsSystemApi.WebSearch(A("query")),
            "open_url" => WindowsSystemApi.OpenUrl(A("url")),
            "open_maps" => WindowsSystemApi.OpenMaps(A("query")),
            "directions" => WindowsSystemApi.Directions(A("destination")),
            "open_camera" => WindowsSystemApi.OpenCamera(),
            "open_settings" => WindowsSystemApi.OpenSettings(A("section")),
            "share_text" => WindowsSystemApi.ShareText(A("text")),
            "set_clipboard" => WindowsSystemApi.SetClipboard(A("text")),
            "set_volume" => WindowsSystemApi.SetVolume(I("percent", 100)),
            "adjust_volume" => WindowsSystemApi.AdjustVolume(A("direction")),

            _ => false,
        };

        if (!IsKnown(tool))
            return $"herramienta no soportada por este cliente: {tool}";
        return ok ? "ok" : "la herramienta no se pudo ejecutar";
    }

    private string RunLearnedTaps(string tool, string taps)
    {
        var labels = taps.Split(',').Select(s => s.Trim()).Where(s => s.Length > 0).ToList();
        var failed = new List<string>();
        foreach (var label in labels)
        {
            // Preferimos invocar por patrón UIA (más robusto); si no, tap por coordenadas del elemento.
            bool done = _uia.InvokeLabel(label);
            if (!done)
            {
                var pt = _uia.TapTargetForLabel(label);
                done = pt != null && InputExecutor.Tap(pt.Value.x, pt.Value.y);
            }
            if (!done) failed.Add(label);
            Thread.Sleep(350);
        }
        if (failed.Count > 0) return $"la herramienta no se pudo ejecutar — pasos fallidos: {string.Join(", ", failed)} (de {labels.Count})";
        return labels.Count > 0 ? "ok" : "la herramienta no se pudo ejecutar — llamada sin taps";
    }

    private static bool IsKnown(string tool) => Known.Contains(tool);

    private static readonly HashSet<string> Known = new()
    {
        "go_home", "open_app_drawer", "open_notifications", "switch_window", "scroll_menu",
        "launch_app", "set_alarm", "set_timer", "create_event", "dial", "send_sms", "send_email",
        "web_search", "open_url", "open_maps", "directions", "open_camera", "open_settings",
        "share_text", "set_clipboard", "set_volume", "adjust_volume",
    };
}
