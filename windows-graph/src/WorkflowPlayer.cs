using U.Graph.Surfaces;

namespace U.Graph;

/// <summary>Cómo fue un paso. La UI narra esto mientras el workflow corre.</summary>
public sealed record StepOutcome(int StepOrder, string Label, string ActionType, bool Ok, string Error);

/// <summary>Resultado de ejecutar un workflow entero.</summary>
public sealed record RunResult(bool Ok, string WorkflowId, IReadOnlyList<StepOutcome> Steps, string Error)
{
    public int Completed => Steps.Count(s => s.Ok);
}

/// <summary>
/// Ejecuta un workflow de Graph sobre la superficie que toque.
///
/// El reparto de responsabilidades es el de Graph y no se negocia: el backend decide QUÉ hacer
/// (devuelve el plan) y este proceso decide CÓMO tocarlo en Windows. Graph nunca controla la máquina.
///
/// PARA ARRIBA EN PRODUCCIÓN: esto corre sobre el SAP real de un cliente, así que las dos decisiones
/// de diseño importantes son conservadoras —
///   1. Se verifica que estamos en la pantalla correcta ANTES de tocar nada.
///   2. Al primer paso que falla, se para. Un formulario a medio llenar es recuperable por un humano;
///      uno llenado a ciegas con los campos corridos, no.
/// </summary>
public sealed class WorkflowPlayer
{
    private readonly GraphClient _graph;
    private readonly GraphConfig _config;
    private readonly IReadOnlyList<IUiSurface> _surfaces;

    /// <summary>Se dispara por cada paso ejecutado.</summary>
    public event EventHandler<StepOutcome>? StepDone;

    public WorkflowPlayer(GraphClient graph, GraphConfig config, params IUiSurface[] surfaces)
    {
        _graph = graph;
        _config = config;
        _surfaces = surfaces;
    }

    /// <summary>
    /// Pide el plan a Graph y lo ejecuta. <paramref name="strictSurface"/> exige que la pantalla actual
    /// coincida con la que se grabó; ponerlo en false permite forzar la ejecución bajo tu criterio.
    /// </summary>
    public async Task<RunResult> RunAsync(
        string workflowId,
        Dictionary<string, string>? variables,
        bool strictSurface,
        CancellationToken ct)
    {
        ExecutionPlan plan;
        try
        {
            plan = await _graph.GetPlanAsync(workflowId, variables, new Dictionary<string, string>
            {
                ["source"] = "windows-u",
                ["surface"] = "native",
            }, ct);
        }
        catch (GraphException e)
        {
            return new RunResult(false, workflowId, Array.Empty<StepOutcome>(), e.Message);
        }

        if (plan.Steps.Count == 0)
            return new RunResult(false, workflowId, Array.Empty<StepOutcome>(),
                "Graph no devolvió ningún paso ejecutable para este workflow.");

        // La superficie se deduce del selector del primer paso, no de una config: el workflow sabe
        // dónde nació.
        IUiSurface? surface = SurfaceFor(plan.Steps[0].Selector);
        if (surface == null)
            return new RunResult(false, workflowId, Array.Empty<StepOutcome>(),
                $"Este workflow se grabó en una superficie que este cliente no maneja ({plan.Steps[0].Selector}).");

        var availability = surface.Check();
        if (!availability.Available)
            return new RunResult(false, workflowId, Array.Empty<StepOutcome>(), availability.Reason);

        if (strictSurface)
        {
            string? mismatch = SurfaceMismatch(surface.Identity(), plan);
            if (mismatch != null)
                return new RunResult(false, workflowId, Array.Empty<StepOutcome>(), mismatch);
        }

        var outcomes = new List<StepOutcome>();
        foreach (PlanStep step in plan.Steps.OrderBy(s => s.StepOrder))
        {
            if (ct.IsCancellationRequested)
                return new RunResult(false, workflowId, outcomes, "Ejecución cancelada.");

            // Un workflow puede cruzar superficies (empieza en UIA, sigue en SAP): se reelige por paso.
            IUiSurface? target = SurfaceFor(step.Selector) ?? surface;

            bool ok;
            try
            {
                // El resultado y el motivo se capturan en la MISMA llamada. Reintentar para recuperar
                // el error ejecutaría la acción dos veces contra el SAP del cliente.
                (bool done, string reason) = await Task.Run(() =>
                {
                    bool r = target.Execute(step, out string err);
                    return (r, err);
                }, ct);

                ok = Report(step, done, done ? "" : Reason(reason), outcomes);
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception e)
            {
                ok = Report(step, false, e.Message, outcomes);
            }

            if (!ok)
                return new RunResult(false, workflowId, outcomes,
                    $"Se detuvo en el paso {step.StepOrder} («{step.Label}»): {outcomes[^1].Error}");

            await Task.Delay(Math.Clamp(_config.StepDelayMs, 0, 5000), ct);
        }

        return new RunResult(true, workflowId, outcomes, "");
    }

    private bool Report(PlanStep step, bool ok, string error, List<StepOutcome> acc)
    {
        var outcome = new StepOutcome(step.StepOrder, step.Label ?? "", step.ActionType, ok, error);
        acc.Add(outcome);
        try { StepDone?.Invoke(this, outcome); } catch { }
        return ok;
    }

    private static string Reason(string error) =>
        string.IsNullOrWhiteSpace(error) ? "el paso no se pudo ejecutar" : error;

    private IUiSurface? SurfaceFor(string selector)
    {
        if (SapSelector.Owns(selector)) return _surfaces.FirstOrDefault(s => s.Name == "sap");
        if (UiaSelector.Owns(selector)) return _surfaces.FirstOrDefault(s => s.Name == "uia");
        return null;
    }

    /// <summary>
    /// ¿Estamos donde se grabó? Se compara el origen (sistema SAP o proceso) y la transacción/ruta.
    /// El título no: cambia con el documento abierto y daría falsos negativos constantes.
    /// </summary>
    private static string? SurfaceMismatch(SurfaceIdentity now, ExecutionPlan plan)
    {
        if (!string.IsNullOrWhiteSpace(plan.SourceOrigin) &&
            !string.Equals(now.Origin, plan.SourceOrigin, StringComparison.OrdinalIgnoreCase))
            return $"Este workflow se grabó en {plan.SourceOrigin} y ahora estás en {now.Origin}.";

        if (!string.IsNullOrWhiteSpace(plan.SourcePathname) &&
            !string.Equals(now.Pathname, plan.SourcePathname, StringComparison.OrdinalIgnoreCase))
            return $"Este workflow se grabó en {plan.SourcePathname} y ahora estás en {now.Pathname}.";

        return null;
    }
}
