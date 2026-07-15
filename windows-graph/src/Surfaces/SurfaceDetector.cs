using System.Diagnostics;
using System.Runtime.InteropServices;

namespace U.Graph.Surfaces;

/// <summary>
/// Elige UIA o SAP GUI según la ventana en primer plano cuando arranca una enseñanza: el operador ya
/// cambió a la app que va a enseñar (ver el countdown en WorkflowTeachSession), así que el proceso en
/// foreground ES la superficie correcta.
/// </summary>
public static class SurfaceDetector
{
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);

    /// <summary>
    /// Nombres de proceso de la ventana de sesión de SAP GUI. `saplogon` es el logon pad; la ventana de
    /// una sesión abierta también corre bajo ese mismo proceso en las instalaciones estándar de SAP GUI
    /// for Windows. Ajustar aquí si el cliente reporta un nombre distinto (ver PRODUCTION.md/plan).
    /// </summary>
    private static readonly HashSet<string> SapProcessNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "saplogon", "sapgui",
    };

    public static IUiSurface Detect(IUiSurface uia, IUiSurface sap) =>
        SapProcessNames.Contains(ForegroundProcessName()) ? sap : uia;

    private static string ForegroundProcessName()
    {
        try
        {
            IntPtr hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return "";
            GetWindowThreadProcessId(hwnd, out uint pid);
            using var p = Process.GetProcessById((int)pid);
            return p.ProcessName;
        }
        catch { return ""; }
    }
}
