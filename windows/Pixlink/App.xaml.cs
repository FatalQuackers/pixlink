using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;
using Pixlink.Services;

namespace Pixlink;

public partial class App : Application
{
    /// <summary>The one paired phone and everything we know about it. Created in OnLaunched.</summary>
    public static PhoneSession Session { get; private set; } = null!;

    public static MainWindow? Window { get; private set; }

    public App()
    {
        // Any crash is written to a log and shown in a message box instead of vanishing silently.
        // Non-fatal UI errors: keep running and log them (WinRT errors carry their text in e.Message).
        UnhandledException += (_, e) => { e.Handled = true; Crash.Log(e.Exception, $"UI error: {e.Message}"); };
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Crash.Report(e.ExceptionObject as Exception, fatal: true);
        TaskScheduler.UnobservedTaskException += (_, e) => { Crash.Log(e.Exception); e.SetObserved(); };

        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            Session = new PhoneSession();
            Toasts.Register();
            Window = new MainWindow();
            Window.Activate();
            // Started with Windows: stay out of the way in the taskbar.
            if (Environment.GetCommandLineArgs().Contains("--background") &&
                Window.AppWindow.Presenter is Microsoft.UI.Windowing.OverlappedPresenter p) p.Minimize();
            _ = Session.StartAsync(Window.DispatcherQueue);
        }
        catch (Exception ex)
        {
            Crash.Report(ex, fatal: true);
        }
    }
}

/// <summary>Writes crashes to %LOCALAPPDATA%\Pixlink\crash.log and tells the user.</summary>
internal static class Crash
{
    public static string LogPath => Path.Combine(AppSettings.Folder, "crash.log");

    public static void Log(Exception? ex, string? context = null)
    {
        try
        {
            Directory.CreateDirectory(AppSettings.Folder);
            var hr = ex is null ? "" : $" (HRESULT 0x{ex.HResult:X8})";
            File.AppendAllText(LogPath,
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] v{Services.Updater.Current} {context}{hr}\n{ex}\n{ex?.StackTrace}\n\n");
        }
        catch { /* nowhere left to report */ }
    }

    public static void Report(Exception? ex, bool fatal)
    {
        Log(ex);
        var text = $"{(fatal ? "Pixlink couldn't start." : "Something went wrong in Pixlink.")}\n\n" +
                   $"{ex?.GetType().Name}: {ex?.Message}\n\n" +
                   $"Details were saved to:\n{LogPath}\n\nPlease share that file in a GitHub issue.";
        MessageBoxW(IntPtr.Zero, text, "Pixlink", 0x10 /* MB_ICONERROR */);
        if (fatal) Environment.Exit(1);
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(IntPtr hWnd, string text, string caption, uint type);
}
