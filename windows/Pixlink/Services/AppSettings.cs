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
