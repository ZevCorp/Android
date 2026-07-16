using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;

namespace U.Graph.Surfaces;

/// <summary>
/// Enganche de los eventos COM de <c>GuiSession</c> (Change/StartRequest/EndRequest/ErrorMessage) SIN
/// referenciar la type library de SAP (sapfewse.ocx) — el resto de esta superficie ya habla con SAP
/// por enlace tardío a propósito (ver <see cref="SapGuiSurface"/>), y esto sigue el mismo criterio.
///
/// CÓMO: <see cref="ComEventsHelper"/> es la única API pública de .NET para suscribirse a un evento COM
/// sin PIA, pero exige el IID de la interfaz de origen y el DISPID exacto del método — ninguno de los
/// dos se puede escribir a mano sin la type library. Se resuelven en runtime:
///   1. <c>IProvideClassInfo2.GetGUID(GUIDKIND_DEFAULT_SOURCE_DISP_IID, ...)</c> da el IID directo; si
///      el objeto no lo implementa, se cae a recorrer <c>IProvideClassInfo.GetClassInfo</c> → los
///      ImplTypes marcados <c>[source]</c> (IMPLTYPEFLAG_FSOURCE) del ClassInfo.
///   2. Con el ITypeInfo de esa interfaz, se enumeran sus FUNCDESC y se busca por NOMBRE (no por
///      DISPID fijo, que podría no coincidir entre versiones de SAP GUI) los eventos que interesan.
///   3. <see cref="ComEventsHelper.Combine"/> exige un delegado con EXACTAMENTE la aridad del evento
///      COM (lo invoca reflejado); como no se conoce de antemano, se arma según <c>FUNCDESC.cParams</c>.
///
/// NO VERIFICADO CONTRA UN SAP REAL — no hay SAP GUI en la máquina donde se escribió esto. Por diseño,
/// cualquier fallo en cualquiera de estos pasos devuelve <c>false</c> con el motivo en vez de lanzar:
/// quien llama (<see cref="SapGuiSurface"/>) cae a sondeo. <see cref="Diagnostic"/> vuelca cada nombre/
/// DISPID/aridad encontrado la primera vez que esto corre contra un SAP real — es la única forma de
/// confirmar que la introspección calzó sin tener SAP GUI a mano.
/// </summary>
internal sealed class SapComEvents : IDisposable
{
    private const int GuidKindDefaultSourceDispIid = 1; // GUIDKIND_DEFAULT_SOURCE_DISP_IID

    /// <summary>Eventos de GuiSession que nos interesan (ver INVESTIGACION-SAPGUI-UIA.md §1).</summary>
    private static readonly string[] WantedEvents = { "Change", "StartRequest", "EndRequest", "ErrorMessage" };

    /// <summary>Volcado de qué se encontró/enganchó — para que SapGuiSurface lo mande a LogBus.</summary>
    public event EventHandler<string>? Diagnostic;

    /// <summary>(nombre del evento, argumentos crudos tal como los entregó el COM).</summary>
    public event EventHandler<(string Name, object?[] Args)>? Raised;

    private readonly object _com;
    private readonly List<(Guid Iid, int DispId, Delegate Sink)> _hooked = new();

    public SapComEvents(object com) => _com = com;

    /// <summary>Intenta enganchar todos los eventos conocidos. true si enganchó al menos uno.</summary>
    public bool TryHook(out string reason)
    {
        reason = "";
        if (!TryResolveSourceInterface(_com, out Guid iid, out ITypeInfo? sourceInfo) || sourceInfo == null)
        {
            reason = "no se pudo resolver la interfaz de eventos de origen (IProvideClassInfo/2)";
            return false;
        }

        int hooked = 0;
        foreach (var (name, dispId, arity) in EnumerateMethods(sourceInfo))
        {
            if (!WantedEvents.Contains(name, StringComparer.OrdinalIgnoreCase)) continue;
            try
            {
                Delegate sink = SinkFor(name, arity);
                ComEventsHelper.Combine(_com, iid, dispId, sink);
                _hooked.Add((iid, dispId, sink));
                hooked++;
                Diagnostic?.Invoke(this, $"enganchado {name} (dispid={dispId}, args={arity})");
            }
            catch (Exception e)
            {
                Diagnostic?.Invoke(this, $"no se pudo enganchar {name} (dispid={dispId}, args={arity}): {e.Message}");
            }
        }

        if (hooked == 0)
        {
            reason = "ningún evento conocido (Change/StartRequest/EndRequest/ErrorMessage) se pudo enganchar";
            return false;
        }
        return true;
    }

    public void Unhook()
    {
        foreach (var (iid, dispId, sink) in _hooked)
        {
            try { ComEventsHelper.Remove(_com, iid, dispId, sink); } catch { /* la sesión pudo morir antes */ }
        }
        _hooked.Clear();
    }

    public void Dispose() => Unhook();

    // ── Sinks: ComEventsHelper exige un delegado con la aridad EXACTA del evento COM ───────────────

    private Delegate SinkFor(string name, int arity) => arity switch
    {
        0 => new Action(() => Raised?.Invoke(this, (name, Array.Empty<object?>()))),
        1 => new Action<object?>(a => Raised?.Invoke(this, (name, new[] { a }))),
        2 => new Action<object?, object?>((a, b) => Raised?.Invoke(this, (name, new[] { a, b }))),
        3 => new Action<object?, object?, object?>((a, b, c) => Raised?.Invoke(this, (name, new[] { a, b, c }))),
        4 => new Action<object?, object?, object?, object?>((a, b, c, d) => Raised?.Invoke(this, (name, new[] { a, b, c, d }))),
        _ => throw new NotSupportedException($"el evento «{name}» tiene {arity} parámetros; no soportado (máximo 4)"),
    };

    // ── Introspección: IProvideClassInfo(2) → interfaz [source] → sus métodos ──────────────────────

    private static bool TryResolveSourceInterface(object com, out Guid iid, out ITypeInfo? typeInfo)
    {
        iid = Guid.Empty; typeInfo = null;

        if (com is IProvideClassInfo2 pci2)
        {
            try
            {
                pci2.GetGUID(GuidKindDefaultSourceDispIid, out iid);
                if (iid != Guid.Empty && TryGetTypeInfoOfGuid(com, iid, out typeInfo)) return true;
            }
            catch { /* cae al camino largo de abajo */ }
        }

        if (com is IProvideClassInfo pci)
        {
            try
            {
                pci.GetClassInfo(out ITypeInfo classInfo);
                return TryFindSourceInterface(classInfo, out iid, out typeInfo);
            }
            catch { return false; }
        }

        return false;
    }

    private static bool TryGetTypeInfoOfGuid(object com, Guid iid, out ITypeInfo? typeInfo)
    {
        typeInfo = null;
        if (com is not IProvideClassInfo pci) return false;
        try
        {
            pci.GetClassInfo(out ITypeInfo classInfo);
            classInfo.GetContainingTypeLib(out ITypeLib lib, out _);
            lib.GetTypeInfoOfGuid(ref iid, out typeInfo);
            return typeInfo != null;
        }
        catch { return false; }
    }

    private static bool TryFindSourceInterface(ITypeInfo classInfo, out Guid iid, out ITypeInfo? typeInfo)
    {
        iid = Guid.Empty; typeInfo = null;
        IntPtr attrPtr = IntPtr.Zero;
        try
        {
            classInfo.GetTypeAttr(out attrPtr);
            var attr = Marshal.PtrToStructure<TYPEATTR>(attrPtr);
            for (int i = 0; i < attr.cImplTypes; i++)
            {
                try
                {
                    classInfo.GetImplTypeFlags(i, out IMPLTYPEFLAGS flags);
                    if ((flags & IMPLTYPEFLAGS.IMPLTYPEFLAG_FSOURCE) == 0) continue;

                    classInfo.GetRefTypeOfImplType(i, out int href);
                    classInfo.GetRefTypeInfo(href, out ITypeInfo srcInfo);
                    var srcAttr = GetTypeAttrSafe(srcInfo);
                    if (srcAttr == null) continue;

                    iid = srcAttr.Value.guid;
                    typeInfo = srcInfo;
                    return true;
                }
                catch { continue; }
            }
        }
        catch { return false; }
        finally { if (attrPtr != IntPtr.Zero) classInfo.ReleaseTypeAttr(attrPtr); }

        return false;
    }

    private static TYPEATTR? GetTypeAttrSafe(ITypeInfo info)
    {
        IntPtr p = IntPtr.Zero;
        try { info.GetTypeAttr(out p); return Marshal.PtrToStructure<TYPEATTR>(p); }
        catch { return null; }
        finally { if (p != IntPtr.Zero) info.ReleaseTypeAttr(p); }
    }

    private static IEnumerable<(string Name, int DispId, int Arity)> EnumerateMethods(ITypeInfo info)
    {
        IntPtr attrPtr = IntPtr.Zero;
        TYPEATTR attr;
        try { info.GetTypeAttr(out attrPtr); attr = Marshal.PtrToStructure<TYPEATTR>(attrPtr); }
        finally { if (attrPtr != IntPtr.Zero) info.ReleaseTypeAttr(attrPtr); }

        for (int i = 0; i < attr.cFuncs; i++)
        {
            IntPtr fdPtr = IntPtr.Zero;
            FUNCDESC fd;
            try
            {
                info.GetFuncDesc(i, out fdPtr);
                fd = Marshal.PtrToStructure<FUNCDESC>(fdPtr);
            }
            catch { continue; }
            finally { if (fdPtr != IntPtr.Zero) info.ReleaseFuncDesc(fdPtr); }

            string name;
            try { info.GetDocumentation(fd.memid, out name, out _, out _, out _); }
            catch { continue; }

            if (!string.IsNullOrWhiteSpace(name))
                yield return (name, fd.memid, fd.cParams);
        }
    }

    // ── Interfaces OLE estándar (GUIDs fijos de la especificación, no de SAP) ───────────────────────

    [ComImport, Guid("B196B283-BAB4-101A-B69C-00AA00341D07"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IProvideClassInfo
    {
        void GetClassInfo(out ITypeInfo ppTI);
    }

    [ComImport, Guid("A6BC3AC0-DBAA-11CE-9DE3-00AA004BB851"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IProvideClassInfo2
    {
        void GetClassInfo(out ITypeInfo ppTI);
        void GetGUID(int dwGuidKind, out Guid pGUID);
    }
}
