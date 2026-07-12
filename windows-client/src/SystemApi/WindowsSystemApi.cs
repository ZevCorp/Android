using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows;

namespace U.WindowsClient.SystemApi;

/// <summary>
/// Acciones de sistema por API/protocolo de Windows: el equivalente a los "Common Intents" de Android
/// (<c>SystemApi</c> del core). Abren apps, URLs, correo, ajustes… sin navegar la interfaz. El cerebro
/// las prefiere sobre computer-use para tareas de sistema; aquí solo se EJECUTAN.
/// </summary>
public static class WindowsSystemApi
{
    [DllImport("user32.dll")] private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
    private const byte VK_VOLUME_MUTE = 0xAD, VK_VOLUME_DOWN = 0xAE, VK_VOLUME_UP = 0xAF;

    public static bool LaunchApp(string app)
    {
        if (string.IsNullOrWhiteSpace(app)) return false;
        // Intenta ejecutable directo; si falla, deja que el shell lo resuelva (nombre, .lnk del menú Inicio).
        return Shell(app) || Shell("cmd", $"/c start \"\" \"{app}\"");
    }

    public static bool OpenUrl(string url) => Shell(NormalizeUrl(url));
    public static bool WebSearch(string query) => Shell($"https://www.google.com/search?q={Uri.EscapeDataString(query)}");
    public static bool OpenMaps(string q) => Shell($"https://www.google.com/maps/search/{Uri.EscapeDataString(q)}");
    public static bool Directions(string dest) => Shell($"https://www.google.com/maps/dir/?api=1&destination={Uri.EscapeDataString(dest)}");

    public static bool SendEmail(string to, string subject, string body) =>
        Shell($"mailto:{Uri.EscapeDataString(to)}?subject={Uri.EscapeDataString(subject)}&body={Uri.EscapeDataString(body)}");

    public static bool Dial(string number) => Shell($"tel:{Uri.EscapeDataString(number)}");
    public static bool SendSms(string number, string message) => Shell($"sms:{Uri.EscapeDataString(number)}?body={Uri.EscapeDataString(message)}");
    public static bool OpenCamera() => Shell("microsoft.windows.camera:");

    public static bool OpenSettings(string section)
    {
        string page = section switch
        {
            "wifi" => "network-wifi",
            "bluetooth" => "bluetooth",
            "network" => "network",
            "display" => "display",
            "sound" => "sound",
            "battery" => "batterysaver",
            "privacy" => "privacy",
            "apps" => "appsfeatures",
            _ => "",
        };
        return Shell($"ms-settings:{page}");
    }

    public static bool SetClipboard(string text)
    {
        try { Clipboard.SetText(text ?? ""); return true; } catch { return false; }
    }

    public static bool ShareText(string text)
    {
        // Windows no tiene un Intent de compartir universal desde consola; copiamos al portapapeles como
        // fallback razonable y honesto (el cerebro puede seguir con computer-use si necesita el diálogo).
        return SetClipboard(text);
    }

    public static bool AdjustVolume(string direction)
    {
        byte vk = direction switch
        {
            "raise" => VK_VOLUME_UP,
            "lower" => VK_VOLUME_DOWN,
            "mute" or "unmute" => VK_VOLUME_MUTE,
            _ => 0,
        };
        if (vk == 0) return false;
        keybd_event(vk, 0, 0, UIntPtr.Zero);
        keybd_event(vk, 0, 2 /*KEYUP*/, UIntPtr.Zero);
        return true;
    }

    /// <summary>Ajuste aproximado por pasos de tecla (control fino requiere CoreAudio; fase siguiente).</summary>
    public static bool SetVolume(int percent)
    {
        percent = Math.Clamp(percent, 0, 100);
        // Baja al mínimo y sube ~ el porcentaje pedido (cada pulsación ≈ 2%).
        for (int i = 0; i < 50; i++) AdjustVolume("lower");
        int steps = percent / 2;
        for (int i = 0; i < steps; i++) AdjustVolume("raise");
        return true;
    }

    public static bool SetAlarm(int hour, int minute, string message) =>
        // La app Reloj no expone protocolo de alarma directa; abrirla es el fallback honesto.
        Shell("ms-clock:");

    public static bool SetTimer(int seconds, string message) => Shell("ms-clock:");
    public static bool CreateEvent(string title, string startIso, string location) => Shell("outlookcal:");

    private static string NormalizeUrl(string url) =>
        url.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? url : $"https://{url}";

    private static bool Shell(string file, string? args = null)
    {
        try
        {
            Process.Start(new ProcessStartInfo { FileName = file, Arguments = args ?? "", UseShellExecute = true });
            return true;
        }
        catch { return false; }
    }
}
