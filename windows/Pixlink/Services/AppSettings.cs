using System.Text.Json;

namespace Pixlink.Services;

/// <summary>Settings saved to %LOCALAPPDATA%\Pixlink\settings.json.</summary>
public sealed class AppSettings
{
    public string PcId { get; set; } = Guid.NewGuid().ToString("N");
    public string? PhoneFingerprint { get; set; }
    public string? PhoneName { get; set; }
    public bool ShowToasts { get; set; } = true;
    public bool AutoJoinHotspot { get; set; } = true;
    public string? PcDisplayName { get; set; }

    // Notifications
    public bool ToastShowContent { get; set; } = true;
    public bool ToastSound { get; set; } = true;
    public HashSet<string> MutedApps { get; set; } = new();

    // Messages tab
    public HashSet<string> PinnedConversations { get; set; } = new();
    public HashSet<string> HiddenConversations { get; set; } = new();
    /// <summary>"pixlink" (conversations from notifications) or "google" (Google Messages for Web).</summary>
    public string MessagesView { get; set; } = "pixlink";

    // Phone battery
    public bool LowBatteryAlert { get; set; } = true;
    public int LowBatteryLevel { get; set; } = 15;

    // Screen mirroring
    public int MirrorMaxSize { get; set; } = 1600;
    public int MirrorBitrateMbps { get; set; } = 8;
    public int MirrorFps { get; set; } = 60;

    // App
    public string Theme { get; set; } = "System";
    public bool AutoCheckUpdates { get; set; } = true;
    public bool StartWithWindows { get; set; }

    public static string Folder { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pixlink");

    private static string FilePath => Path.Combine(Folder, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath)) ?? new AppSettings();
        }
        catch (Exception) { /* corrupt file: start fresh */ }
        var s = new AppSettings();
        s.Save();
        return s;
    }

    public void Save()
    {
        Directory.CreateDirectory(Folder);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }
}
