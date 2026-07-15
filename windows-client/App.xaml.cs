using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using U.WindowsClient.Diagnostics;

namespace U.WindowsClient;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // Nunca dejar caer la carita por una excepción no capturada: es un overlay permanente.
        DispatcherUnhandledException += (_, ex) =>
        {
            LogBus.Log("fatal", ex.Exception.ToString());
            MessageBox.Show($"Ü tropezó: {ex.Exception.Message}", "Ü", MessageBoxButton.OK, MessageBoxImage.Warning);
            ex.Handled = true;
        };

        // Una tarea "fire-and-forget" (p.ej. `_ = algo.EmpezarAsync()`) que revienta NO pasa por
        // DispatcherUnhandledException: se pierde en silencio salvo que se observe aquí. Esto fue
        // exactamente lo que ocultó el primer error real de la enseñanza por video.
        TaskScheduler.UnobservedTaskException += (_, ex) =>
        {
            LogBus.Log("unobserved-task", ex.Exception.ToString());
            ex.SetObserved();
        };
    }
}
