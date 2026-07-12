using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

namespace U.WindowsClient.Capture;

/// <summary>
/// Captura la pantalla completa a PNG (base64) para computer-use. Se llama SOLO cuando el turno
/// anterior devolvió <c>needsScreenshot</c>: por defecto el cerebro opera con el texto del árbol de UI.
///
/// Captura a la resolución física de pantalla (el manifiesto declara PerMonitorV2), así el screenshot
/// y las coordenadas donde el cerebro pide tocar coinciden 1:1 — sin reescalado.
/// </summary>
public static class Screenshotter
{
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int nIndex);
    private const int SM_CXSCREEN = 0, SM_CYSCREEN = 1;

    public static string CaptureBase64Png()
    {
        int w = GetSystemMetrics(SM_CXSCREEN);
        int h = GetSystemMetrics(SM_CYSCREEN);
        using var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(bmp))
        {
            g.CopyFromScreen(0, 0, 0, 0, new Size(w, h), CopyPixelOperation.SourceCopy);
        }
        using var ms = new MemoryStream();
        bmp.Save(ms, ImageFormat.Png);
        return Convert.ToBase64String(ms.ToArray());
    }
}
