using System.Runtime.InteropServices;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;

namespace Pixlink.Services;

/// <summary>Phone → PC: "Find my PC" (rings and flashes) and "Lock PC".</summary>
public static class FindMyPc
{
    private const string Tag = "findpc";

    /// <summary>A looping alarm pop-up (stays until dismissed) plus a flashing, front-most Pixlink window.</summary>
    public static void Ring(string phoneName)
    {
        try
        {
            var toast = new AppNotificationBuilder()
                .AddText("Here I am!")
                .AddText($"{phoneName} is looking for this PC.")
                .SetScenario(AppNotificationScenario.Alarm)
                .SetAudioEvent(AppNotificationSoundEvent.Alarm2, AppNotificationAudioLooping.Loop)
                .AddButton(new AppNotificationButton("Found it").AddArgument("action", "foundpc"))
                .BuildNotification();
            toast.Tag = Tag;
            AppNotificationManager.Default.Show(toast);
        }
        catch (Exception ex) { Log.Error("Find my PC pop-up failed", ex); }

        if (App.Window is { } w)
        {
            w.AppWindow.Show();
            if (w.AppWindow.Presenter is Microsoft.UI.Windowing.OverlappedPresenter p && p.State == Microsoft.UI.Windowing.OverlappedPresenterState.Minimized) p.Restore();
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(w);
            var info = new FLASHWINFO { cbSize = (uint)Marshal.SizeOf<FLASHWINFO>(), hwnd = hwnd, dwFlags = FLASHW_ALL | FLASHW_TIMERNOFG, uCount = 30 };
            FlashWindowEx(ref info);
        }
        Log.Info($"{phoneName} rang this PC");
    }

    public static void Stop()
    {
        try { _ = AppNotificationManager.Default.RemoveByTagAsync(Tag); } catch { }
        if (App.Window is { } w)
        {
            var info = new FLASHWINFO { cbSize = (uint)Marshal.SizeOf<FLASHWINFO>(), hwnd = WinRT.Interop.WindowNative.GetWindowHandle(w), dwFlags = 0 };
            FlashWindowEx(ref info);
        }
    }

    public static void Lock(string phoneName)
    {
        Log.Info($"{phoneName} locked this PC");
        if (!LockWorkStation()) Log.Warn("Couldn't lock the PC");
    }

    [DllImport("user32.dll")] private static extern bool LockWorkStation();
    [DllImport("user32.dll")] private static extern bool FlashWindowEx(ref FLASHWINFO info);
    private const uint FLASHW_ALL = 3, FLASHW_TIMERNOFG = 12;

    [StructLayout(LayoutKind.Sequential)]
    private struct FLASHWINFO
    {
        public uint cbSize;
        public IntPtr hwnd;
        public uint dwFlags;
        public uint uCount;
        public uint dwTimeout;
    }
}
