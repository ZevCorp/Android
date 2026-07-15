using System.Diagnostics;
using System.Windows;
using U.WindowsClient.Teach;

namespace U.WindowsClient.Ui;

/// <summary>
/// Biblioteca de videos de enseñanza grabados. Lee de <see cref="VideoLibrary"/> (disco local),
/// reproduce con <see cref="System.Windows.Controls.MediaElement"/> y permite borrar. No sabe nada
/// de grabar ni de procesar — solo mostrar lo que ya existe en disco.
/// </summary>
public partial class VideoLibraryWindow : Window
{
    private readonly VideoLibrary _library;
    private bool _playing;

    public VideoLibraryWindow(VideoLibrary library)
    {
        InitializeComponent();
        _library = library;
        Loaded += (_, _) => Reload();
    }

    /// <summary>Refresca la lista. Se llama al abrir y también tras cada grabación nueva.</summary>
    public void Reload()
    {
        var selected = (List.SelectedItem as TeachVideo)?.Path;
        var videos = _library.List();
        List.ItemsSource = videos.Select(Label).ToList();
        List.Tag = videos; // el backing list real; ItemsSource lleva solo las etiquetas
        if (selected != null)
        {
            int idx = videos.ToList().FindIndex(v => v.Path == selected);
            if (idx >= 0) List.SelectedIndex = idx;
        }
    }

    private static string Label(TeachVideo v) =>
        $"{v.RecordedAt:dd/MM HH:mm}  ·  {v.SizeBytes / 1024.0 / 1024.0:0.0} MB" +
        (string.IsNullOrWhiteSpace(v.Summary) ? "" : $"\n{Trim(v.Summary!)}");

    private static string Trim(string s) => s.Length > 70 ? s[..70] + "…" : s;

    private TeachVideo? Current()
    {
        if (List.Tag is not IReadOnlyList<TeachVideo> videos) return null;
        int i = List.SelectedIndex;
        return i >= 0 && i < videos.Count ? videos[i] : null;
    }

    private void OnSelected(object sender, RoutedEventArgs e)
    {
        var video = Current();
        Stop();
        if (video == null)
        {
            Empty.Visibility = Visibility.Visible;
            Caption.Text = "";
            return;
        }
        Empty.Visibility = Visibility.Collapsed;
        Caption.Text = string.IsNullOrWhiteSpace(video.Summary) ? video.FileName : video.Summary;
        Player.Source = new Uri(video.Path);
    }

    private void OnPlayPause(object sender, RoutedEventArgs e)
    {
        if (Current() == null) return;
        if (_playing) { Player.Pause(); PlayBtn.Content = "▶"; }
        else { Player.Play(); PlayBtn.Content = "⏸"; }
        _playing = !_playing;
    }

    private void OnMediaEnded(object sender, RoutedEventArgs e)
    {
        Player.Position = TimeSpan.Zero;
        Player.Stop();
        PlayBtn.Content = "▶";
        _playing = false;
    }

    private void Stop()
    {
        try { Player.Stop(); } catch { }
        PlayBtn.Content = "▶";
        _playing = false;
    }

    private void OnDelete(object sender, RoutedEventArgs e)
    {
        var video = Current();
        if (video == null) return;
        if (MessageBox.Show($"¿Borrar «{video.FileName}»? No se puede deshacer.", "Borrar video",
                MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes)
            return;

        Stop();
        Player.Source = null;
        _library.Delete(video.Path);
        Reload();
    }

    private void OnOpenFolder(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("explorer.exe", $"\"{_library.Folder}\"")); }
        catch { }
    }

    protected override void OnClosed(EventArgs e)
    {
        Stop();
        base.OnClosed(e);
    }
}
