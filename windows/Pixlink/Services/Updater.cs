using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Text.Json.Nodes;

namespace Pixlink.Services;

/// <summary>
/// Checks GitHub for a newer release and installs it: downloads the zip, then a small
/// script waits for Pixlink to close, copies the new files over this folder and restarts it.
/// </summary>
public static class Updater
{
    public sealed record Update(Version Version, string ZipUrl, string PageUrl);

    private const string Latest = "https://api.github.com/repos/FatalQuackers/pixlink/releases/latest";
    private static readonly HttpClient Http = CreateClient();

    private static HttpClient CreateClient()
    {
        var c = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        c.DefaultRequestHeaders.UserAgent.ParseAdd("Pixlink-Updater");
        c.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return c;
    }

    /// <summary>This build's version (set by CI from the release number; 0.1.0 for local builds).</summary>
    public static Version Current
    {
        get
        {
            var v = typeof(Updater).Assembly.GetName().Version ?? new Version(0, 1, 0);
            return new Version(v.Major, v.Minor, Math.Max(v.Build, 0));
        }
    }

    /// <summary>Returns the newer release, or null if this is the latest. Throws if offline.</summary>
    public static async Task<Update?> CheckAsync()
    {
        var json = JsonNode.Parse(await Http.GetStringAsync(Latest))!.AsObject();
        var tag = json["tag_name"]!.GetValue<string>().TrimStart('v');
        if (!Version.TryParse(tag, out var latest)) return null;
        latest = new Version(latest.Major, latest.Minor, Math.Max(latest.Build, 0));

        var zip = json["assets"]?.AsArray()
            .Select(a => a!.AsObject())
            .FirstOrDefault(a => a["name"]?.GetValue<string>().EndsWith("windows-x64.zip") == true)?["browser_download_url"]?.GetValue<string>();
        if (zip is null || latest <= Current) return null;
        return new Update(latest, zip, json["html_url"]!.GetValue<string>());
    }

    /// <summary>Downloads and installs the update, then closes Pixlink (it restarts by itself).</summary>
    public static async Task InstallAsync(Update update, IProgress<double>? progress = null)
    {
        var work = Path.Combine(Path.GetTempPath(), "PixlinkUpdate");
        if (Directory.Exists(work)) Directory.Delete(work, recursive: true);
        Directory.CreateDirectory(work);
        var zipPath = Path.Combine(work, "pixlink.zip");
        var newDir = Path.Combine(work, "new");

        using (var resp = await Http.GetAsync(update.ZipUrl, HttpCompletionOption.ResponseHeadersRead))
        {
            resp.EnsureSuccessStatusCode();
            var total = resp.Content.Headers.ContentLength ?? 0;
            await using var src = await resp.Content.ReadAsStreamAsync();
            await using var dst = File.Create(zipPath);
            var buf = new byte[81920];
            long done = 0;
            int n;
            while ((n = await src.ReadAsync(buf)) > 0)
            {
                await dst.WriteAsync(buf.AsMemory(0, n));
                done += n;
                if (total > 0) progress?.Report((double)done / total);
            }
        }
        ZipFile.ExtractToDirectory(zipPath, newDir);

        var appDir = AppContext.BaseDirectory.TrimEnd('\\');
        var pid = Environment.ProcessId;
        var script = Path.Combine(work, "update.cmd");
        await File.WriteAllTextAsync(script, $"""
            @echo off
            :wait
            tasklist /FI "PID eq {pid}" | find "{pid}" >nul && (timeout /t 1 /nobreak >nul & goto wait)
            robocopy "{newDir}" "{appDir}" /E /R:5 /W:1 /NFL /NDL /NJH /NJS /NP >nul
            start "" "{appDir}\Pixlink.exe" --updated
            """);

        Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{script}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            WindowStyle = ProcessWindowStyle.Hidden,
        });
        Environment.Exit(0);
    }
}
