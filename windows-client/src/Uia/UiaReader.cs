using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Automation;
using U.WindowsClient.Domain;

namespace U.WindowsClient.Uia;

/// <summary>
/// Lee el árbol de UI de Windows con UIA (UI Automation) y lo resume como TEXTO para el cerebro,
/// igual que <c>GraphAccessibilityService</c> hacía con el árbol de accesibilidad en Android.
///
/// Produce el <see cref="ScreenState"/> por defecto (sin imagen): <c>Screen</c> (proceso · título de
/// ventana) y <c>UiContext</c> (tipo de pantalla + etiquetas accionables visibles). Con eso al cerebro
/// le basta para ubicarse y actuar por MCP; el screenshot solo se adjunta cuando pide computer-use.
///
/// También expone <see cref="Elements"/> (accionables con bounds) para resolver taps por etiqueta
/// (herramientas aprendidas) y <see cref="TapTargetForLabel"/> para el ejecutor MCP.
/// </summary>
public sealed class UiaReader
{
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int nIndex);
    private const int SM_CXSCREEN = 0, SM_CYSCREEN = 1;

    /// <summary>Un elemento accionable detectado en la pantalla visible.</summary>
    public sealed record UiElement(string Label, string ControlType, System.Windows.Rect Bounds, AutomationElement Native);

    /// <summary>Snapshot de accionables del último <see cref="Read"/>. Sirve para taps por etiqueta.</summary>
    public IReadOnlyList<UiElement> Elements { get; private set; } = Array.Empty<UiElement>();

    private static readonly HashSet<ControlType> Actionable = new()
    {
        ControlType.Button, ControlType.MenuItem, ControlType.ListItem, ControlType.TreeItem,
        ControlType.TabItem, ControlType.Hyperlink, ControlType.Edit, ControlType.CheckBox,
        ControlType.RadioButton, ControlType.ComboBox, ControlType.SplitButton, ControlType.Text,
    };

    /// <summary>
    /// Captura el estado actual. <paramref name="withScreenshot"/> lo llena aparte el
    /// <c>Screenshotter</c>; aquí solo el texto del árbol de UI.
    /// </summary>
    public ScreenState Read()
    {
        var state = new ScreenState
        {
            Width = GetSystemMetrics(SM_CXSCREEN),
            Height = GetSystemMetrics(SM_CYSCREEN),
        };

        IntPtr hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero)
        {
            state.Screen = "escritorio";
            state.UiContext = "Escritorio de Windows (sin ventana en primer plano).";
            Elements = Array.Empty<UiElement>();
            return state;
        }

        string proc = ProcessName(hwnd);
        AutomationElement? root = SafeFromHandle(hwnd);
        string title = root?.Current.Name ?? "";
        state.Screen = string.IsNullOrWhiteSpace(title) ? proc : $"{proc} · {title}";

        var elements = new List<UiElement>();
        if (root != null)
        {
            try { Collect(root, elements, 0); } catch { /* UIA puede lanzar en árboles inestables */ }
        }
        Elements = elements;

        state.UiContext = BuildContext(proc, title, elements);
        return state;
    }

    /// <summary>Devuelve el punto (centro) donde tocar el primer elemento cuya etiqueta coincida.</summary>
    public (int x, int y)? TapTargetForLabel(string label)
    {
        var el = Elements.FirstOrDefault(e => Match(e.Label, label));
        if (el == null || el.Bounds.IsEmpty || double.IsInfinity(el.Bounds.X)) return null;
        return ((int)(el.Bounds.X + el.Bounds.Width / 2), (int)(el.Bounds.Y + el.Bounds.Height / 2));
    }

    /// <summary>Invoca por patrón UIA (sin ratón) el primer elemento con esa etiqueta, si soporta Invoke.</summary>
    public bool InvokeLabel(string label)
    {
        var el = Elements.FirstOrDefault(e => Match(e.Label, label));
        if (el == null) return false;
        if (el.Native.TryGetCurrentPattern(InvokePattern.Pattern, out var p) && p is InvokePattern inv)
        {
            try { inv.Invoke(); return true; } catch { return false; }
        }
        return false;
    }

    private static bool Match(string a, string b) =>
        string.Equals(a.Trim(), b.Trim(), StringComparison.OrdinalIgnoreCase);

    private static void Collect(AutomationElement node, List<UiElement> acc, int depth)
    {
        if (depth > 40 || acc.Count > 400) return;
        AutomationElement? child = TreeWalker.ControlViewWalker.GetFirstChild(node);
        while (child != null)
        {
            try
            {
                var info = child.Current;
                var ct = info.ControlType;
                bool offscreen = info.IsOffscreen;
                if (!offscreen && Actionable.Contains(ct))
                {
                    string label = LabelOf(child, info);
                    if (!string.IsNullOrWhiteSpace(label))
                        acc.Add(new UiElement(label, ControlTypeName(ct), info.BoundingRectangle, child));
                }
            }
            catch { /* nodo muerto */ }

            try { Collect(child, acc, depth + 1); } catch { }
            child = TreeWalker.ControlViewWalker.GetNextSibling(child);
        }
    }

    /// <summary>La etiqueta con la que el agente identifica un elemento: Name → AutomationId → HelpText.</summary>
    private static string LabelOf(AutomationElement el, AutomationElement.AutomationElementInformation info)
    {
        if (!string.IsNullOrWhiteSpace(info.Name)) return info.Name.Trim();
        if (!string.IsNullOrWhiteSpace(info.AutomationId)) return info.AutomationId.Trim();
        try
        {
            var help = el.GetCurrentPropertyValue(AutomationElement.HelpTextProperty) as string;
            if (!string.IsNullOrWhiteSpace(help)) return help.Trim();
        }
        catch { }
        return "";
    }

    private static string BuildContext(string proc, string title, List<UiElement> elements)
    {
        var sb = new StringBuilder();
        sb.AppendLine($"App en primer plano: {proc}");
        if (!string.IsNullOrWhiteSpace(title)) sb.AppendLine($"Ventana: {title}");
        sb.AppendLine($"Elementos accionables visibles ({elements.Count}):");

        // Agrupa por tipo para que el texto sea legible y compacto (como el uiContext de Android).
        foreach (var grp in elements.GroupBy(e => e.ControlType))
        {
            var labels = grp.Select(e => e.Label)
                            .Where(l => l.Length <= 60)
                            .Distinct()
                            .Take(40);
            sb.AppendLine($"- {grp.Key}: {string.Join(" | ", labels)}");
        }
        return sb.ToString().TrimEnd();
    }

    private static string ProcessName(IntPtr hwnd)
    {
        try
        {
            GetWindowThreadProcessId(hwnd, out uint pid);
            using var p = Process.GetProcessById((int)pid);
            return p.ProcessName;
        }
        catch { return "app"; }
    }

    private static AutomationElement? SafeFromHandle(IntPtr hwnd)
    {
        try { return AutomationElement.FromHandle(hwnd); }
        catch { return null; }
    }

    private static string ControlTypeName(ControlType ct) => ct.ProgrammaticName.Replace("ControlType.", "");
}
