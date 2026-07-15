using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Automation;

namespace U.Graph.Surfaces;

/// <summary>
/// Superficie UIA: cualquier app de Windows. Es la red de seguridad — funciona en todas partes, pero
/// entiende menos que <see cref="SapGuiSurface"/> cuando la app de abajo es SAP.
///
/// Comparte enfoque con <c>windows-client/src/Uia/UiaReader.cs</c> pero NO lo reutiliza: aquel resume
/// el árbol como TEXTO para un LLM, este lo estructura como CAMPOS para Graph. Mezclarlos ataría este
/// módulo al asistente, que es justo lo que queremos evitar.
/// </summary>
public sealed class UiaSurface : IUiSurface
{
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);

    public string Name => "uia";

    public event EventHandler<ObservedStep>? StepObserved;

    private AutomationElement? _observedRoot;
    private AutomationEventHandler? _invoked;
    private AutomationPropertyChangedEventHandler? _propertyChanged;
    private bool _observing;
    private readonly object _gate = new();

    /// <summary>Qué se considera un campo con el que se interactúa. Text/Label quedan fuera: no son acciones.</summary>
    private static readonly HashSet<ControlType> Interactive = new()
    {
        ControlType.Edit, ControlType.ComboBox, ControlType.CheckBox, ControlType.RadioButton,
        ControlType.Button, ControlType.List, ControlType.ListItem, ControlType.MenuItem,
        ControlType.Hyperlink, ControlType.TabItem, ControlType.SplitButton, ControlType.Document,
    };

    public SurfaceAvailability Check() =>
        GetForegroundWindow() == IntPtr.Zero
            ? SurfaceAvailability.No("No hay ninguna ventana en primer plano.")
            : SurfaceAvailability.Ok;

    public SurfaceIdentity Identity()
    {
        IntPtr hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return SurfaceIdentity.Unknown;

        string proc = ProcessName(hwnd);
        string title = Root(hwnd)?.Current.Name ?? "";
        return new SurfaceIdentity($"uia://{proc}", "/" + title.Trim(), title.Trim());
    }

    // ── Lectura ──────────────────────────────────────────────────────────────

    public IReadOnlyList<DetectedField> ReadFields()
    {
        var fields = new List<DetectedField>();
        IntPtr hwnd = GetForegroundWindow();
        AutomationElement? root = hwnd == IntPtr.Zero ? null : Root(hwnd);
        if (root == null) return fields;

        var found = new List<(AutomationElement El, List<int> Path)>();
        try { Walk(root, found, new List<int>(), 0); }
        catch { /* UIA lanza en árboles que cambian mientras se recorren */ }

        int order = 1;
        foreach (var (el, path) in found)
        {
            var field = Describe(el, path, order);
            if (field != null) { fields.Add(field); order++; }
        }
        return fields;
    }

    private static void Walk(AutomationElement node, List<(AutomationElement, List<int>)> acc, List<int> path, int depth)
    {
        if (depth > 40 || acc.Count > 300) return;

        AutomationElement? child = TreeWalker.ControlViewWalker.GetFirstChild(node);
        int index = 0;
        while (child != null)
        {
            var here = new List<int>(path) { index };
            try
            {
                var info = child.Current;
                if (!info.IsOffscreen && Interactive.Contains(info.ControlType) && info.IsEnabled)
                    acc.Add((child, here));
            }
            catch { /* nodo muerto entre la enumeración y la lectura */ }

            try { Walk(child, acc, here, depth + 1); } catch { }

            try { child = TreeWalker.ControlViewWalker.GetNextSibling(child); }
            catch { break; }
            index++;
        }
    }

    private DetectedField? Describe(AutomationElement el, List<int> path, int order)
    {
        try
        {
            var info = el.Current;
            string label = LabelOf(el, info);
            if (string.IsNullOrWhiteSpace(label)) return null;

            string ct = ControlTypeName(info.ControlType);
            var selectors = SelectorsFor(info, path, ct);
            if (selectors.Count == 0) return null;

            return new DetectedField
            {
                StepOrder = order,
                ActionType = ActionTypeFor(info.ControlType),
                Selector = selectors[0],
                Label = label,
                ControlType = GraphControlType(info.ControlType, el),
                CurrentValue = ValueOf(el),
                AllowedOptions = OptionsOf(el, info.ControlType),
            };
        }
        catch { return null; }
    }

    /// <summary>Los selectores de un elemento, del más estable al más frágil.</summary>
    private static List<string> SelectorsFor(
        AutomationElement.AutomationElementInformation info, List<int> path, string ct)
    {
        var list = new List<string>();
        if (!string.IsNullOrWhiteSpace(info.AutomationId))
            list.Add(UiaSelector.ByAutomationId(info.AutomationId.Trim(), ct));
        if (!string.IsNullOrWhiteSpace(info.Name))
            list.Add(UiaSelector.ByName(info.Name.Trim(), ct));
        list.Add(UiaSelector.ByPath(path, ct));
        return list;
    }

    /// <summary>Name → AutomationId → HelpText. Es lo que un humano llamaría "el campo".</summary>
    private static string LabelOf(AutomationElement el, AutomationElement.AutomationElementInformation info)
    {
        if (!string.IsNullOrWhiteSpace(info.Name)) return info.Name.Trim();
        if (!string.IsNullOrWhiteSpace(info.AutomationId)) return info.AutomationId.Trim();
        try
        {
            if (el.GetCurrentPropertyValue(AutomationElement.HelpTextProperty) is string help &&
                !string.IsNullOrWhiteSpace(help))
                return help.Trim();
        }
        catch { }
        return "";
    }

    private static string? ValueOf(AutomationElement el)
    {
        try
        {
            if (el.TryGetCurrentPattern(ValuePattern.Pattern, out var p) && p is ValuePattern v)
                return v.Current.Value;
        }
        catch { }
        try
        {
            if (el.TryGetCurrentPattern(TogglePattern.Pattern, out var p) && p is TogglePattern t)
                return t.Current.ToggleState == ToggleState.On ? "true" : "false";
        }
        catch { }
        try
        {
            if (el.TryGetCurrentPattern(SelectionPattern.Pattern, out var p) && p is SelectionPattern s)
            {
                var sel = s.Current.GetSelection();
                if (sel.Length > 0) return sel[0].Current.Name;
            }
        }
        catch { }
        return null;
    }

    /// <summary>
    /// Las opciones de un combo/lista. NO se expande el control para leerlas: abrir un desplegable en
    /// el SAP de un cliente es una acción visible que puede disparar validaciones. Se leen los hijos
    /// que ya estén expuestos; si no hay, se devuelve null y Graph trata el campo como texto libre.
    /// </summary>
    private static List<FieldOption>? OptionsOf(AutomationElement el, ControlType ct)
    {
        if (ct != ControlType.ComboBox && ct != ControlType.List) return null;
        try
        {
            var items = el.FindAll(TreeScope.Children,
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ListItem));
            if (items.Count == 0) return null;

            var options = new List<FieldOption>();
            foreach (AutomationElement item in items)
            {
                string n = item.Current.Name?.Trim() ?? "";
                if (n.Length > 0) options.Add(new FieldOption { Value = n, Label = n, Text = n });
                if (options.Count >= 80) break; // el límite que aplica NoteFieldMatcher al otro lado
            }
            return options.Count > 0 ? options : null;
        }
        catch { return null; }
    }

    private static string ActionTypeFor(ControlType ct)
    {
        if (ct == ControlType.Edit || ct == ControlType.Document) return "input";
        if (ct == ControlType.ComboBox || ct == ControlType.List) return "select";
        return "click";
    }

    /// <summary>Traduce el ControlType de UIA al vocabulario que ya usa Graph (nacido del DOM).</summary>
    private static string GraphControlType(ControlType ct, AutomationElement el)
    {
        if (ct == ControlType.Edit) return IsMultiline(el) ? "textarea" : "text";
        if (ct == ControlType.Document) return "textarea";
        if (ct == ControlType.ComboBox || ct == ControlType.List) return "select";
        if (ct == ControlType.CheckBox) return "checkbox";
        if (ct == ControlType.RadioButton) return "radio";
        return "button";
    }

    private static bool IsMultiline(AutomationElement el)
    {
        try
        {
            return el.TryGetCurrentPattern(TextPattern.Pattern, out _) &&
                   !el.Current.BoundingRectangle.IsEmpty &&
                   el.Current.BoundingRectangle.Height > 40;
        }
        catch { return false; }
    }

    // ── Ejecución ────────────────────────────────────────────────────────────

    public bool Execute(PlanStep step, out string error)
    {
        error = "";
        var candidates = new List<string> { step.Selector };
        candidates.AddRange(step.AlternativeTargets().Where(UiaSelector.Owns));

        AutomationElement? el = null;
        foreach (string sel in candidates.Where(UiaSelector.Owns))
        {
            el = Resolve(sel);
            if (el != null) break;
        }

        if (el == null)
        {
            error = $"no se encontró el elemento «{step.Label}» ({step.Selector})";
            return false;
        }

        try
        {
            return step.ActionType switch
            {
                "input" => SetValue(el, step.Value ?? "", out error),
                "select" => Select(el, step.SelectedValue ?? step.Value ?? "", out error),
                "click" => Click(el, out error),
                _ => Fail($"actionType no soportado en UIA: {step.ActionType}", out error),
            };
        }
        catch (Exception e)
        {
            error = $"UIA falló ejecutando «{step.Label}»: {e.Message}";
            return false;
        }
    }

    private static bool Fail(string reason, out string error) { error = reason; return false; }

    private AutomationElement? Resolve(string selector)
    {
        IntPtr hwnd = GetForegroundWindow();
        AutomationElement? root = hwnd == IntPtr.Zero ? null : Root(hwnd);
        if (root == null) return null;

        var parts = UiaSelector.Parse(selector);

        if (parts.TryGetValue("path", out string? raw) && !string.IsNullOrWhiteSpace(raw))
            return ByPath(root, raw);

        var condition = UiaSelector.ConditionFor(parts);
        if (condition == null) return null;
        try { return root.FindFirst(TreeScope.Descendants, condition); }
        catch { return null; }
    }

    private static AutomationElement? ByPath(AutomationElement root, string raw)
    {
        var node = root;
        foreach (string chunk in raw.Split('/', StringSplitOptions.RemoveEmptyEntries))
        {
            if (!int.TryParse(chunk, out int index)) return null;
            try
            {
                var child = TreeWalker.ControlViewWalker.GetFirstChild(node);
                for (int i = 0; i < index && child != null; i++)
                    child = TreeWalker.ControlViewWalker.GetNextSibling(child);
                if (child == null) return null;
                node = child;
            }
            catch { return null; }
        }
        return node;
    }

    private static bool SetValue(AutomationElement el, string value, out string error)
    {
        error = "";
        if (el.TryGetCurrentPattern(ValuePattern.Pattern, out var p) && p is ValuePattern v)
        {
            if (v.Current.IsReadOnly) { error = "el campo es de solo lectura"; return false; }
            v.SetValue(value);
            return true;
        }
        error = "el campo no soporta ValuePattern (no se puede escribir por UIA)";
        return false;
    }

    private static bool Select(AutomationElement el, string value, out string error)
    {
        error = "";
        // El valor puede venir como texto de la opción: se busca entre los hijos ListItem.
        try
        {
            var item = el.FindFirst(TreeScope.Descendants, new AndCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ListItem),
                new PropertyCondition(AutomationElement.NameProperty, value)));

            if (item != null &&
                item.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var sp) &&
                sp is SelectionItemPattern sel)
            {
                sel.Select();
                return true;
            }
        }
        catch { }

        // Algunos combos aceptan el texto directamente.
        if (el.TryGetCurrentPattern(ValuePattern.Pattern, out var p) && p is ValuePattern v && !v.Current.IsReadOnly)
        {
            v.SetValue(value);
            return true;
        }

        error = $"no se pudo seleccionar «{value}»: la opción no existe o el control no lo permite";
        return false;
    }

    private static bool Click(AutomationElement el, out string error)
    {
        error = "";
        if (el.TryGetCurrentPattern(InvokePattern.Pattern, out var ip) && ip is InvokePattern inv)
        {
            inv.Invoke();
            return true;
        }
        if (el.TryGetCurrentPattern(TogglePattern.Pattern, out var tp) && tp is TogglePattern tog)
        {
            tog.Toggle();
            return true;
        }
        if (el.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var sp) && sp is SelectionItemPattern sel)
        {
            sel.Select();
            return true;
        }
        error = "el elemento no soporta Invoke/Toggle/Select";
        return false;
    }

    // ── Observación ──────────────────────────────────────────────────────────

    /// <summary>
    /// Engancha los eventos de UIA sobre la ventana en primer plano. El ámbito es esa ventana y no el
    /// escritorio a propósito: suscribirse a TreeScope.Descendants del root es una forma conocida de
    /// ahogar el proceso y la app observada.
    ///
    /// Sobre el handler: Microsoft documenta que SÍ es seguro llamar a UIA desde dentro de un handler
    /// de eventos de UIA ("It is safe to make UI Automation calls in a UI Automation event handler").
    /// Aun así aquí solo se arma el ObservedStep y se publica: el consumidor (WorkflowRecorder) hace
    /// HTTP, y ESO no puede colgar del hilo de eventos de la app observada.
    ///
    /// LÍMITE CONOCIDO: esto NO captura el tecleo. UIA no expone pulsaciones, y ValueProperty llega
    /// como cambio ya consolidado, no por tecla. Para grabar escritura de verdad hace falta sumar
    /// WinEvents (EVENT_OBJECT_VALUECHANGE), que va en su propio hilo con su propia bomba de mensajes.
    /// </summary>
    public void StartObserving()
    {
        lock (_gate)
        {
            if (_observing) return;

            IntPtr hwnd = GetForegroundWindow();
            _observedRoot = hwnd == IntPtr.Zero ? null : Root(hwnd);
            if (_observedRoot == null) return;

            _invoked = OnInvoked;
            _propertyChanged = OnPropertyChanged;

            try
            {
                Automation.AddAutomationEventHandler(
                    InvokePattern.InvokedEvent, _observedRoot, TreeScope.Subtree, _invoked);

                Automation.AddAutomationPropertyChangedEventHandler(
                    _observedRoot, TreeScope.Subtree, _propertyChanged,
                    ValuePattern.ValueProperty, TogglePattern.ToggleStateProperty,
                    SelectionItemPattern.IsSelectedProperty);

                _observing = true;
            }
            catch
            {
                StopObserving();
            }
        }
    }

    public void StopObserving()
    {
        lock (_gate)
        {
            try
            {
                if (_invoked != null && _observedRoot != null)
                    Automation.RemoveAutomationEventHandler(InvokePattern.InvokedEvent, _observedRoot, _invoked);
                if (_propertyChanged != null && _observedRoot != null)
                    Automation.RemoveAutomationPropertyChangedEventHandler(_observedRoot, _propertyChanged);
            }
            catch { /* la ventana pudo morir antes que nosotros */ }

            _invoked = null;
            _propertyChanged = null;
            _observedRoot = null;
            _observing = false;
        }
    }

    private void OnInvoked(object? sender, AutomationEventArgs e)
    {
        if (sender is not AutomationElement el) return;
        Publish(el, "click", null);
    }

    private void OnPropertyChanged(object? sender, AutomationPropertyChangedEventArgs e)
    {
        if (sender is not AutomationElement el) return;

        if (e.Property == ValuePattern.ValueProperty)
            Publish(el, "input", e.NewValue as string);
        else if (e.Property == TogglePattern.ToggleStateProperty)
            Publish(el, "click", e.NewValue?.ToString());
        else if (e.Property == SelectionItemPattern.IsSelectedProperty && e.NewValue is true)
            Publish(el, "select", el.Current.Name);
    }

    private void Publish(AutomationElement el, string actionType, string? value)
    {
        ObservedStep step;
        try
        {
            var info = el.Current;
            string label = LabelOf(el, info);
            if (string.IsNullOrWhiteSpace(label)) return;

            string ct = ControlTypeName(info.ControlType);
            var selectors = SelectorsFor(info, new List<int>(), ct);
            if (selectors.Count == 0) return;

            step = new ObservedStep(
                ActionType: actionType,
                Selector: selectors[0],
                Label: label,
                ControlType: GraphControlType(info.ControlType, el),
                Value: value,
                AllowedOptions: null,
                SelectedValue: actionType == "select" ? value : null,
                SelectedLabel: actionType == "select" ? value : null,
                SurfaceSection: null,
                AlternativeTargets: selectors.Skip(1).ToList());
        }
        catch { return; }

        try { StepObserved?.Invoke(this, step); } catch { }
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private static AutomationElement? Root(IntPtr hwnd)
    {
        try { return AutomationElement.FromHandle(hwnd); }
        catch { return null; }
    }

    private static string ProcessName(IntPtr hwnd)
    {
        try
        {
            GetWindowThreadProcessId(hwnd, out uint pid);
            using var p = Process.GetProcessById((int)pid);
            return p.ProcessName + ".exe";
        }
        catch { return "app"; }
    }

    private static string ControlTypeName(ControlType ct) => ct.ProgrammaticName.Replace("ControlType.", "");

    public void Dispose() => StopObserving();
}
