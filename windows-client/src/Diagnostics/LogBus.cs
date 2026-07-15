using System.Collections.Generic;

namespace U.WindowsClient.Diagnostics;

/// <summary>
/// Bitácora en memoria de todo lo que pasa en las funcionalidades nuevas (enseñanza por video, etc.):
/// sin esto, un error en una tarea "fire-and-forget" o en un evento de una librería nativa se pierde
/// en el aire y el usuario solo ve un mensaje de estado genérico y sobreescrito. Cada entrada queda
/// aquí, visible en <see cref="U.WindowsClient.Ui.LogWindow"/>, incluso después de que el globo de
/// estado ya mostró otra cosa.
///
/// Mismo patrón que el LogBus de la versión Android (com.zevcorp.graph.platform.LogBus).
/// </summary>
public static class LogBus
{
    private const int MaxEntries = 500;
    private static readonly List<string> _entries = new();
    private static readonly object _lock = new();

    public static event EventHandler<string>? Logged;

    public static void Log(string tag, string message)
    {
        string line = $"[{DateTime.Now:HH:mm:ss}] {tag}: {message}";
        lock (_lock)
        {
            _entries.Add(line);
            if (_entries.Count > MaxEntries) _entries.RemoveAt(0);
        }
        Logged?.Invoke(null, line);
    }

    public static IReadOnlyList<string> Snapshot()
    {
        lock (_lock) return _entries.ToArray();
    }

    public static void Clear()
    {
        lock (_lock) _entries.Clear();
    }
}
