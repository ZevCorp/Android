using System.Runtime.InteropServices;

namespace U.WindowsClient.Actions;

/// <summary>
/// Gestos de navegación de Windows por atajos del shell — el equivalente a los gestos de accesibilidad
/// de Android (home, cajón de apps, notificaciones). Puro I/O de teclado.
/// </summary>
public static class Gestures
{
    [DllImport("user32.dll")] private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
    private const uint KEYUP = 2;
    private const byte VK_LWIN = 0x5B, VK_MENU = 0x12 /*Alt*/, VK_TAB = 0x09, VK_SHIFT = 0x10;
    private const byte VK_D = 0x44, VK_A = 0x41, VK_N = 0x4E;

    public static bool ShowDesktop() => Combo(VK_LWIN, VK_D);
    public static bool StartMenu() => Tap(VK_LWIN);
    public static bool NotificationCenter() => Combo(VK_LWIN, VK_N);

    public static bool SwitchWindow(bool next)
    {
        Down(VK_MENU);
        if (!next) Down(VK_SHIFT);
        Tap(VK_TAB);
        if (!next) Up(VK_SHIFT);
        Up(VK_MENU);
        return true;
    }

    private static bool Combo(byte mod, byte key)
    {
        Down(mod); Tap(key); Up(mod);
        return true;
    }

    private static bool Tap(byte vk) { Down(vk); Up(vk); return true; }
    private static void Down(byte vk) => keybd_event(vk, 0, 0, UIntPtr.Zero);
    private static void Up(byte vk) => keybd_event(vk, 0, KEYUP, UIntPtr.Zero);
}
