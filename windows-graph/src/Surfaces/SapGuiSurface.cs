using System.Reflection;
using System.Text;

namespace U.Graph.Surfaces;

/// <summary>
/// Superficie SAP GUI, por la Scripting API (COM).
///
/// POR QUÉ NO UIA: SAP documenta que sus controles están acoplados a la lógica de negocio y no pueden
/// instanciarse fuera de SAP GUI, así que en vez de exponerlos a la automatización genérica crearon
/// esta API. En la práctica UIA se queda en un Pane y no ve nada dentro. Para SAP, esto o nada.
///
/// ENLACE TARDÍO A PROPÓSITO: todo el COM se llama por reflexión, sin referencia a sapfewse.ocx. Así
/// este proyecto compila en máquinas sin SAP GUI (CI, el portátil de un dev) y la ausencia de SAP es
/// un estado que se reporta, no un fallo de build. Además sobrevive mejor a los cambios de versión:
/// el interop de C# se ha roto entre 7.40 → 7.70 → 8.0.
///
/// LO QUE NO CONTROLAMOS: el scripting depende de que el Basis del cliente ponga el parámetro de
/// perfil sapgui/user_scripting en TRUE (por defecto es FALSE) y de que el SAP GUI local lo permita.
/// Por eso <see cref="Check"/> distingue los modos de fallo en vez de devolver un booleano: en la
/// máquina de un cliente, "no funciona" es inútil; "tu Basis no ha habilitado el scripting" es
/// accionable.
/// </summary>
public sealed class SapGuiSurface : IUiSurface
{
    public string Name => "sap";

    public event EventHandler<ObservedStep>? StepObserved;

    /// <summary>Tipos de GuiComponent con los que un humano interactúa. El resto es decorado.</summary>
    private static readonly HashSet<string> Interactive = new(StringComparer.OrdinalIgnoreCase)
    {
        "GuiTextField", "GuiCTextField", "GuiPasswordField", "GuiComboBox",
        "GuiCheckBox", "GuiRadioButton", "GuiButton", "GuiOkCodeField",
    };

    // ── Disponibilidad ───────────────────────────────────────────────────────

    public SurfaceAvailability Check()
    {
        try
        {
            object? engine = ScriptingEngine();
            if (engine == null)
                return SurfaceAvailability.No(
                    "No hay ninguna sesión de SAP GUI abierta, o SAP GUI no está instalado en este equipo.");

            dynamic app = engine;
            int connections = (int)app.Connections.Count;
            if (connections == 0)
                return SurfaceAvailability.No("SAP GUI está abierto pero no hay ninguna conexión activa.");

            dynamic conn = app.Connections.ElementAt(0);
            int sessions = (int)conn.Sessions.Count;
            if (sessions == 0)
                return SurfaceAvailability.No("La conexión de SAP no tiene ninguna sesión abierta.");

            return SurfaceAvailability.Ok;
        }
        catch (Exception e)
        {
            // El fallo típico aquí es que el scripting esté apagado: SAP no expone el motor y el COM
            // revienta al pedir GetScriptingEngine o al recorrer Connections.
            return SurfaceAvailability.No(
                "No se pudo hablar con SAP GUI Scripting. Lo más probable es que esté deshabilitado: " +
                "el Basis del sistema SAP debe poner sapgui/user_scripting en TRUE (RZ11), y SAP GUI " +
                $"debe permitir scripting en Opciones → Accessibility & Scripting. Detalle: {e.Message}");
        }
    }

    /// <summary>
    /// El motor de scripting, por la Running Object Table. Es el camino estándar desde .NET:
    /// SapROTWr.CSapROTWrapper → GetROTEntry("SAPGUI") → GetScriptingEngine.
    /// Devuelve null si SAP GUI no está corriendo (o no está instalado).
    /// </summary>
    private static object? ScriptingEngine()
    {
        Type? wrapperType = Type.GetTypeFromProgID("SapROTWr.CSapROTWrapper");
        if (wrapperType == null) return null; // saprotwr.dll no registrada → SAP GUI no instalado

        object? wrapper = Activator.CreateInstance(wrapperType);
        if (wrapper == null) return null;

        object? rot = wrapperType.InvokeMember(
            "GetROTEntry", BindingFlags.InvokeMethod, null, wrapper, new object[] { "SAPGUI" });
        if (rot == null) return null; // SAP GUI no está corriendo

        return rot.GetType().InvokeMember(
            "GetScriptingEngine", BindingFlags.InvokeMethod, null, rot, null);
    }

    /// <summary>La sesión con la que trabajamos: la primera de la primera conexión.</summary>
    private static dynamic? Session()
    {
        object? engine = ScriptingEngine();
        if (engine == null) return null;

        dynamic app = engine;
        if ((int)app.Connections.Count == 0) return null;

        dynamic conn = app.Connections.ElementAt(0);
        if ((int)conn.Sessions.Count == 0) return null;

        return conn.Sessions.ElementAt(0);
    }

    // ── Identidad ────────────────────────────────────────────────────────────

    public SurfaceIdentity Identity()
    {
        try
        {
            dynamic? session = Session();
            if (session == null) return SurfaceIdentity.Unknown;

            dynamic info = session.Info;
            string system = Str(info.SystemName);            // p.ej. PRD
            string tcode = Str(info.Transaction);            // p.ej. VA01
            string title = Str(session.FindById("wnd[0]").Text);

            return new SurfaceIdentity(
                Origin: $"sapgui://{(system.Length > 0 ? system : "sap")}",
                Pathname: "/" + tcode,
                Title: title);
        }
        catch { return SurfaceIdentity.Unknown; }
    }

    // ── Lectura ──────────────────────────────────────────────────────────────

    public IReadOnlyList<DetectedField> ReadFields()
    {
        var fields = new List<DetectedField>();
        dynamic? session;
        try { session = Session(); } catch { return fields; }
        if (session == null) return fields;

        try
        {
            // wnd[0]/usr es el área de usuario: lo que el operador rellena. Fuera quedan barra de
            // herramientas, menús y statusbar, que no son campos de formulario.
            dynamic area = session.FindById("wnd[0]/usr", false);
            if (area == null) return fields;

            var found = new List<dynamic>();
            Walk(area, found, 0);

            int order = 1;
            foreach (dynamic node in found)
            {
                var field = Describe(node, order);
                if (field != null) { fields.Add(field); order++; }
            }
        }
        catch { /* pantalla cambiando bajo los pies */ }

        return fields;
    }

    private static void Walk(dynamic node, List<dynamic> acc, int depth)
    {
        if (depth > 20 || acc.Count > 300) return;
        try
        {
            dynamic children = node.Children;
            int count = (int)children.Count;
            for (int i = 0; i < count; i++)
            {
                dynamic child;
                try { child = children.ElementAt(i); } catch { continue; }

                try
                {
                    if (Interactive.Contains(Str(child.Type))) acc.Add(child);
                }
                catch { }

                try { Walk(child, acc, depth + 1); } catch { }
            }
        }
        catch { /* el componente no tiene hijos */ }
    }

    private static DetectedField? Describe(dynamic node, int order)
    {
        try
        {
            string type = Str(node.Type);
            string id = Str(node.Id);
            if (id.Length == 0) return null;

            string label = LabelOf(node);
            if (label.Length == 0) return null;

            return new DetectedField
            {
                StepOrder = order,
                ActionType = ActionTypeFor(type),
                Selector = SapSelector.ById(id),
                Label = label,
                ControlType = GraphControlType(type),
                CurrentValue = ValueOf(node, type),
                AllowedOptions = OptionsOf(node, type),
            };
        }
        catch { return null; }
    }

    /// <summary>
    /// Cómo se llama el campo para un humano. El Tooltip de SAP suele ser el texto del label de al
    /// lado (que es un GuiLabel aparte y no está enlazado al control), así que es la mejor pista
    /// disponible sin adivinar por coordenadas.
    /// </summary>
    private static string LabelOf(dynamic node)
    {
        foreach (string prop in new[] { "Tooltip", "Name", "Text" })
        {
            try
            {
                string v = Str(node.GetType().InvokeMember(prop, BindingFlags.GetProperty, null, node, null));
                if (v.Trim().Length > 0) return v.Trim();
            }
            catch { }
        }
        return "";
    }

    private static string? ValueOf(dynamic node, string type)
    {
        try
        {
            if (type.Equals("GuiCheckBox", StringComparison.OrdinalIgnoreCase) ||
                type.Equals("GuiRadioButton", StringComparison.OrdinalIgnoreCase))
                return (bool)node.Selected ? "true" : "false";

            if (type.Equals("GuiComboBox", StringComparison.OrdinalIgnoreCase))
                return Str(node.Key);

            if (type.Equals("GuiPasswordField", StringComparison.OrdinalIgnoreCase))
                return null; // jamás se lee ni se graba una contraseña

            return Str(node.Text);
        }
        catch { return null; }
    }

    /// <summary>Las opciones de un combo. En SAP la clave interna (Key) y el texto visible difieren.</summary>
    private static List<FieldOption>? OptionsOf(dynamic node, string type)
    {
        if (!type.Equals("GuiComboBox", StringComparison.OrdinalIgnoreCase)) return null;
        try
        {
            dynamic entries = node.Entries;
            int count = (int)entries.Count;
            var options = new List<FieldOption>();
            for (int i = 0; i < count && options.Count < 80; i++)
            {
                try
                {
                    dynamic entry = entries.ElementAt(i);
                    string key = Str(entry.Key);
                    string text = Str(entry.Value);
                    if (key.Length > 0 || text.Length > 0)
                        options.Add(new FieldOption { Value = key, Label = text, Text = text });
                }
                catch { }
            }
            return options.Count > 0 ? options : null;
        }
        catch { return null; }
    }

    private static string ActionTypeFor(string type) => type.ToLowerInvariant() switch
    {
        "guibutton" => "click",
        "guicheckbox" => "click",
        "guiradiobutton" => "click",
        "guicombobox" => "select",
        _ => "input",
    };

    /// <summary>Traduce el tipo de SAP al vocabulario que Graph ya usa (nacido del DOM).</summary>
    private static string GraphControlType(string type) => type.ToLowerInvariant() switch
    {
        "guicombobox" => "select",
        "guicheckbox" => "checkbox",
        "guiradiobutton" => "radio",
        "guibutton" => "button",
        "guipasswordfield" => "password",
        _ => "text",
    };

    // ── Ejecución ────────────────────────────────────────────────────────────

    public bool Execute(PlanStep step, out string error)
    {
        error = "";
        dynamic? session;
        try { session = Session(); }
        catch (Exception e) { error = $"SAP GUI no responde: {e.Message}"; return false; }

        if (session == null) { error = "no hay ninguna sesión de SAP GUI abierta"; return false; }

        var candidates = new List<string> { step.Selector };
        candidates.AddRange(step.AlternativeTargets());

        foreach (string selector in candidates.Where(SapSelector.Owns))
        {
            string id = SapSelector.IdOf(selector);
            dynamic? node;
            try { node = session.FindById(id, false); }
            catch { continue; }
            if (node == null) continue;

            try { return Apply(node, step, out error); }
            catch (Exception e)
            {
                error = $"SAP rechazó la acción sobre «{step.Label}» ({id}): {e.Message}";
                return false;
            }
        }

        error = $"no se encontró el campo «{step.Label}» en la pantalla actual de SAP ({step.Selector})";
        return false;
    }

    private static bool Apply(dynamic node, PlanStep step, out string error)
    {
        error = "";
        string type = Str(node.Type);

        switch (step.ActionType)
        {
            case "input":
                node.Text = step.Value ?? "";
                return true;

            case "select":
                // En un combo de SAP se fija la CLAVE, no el texto visible. Graph guarda ambos:
                // selectedValue es la clave y selectedLabel el texto.
                if (type.Equals("GuiComboBox", StringComparison.OrdinalIgnoreCase))
                {
                    string key = step.SelectedValue ?? step.Value ?? "";
                    if (key.Length == 0) { error = "el step no trae la clave de la opción"; return false; }
                    node.Key = key;
                    return true;
                }
                node.Text = step.SelectedValue ?? step.Value ?? "";
                return true;

            case "click":
                if (type.Equals("GuiButton", StringComparison.OrdinalIgnoreCase))
                {
                    node.Press();
                    return true;
                }
                if (type.Equals("GuiCheckBox", StringComparison.OrdinalIgnoreCase) ||
                    type.Equals("GuiRadioButton", StringComparison.OrdinalIgnoreCase))
                {
                    node.Selected = !string.Equals(step.Value, "false", StringComparison.OrdinalIgnoreCase);
                    return true;
                }
                node.SetFocus();
                return true;

            default:
                error = $"actionType no soportado en SAP: {step.ActionType}";
                return false;
        }
    }

    // ── Observación ──────────────────────────────────────────────────────────

    /// <summary>
    /// PENDIENTE, y con tres restricciones ya verificadas contra la spec oficial que condicionan cómo
    /// se implementará:
    ///
    /// 1. <c>Change</c> NO es un evento por pulsación: se dispara por lotes en el round-trip al
    ///    servidor. La granularidad máxima de grabación es el viaje al servidor, no la tecla.
    ///
    /// 2. <c>Hit</c> NO es un evento de clic — es hover, y solo existe bajo elementVisualizationMode.
    ///    Sirve para un selector interactivo, no para grabar.
    ///
    /// 3. El parámetro de servidor <c>sapgui/user_scripting_disable_recording</c> apaga TODOS los
    ///    eventos de scripting, no solo el grabador nativo de SAP. Un Basis puede activarlo sin
    ///    avisar: la ejecución sigue funcionando y la grabación deja de recibir nada. Cuando se
    ///    implemente hay que detectarlo y decirlo, porque es indistinguible de un bug nuestro.
    ///
    /// Además el COM de SAP exige STA con bomba de mensajes, incompatible con el hilo de UIA. Son
    /// hilos separados, no uno.
    ///
    /// Mientras tanto la superficie SIRVE para leer campos, ejecutar planes y hacer autofill.
    /// </summary>
    public void StartObserving() =>
        throw new NotSupportedException(
            "La grabación sobre SAP GUI aún no está implementada: requiere hilo STA propio con bomba " +
            "de mensajes y depende de que sapgui/user_scripting_disable_recording esté desactivado. " +
            "Lectura, ejecución y autofill sí funcionan.");

    public void StopObserving() { }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private static string Str(object? v) => v?.ToString() ?? "";

    public void Dispose() => StopObserving();
}
