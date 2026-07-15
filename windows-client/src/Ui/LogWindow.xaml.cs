using System.Linq;
using System.Windows;
using U.WindowsClient.Diagnostics;

namespace U.WindowsClient.Ui;

/// <summary>Ventana simple de registro en vivo, alimentada por <see cref="LogBus"/>.</summary>
public partial class LogWindow : Window
{
    public LogWindow()
    {
        InitializeComponent();
        foreach (var line in LogBus.Snapshot()) List.Items.Add(line);
        ScrollToEnd();

        LogBus.Logged += OnLogged;
        Closed += (_, _) => LogBus.Logged -= OnLogged;
    }

    private void OnLogged(object? sender, string line) => Dispatcher.Invoke(() =>
    {
        List.Items.Add(line);
        ScrollToEnd();
    });

    private void ScrollToEnd()
    {
        if (List.Items.Count > 0) List.ScrollIntoView(List.Items[^1]);
    }

    private void OnClear(object sender, RoutedEventArgs e)
    {
        LogBus.Clear();
        List.Items.Clear();
    }

    private void OnCopy(object sender, RoutedEventArgs e)
    {
        string text = string.Join(Environment.NewLine, List.Items.Cast<string>());
        try
        {
            Clipboard.SetText(text.Length > 0 ? text : "(sin entradas)");
            CopyBtn.Content = "✅ Copiado";
        }
        catch
        {
            CopyBtn.Content = "⚠ No se pudo copiar";
        }
        finally
        {
            var reset = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
            reset.Tick += (_, _) => { CopyBtn.Content = "📋 Copiar"; reset.Stop(); };
            reset.Start();
        }
    }
}
