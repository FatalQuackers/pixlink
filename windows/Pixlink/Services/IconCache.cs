using Microsoft.UI.Xaml.Media.Imaging;

namespace Pixlink.Services;

/// <summary>
/// App icons from the phone, kept on disk so they show straight away after Pixlink restarts
/// (and so pop-ups can show the app's icon, which needs a file).
/// </summary>
public static class IconCache
{
    private static string Folder => Path.Combine(AppSettings.Folder, "icons");

    private static string FileFor(string app) =>
        Path.Combine(Folder, string.Concat(app.Select(ch => char.IsLetterOrDigit(ch) || ch is '.' or '_' ? ch : '_')) + ".png");

    public static void Save(string app, string pngBase64)
    {
        try
        {
            Directory.CreateDirectory(Folder);
            File.WriteAllBytes(FileFor(app), Convert.FromBase64String(pngBase64));
        }
        catch (Exception ex) { Log.Warn($"Couldn't save the icon for {app}: {ex.Message}"); }
    }

    /// <summary>The icon file for an app, if we have one (for pop-ups).</summary>
    public static string? PathFor(string? app) => app is not null && File.Exists(FileFor(app)) ? FileFor(app) : null;

    public static Dictionary<string, BitmapImage> LoadAll()
    {
        var icons = new Dictionary<string, BitmapImage>();
        try
        {
            if (!Directory.Exists(Folder)) return icons;
            foreach (var file in Directory.EnumerateFiles(Folder, "*.png").Take(400))
                icons[Path.GetFileNameWithoutExtension(file)] = PhoneSession.ImageFromBytes(File.ReadAllBytes(file));
        }
        catch (Exception ex) { Log.Warn($"Couldn't load saved app icons: {ex.Message}"); }
        return icons;
    }
}
