using System.Windows;
using System.Windows.Threading;

namespace U.WindowsClient;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // Nunca dejar caer la carita por una excepción no capturada: es un overlay permanente.
        DispatcherUnhandledException += (_, ex) =>
        {
            MessageBox.Show($"Ü tropezó: {ex.Exception.Message}", "Ü", MessageBoxButton.OK, MessageBoxImage.Warning);
            ex.Handled = true;
        };
    }
}
