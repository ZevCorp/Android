using System.Text;
using System.Windows.Automation;

namespace U.Graph.Surfaces;

/// <summary>
/// El "selector CSS" de UIA. Graph guarda un string opaco y nos lo devuelve intacto en el plan, así
/// que el formato lo elegimos nosotros — pero tiene que ser RE-RESOLUBLE en otra ejecución, en otra
/// sesión y quizá en otra máquina.
///
/// Formato: <c>uia:clave=valor;clave=valor</c>
///
///   uia:aid=txtUsuario;ct=Edit        ← AutomationId. El único de verdad estable.
///   uia:name=Aceptar;ct=Button        ← Name visible. Estable si no está traducido/duplicado.
///   uia:path=0/3/1/2;ct=Edit          ← Índices desde la ventana. Frágil: último recurso.
///
/// Se emiten los TRES cuando se puede: el mejor como selector y los demás como
/// <c>surfaceHints.alternativeTargets</c>, que es la convención que Graph ya entiende. Si la app
/// cambia y el AutomationId desaparece, el ejecutor cae al name y luego al path.
///
/// Ni siquiera el AutomationId es garantía: Microsoft avisa de que "a major drawback to using
/// AutomationID for recording user interactions in a volatile UI is the probability of catastrophic
/// change in the UI". De ahí que los alternativos no sean un lujo — son el plan de contingencia.
///
/// RuntimeId queda fuera: se documenta como OPACO y reutilizable en el tiempo. Identifica una
/// instancia viva, no el mismo control lógico en otra ejecución, así que no sirve de selector
/// persistente.
/// </summary>
public static class UiaSelector
{
    public const string Prefix = "uia:";

    public static string ByAutomationId(string automationId, string controlType) =>
        $"{Prefix}aid={Escape(automationId)};ct={Escape(controlType)}";

    public static string ByName(string name, string controlType) =>
        $"{Prefix}name={Escape(name)};ct={Escape(controlType)}";

    public static string ByPath(IEnumerable<int> path, string controlType) =>
        $"{Prefix}path={string.Join("/", path)};ct={Escape(controlType)}";

    public static bool Owns(string selector) =>
        !string.IsNullOrWhiteSpace(selector) && selector.StartsWith(Prefix, StringComparison.OrdinalIgnoreCase);

    /// <summary>Parte un selector en sus claves. Devuelve vacío si no es de esta superficie.</summary>
    public static Dictionary<string, string> Parse(string selector)
    {
        var parts = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (!Owns(selector)) return parts;

        foreach (string chunk in selector.Substring(Prefix.Length).Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            int eq = chunk.IndexOf('=');
            if (eq <= 0) continue;
            parts[chunk.Substring(0, eq).Trim()] = Unescape(chunk.Substring(eq + 1));
        }
        return parts;
    }

    /// <summary>
    /// Construye la condición de búsqueda de un selector. Null si el selector no trae nada con lo que
    /// buscar (el caso path se resuelve aparte, recorriendo índices).
    /// </summary>
    public static Condition? ConditionFor(Dictionary<string, string> parts)
    {
        var conditions = new List<Condition>();

        if (parts.TryGetValue("aid", out string? aid) && !string.IsNullOrWhiteSpace(aid))
            conditions.Add(new PropertyCondition(AutomationElement.AutomationIdProperty, aid));

        if (parts.TryGetValue("name", out string? name) && !string.IsNullOrWhiteSpace(name))
            conditions.Add(new PropertyCondition(AutomationElement.NameProperty, name));

        if (conditions.Count == 0) return null;
        return conditions.Count == 1 ? conditions[0] : new AndCondition(conditions.ToArray());
    }

    // Los separadores del formato tienen que sobrevivir dentro de un Name arbitrario.
    private static string Escape(string v) =>
        (v ?? "").Replace("%", "%25").Replace(";", "%3B").Replace("=", "%3D");

    private static string Unescape(string v) =>
        (v ?? "").Replace("%3D", "=").Replace("%3B", ";").Replace("%25", "%");
}
