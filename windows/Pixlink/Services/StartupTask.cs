using Microsoft.Win32;

namespace Pixlink.Services;

/// <summary>"Start Tethr Link when I sign in": a value under HKCU\...\Run (no admin needed).</summary>
public static class StartupTask
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "Pixlink";

    private static string ExePath => Path.Combine(AppContext.BaseDirectory, "Pixlink.exe");

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(Name) is string;
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue(Name, $"\"{ExePath}\" --background");
        else key.DeleteValue(Name, throwOnMissingValue: false);
    }
}
