using System.Collections.ObjectModel;

namespace Pixlink.Services;

/// <summary>
/// Pixlink's activity log: shown in Settings → Logs and kept in pixlink.log for bug reports.
/// Holds no message contents, only what the app did (connected, sent a file, errors…).
/// </summary>
public static class Log
{
    public sealed record Entry(DateTime Time, string Level, string Text)
    {
        public string TimeText => Time.ToString("HH:mm:ss");
        public string Line => $"{Time:yyyy-MM-dd HH:mm:ss} [{Level}] {Text}";
    }

    private const int Max = 500;
    private static readonly object Gate = new();
    public static ObservableCollection<Entry> Entries { get; } = new();
    public static string FilePath => Path.Combine(AppSettings.Folder, "pixlink.log");

    public static void Info(string text) => Write("info", text);
    public static void Warn(string text) => Write("warn", text);
    public static void Error(string text, Exception? ex = null) => Write("error", ex is null ? text : $"{text}: {ex.Message}");

    private static void Write(string level, string text)
    {
        var e = new Entry(DateTime.Now, level, text);
        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(AppSettings.Folder);
                var info = new FileInfo(FilePath);
                if (info.Exists && info.Length > 1_000_000) File.Move(FilePath, FilePath + ".old", overwrite: true);
                File.AppendAllText(FilePath, e.Line + Environment.NewLine);
            }
            catch (Exception) { /* logging must never break the app */ }
        }
        void Add()
        {
            Entries.Insert(0, e);
            while (Entries.Count > Max) Entries.RemoveAt(Entries.Count - 1);
        }
        var ui = App.Window?.DispatcherQueue;
        if (ui is null || ui.HasThreadAccess) Add(); else ui.TryEnqueue(Add);
    }
}
