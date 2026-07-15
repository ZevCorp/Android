using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using U.WindowsClient.Diagnostics;
using Velopack;

namespace U.WindowsClient;

public partial class App : Application
{
    /// <summary>
    /// Entry point manual (ver StartupObject en WindowsClient.csproj). Existe por Velopack: cuando el
    /// updater instala o desinstala una versión relanza este mismo .exe con argumentos de hook y espera
    /// que el proceso los atienda y termine. <c>VelopackApp.Run()</c> hace eso y NO retorna en ese caso
    /// — por eso corre antes de levantar WPF, o cada actualización abriría una carita fantasma.
    /// </summary>
    [STAThread]
    private static void Main(string[] args)
    {
        try
        {
            VelopackApp.Build().Run();

            var app = new App();
            app.InitializeComponent();
            app.Run();
        }
        catch (Exception ex)
        {
            LogBus.Log("fatal", ex.ToString());
            MessageBox.Show($"Ü no pudo arrancar: {ex.Message}", "Ü", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

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
