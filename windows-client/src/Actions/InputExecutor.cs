using System.Runtime.InteropServices;

namespace U.WindowsClient.Actions;

/// <summary>
/// Ejecuta las primitivas de computer-use con <c>SendInput</c> (Win32): el equivalente Windows de las
/// primitivas <c>Phone</c> (tap/type/scroll/swipe/key) que en Android implementaba
/// <c>GraphAccessibilityService.dispatchGesture</c>. Puro I/O; sin ninguna decisión propia.
/// </summary>
public static class InputExecutor
{
    #region Win32 SendInput
    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT { public uint type; public InputUnion U; }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }

    [DllImport("user32.dll")] private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int nIndex);

    private const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    private const uint MOUSEEVENTF_MOVE = 0x0001, MOUSEEVENTF_ABSOLUTE = 0x8000;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004, MOUSEEVENTF_WHEEL = 0x0800;
    private const uint KEYEVENTF_KEYUP = 0x0002, KEYEVENTF_UNICODE = 0x0004;
    private const int SM_CXSCREEN = 0, SM_CYSCREEN = 1;
    #endregion

    public static bool Tap(int x, int y)
    {
        MoveTo(x, y);
        Thread.Sleep(30);
        Mouse(MOUSEEVENTF_LEFTDOWN);
        Mouse(MOUSEEVENTF_LEFTUP);
        return true;
    }

    public static bool Type(int x, int y, string text)
    {
        Tap(x, y);
        Thread.Sleep(60);
        return TypeText(text);
    }

    public static bool TypeText(string text)
    {
        foreach (char c in text)
        {
            SendKeyUnicode(c, false);
            SendKeyUnicode(c, true);
        }
        return true;
    }

    public static bool Scroll(bool down)
    {
        var inp = new INPUT
        {
            type = INPUT_MOUSE,
            U = new InputUnion { mi = new MOUSEINPUT { mouseData = unchecked((uint)(down ? -120 : 120)), dwFlags = MOUSEEVENTF_WHEEL } },
        };
        SendInput(1, new[] { inp }, Marshal.SizeOf<INPUT>());
        return true;
    }

    public static bool Swipe(int x1, int y1, int x2, int y2, int ms)
    {
        MoveTo(x1, y1);
        Mouse(MOUSEEVENTF_LEFTDOWN);
        int steps = Math.Max(10, ms / 15);
        for (int i = 1; i <= steps; i++)
        {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            MoveTo(x, y);
            Thread.Sleep(ms / steps);
        }
        Mouse(MOUSEEVENTF_LEFTUP);
        return true;
    }

    /// <summary>Teclas semánticas que el cerebro envía normalizadas (enter/back/tab/…).</summary>
    public static bool Key(string key)
    {
        ushort vk = key.ToLowerInvariant() switch
        {
            "enter" => 0x0D,
            "back" or "esc" or "escape" => 0x1B,
            "tab" => 0x09,
            "backspace" => 0x08,
            "delete" => 0x2E,
            "up" => 0x26,
            "down" => 0x28,
            "left" => 0x25,
            "right" => 0x27,
            "home" => 0x24,
            "end" => 0x23,
            "space" => 0x20,
            _ => 0,
        };
        if (vk == 0) return false;
        SendVk(vk, false);
        SendVk(vk, true);
        return true;
    }

    private static void MoveTo(int x, int y)
    {
        // Coordenadas absolutas normalizadas a 0..65535 sobre la pantalla primaria.
        int sw = GetSystemMetrics(SM_CXSCREEN), sh = GetSystemMetrics(SM_CYSCREEN);
        int ax = (int)(x * 65535.0 / Math.Max(1, sw - 1));
        int ay = (int)(y * 65535.0 / Math.Max(1, sh - 1));
        var inp = new INPUT
        {
            type = INPUT_MOUSE,
            U = new InputUnion { mi = new MOUSEINPUT { dx = ax, dy = ay, dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE } },
        };
        SendInput(1, new[] { inp }, Marshal.SizeOf<INPUT>());
        SetCursorPos(x, y);
    }

    private static void Mouse(uint flags)
    {
        var inp = new INPUT { type = INPUT_MOUSE, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = flags } } };
        SendInput(1, new[] { inp }, Marshal.SizeOf<INPUT>());
    }

    private static void SendVk(ushort vk, bool up)
    {
        var inp = new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wVk = vk, dwFlags = up ? KEYEVENTF_KEYUP : 0 } } };
        SendInput(1, new[] { inp }, Marshal.SizeOf<INPUT>());
    }

    private static void SendKeyUnicode(char c, bool up)
    {
        var inp = new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | (up ? KEYEVENTF_KEYUP : 0) } },
        };
        SendInput(1, new[] { inp }, Marshal.SizeOf<INPUT>());
    }
}
