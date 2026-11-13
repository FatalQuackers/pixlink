using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;

namespace Pixlink.Services;

/// <summary>
/// The Pixlink icon in the system tray (by the clock): click to open, right-click for Open / Quit.
/// Plain Win32 Shell_NotifyIcon, so no extra libraries are needed.
/// </summary>
public static class Tray
{
    private const int WM_APP_TRAY = 0x8000 + 1;
    private const int WM_LBUTTONUP = 0x0202, WM_RBUTTONUP = 0x0205, WM_COMMAND = 0x0111;
    private const int NIM_ADD = 0, NIM_DELETE = 2, NIF_MESSAGE = 1, NIF_ICON = 2, NIF_TIP = 4;
    private const int IDM_OPEN = 1, IDM_QUIT = 2;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NOTIFYICONDATA
    {
        public int cbSize; public IntPtr hWnd; public int uID; public int uFlags; public int uCallbackMessage; public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string szTip;
        public int dwState; public int dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string szInfo;
        public int uVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string szInfoTitle;
        public int dwInfoFlags; public Guid guidItem; public IntPtr hBalloonIcon;
    }

    private delegate IntPtr SubclassProc(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam, IntPtr id, IntPtr data);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)] private static extern bool Shell_NotifyIcon(int msg, ref NOTIFYICONDATA data);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern IntPtr LoadImage(IntPtr hInst, string name, int type, int cx, int cy, int load);
    [DllImport("comctl32.dll")] private static extern bool SetWindowSubclass(IntPtr hWnd, SubclassProc proc, IntPtr id, IntPtr data);
    [DllImport("comctl32.dll")] private static extern IntPtr DefSubclassProc(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] private static extern IntPtr CreatePopupMenu();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool AppendMenu(IntPtr menu, int flags, int id, string text);
    [DllImport("user32.dll")] private static extern int TrackPopupMenu(IntPtr menu, int flags, int x, int y, int reserved, IntPtr hWnd, IntPtr rect);
    [DllImport("user32.dll")] private static extern bool DestroyMenu(IntPtr menu);
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out POINT p);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int X, Y; }

    private static NOTIFYICONDATA _data;
    private static SubclassProc? _proc; // kept alive so the GC doesn't collect the callback
    private static MainWindow? _window;
    private static bool _created;

    public static void Create(MainWindow window)
    {
        try
        {
            _window = window;
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(window);
            _proc = WndProc;
            SetWindowSubclass(hwnd, _proc, (IntPtr)101, IntPtr.Zero);
            var icon = LoadImage(IntPtr.Zero, Path.Combine(AppContext.BaseDirectory, "Assets", "Pixlink.ico"), 1 /*IMAGE_ICON*/, 16, 16, 0x10 /*LR_LOADFROMFILE*/);
            _data = new NOTIFYICONDATA
            {
                cbSize = Marshal.SizeOf<NOTIFYICONDATA>(), hWnd = hwnd, uID = 1, uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP,
                uCallbackMessage = WM_APP_TRAY, hIcon = icon, szTip = "Tethr Link", szInfo = "", szInfoTitle = "",
            };
            _created = Shell_NotifyIcon(NIM_ADD, ref _data);
        }
        catch (Exception ex) { Log.Error("Couldn't add the tray icon", ex); }
    }

    public static void Dispose()
    {
        if (!_created) return;
        _created = false;
        Shell_NotifyIcon(NIM_DELETE, ref _data);
    }

    private static IntPtr WndProc(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam, IntPtr id, IntPtr data)
    {
        if (msg == WM_APP_TRAY)
        {
            var ev = (int)lParam & 0xFFFF;
            if (ev == WM_LBUTTONUP) _window?.BringToFront();
            else if (ev == WM_RBUTTONUP) ShowMenu(hWnd);
            return IntPtr.Zero;
        }
        if (msg == WM_COMMAND)
        {
            switch ((int)wParam & 0xFFFF)
            {
                case IDM_OPEN: _window?.BringToFront(); return IntPtr.Zero;
                case IDM_QUIT: _window?.Quit(); return IntPtr.Zero;
            }
        }
        return DefSubclassProc(hWnd, msg, wParam, lParam);
    }

    private static void ShowMenu(IntPtr hWnd)
    {
        var menu = CreatePopupMenu();
        AppendMenu(menu, 0, IDM_OPEN, "Open Tethr Link");
        AppendMenu(menu, 0x800 /*MF_SEPARATOR*/, 0, "");
        AppendMenu(menu, 0, IDM_QUIT, "Quit Tethr Link");
        GetCursorPos(out var p);
        SetForegroundWindow(hWnd);
        TrackPopupMenu(menu, 0, p.X, p.Y, 0, hWnd, IntPtr.Zero);
        DestroyMenu(menu);
    }
}
