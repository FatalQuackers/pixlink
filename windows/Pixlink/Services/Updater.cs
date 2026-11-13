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
    public sealed record Update(Version Version, string ZipUrl, string PageUrl, string Notes = "", DateTimeOffset? Published = null, bool IsEarly = false);

    /// <summary>The steps the updating screen shows.</summary>
    public enum Stage { Downloading, Installing, Restarting }

    private const string Latest = "https://api.github.com/repos/FatalQuackers/tethr-link/releases/latest";
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

    /// <summary>This build's build number (the CI run; 0 for local builds). A rebuild of the same version has a higher one.</summary>
    public static int CurrentBuild => Math.Max(typeof(Updater).Assembly.GetName().Version?.Revision ?? 0, 0);

    private static int BuildOf(string? body) =>
        System.Text.RegularExpressions.Regex.Match(body ?? "", @"<!--\s*build:(\d+)\s*-->") is { Success: true } m ? int.Parse(m.Groups[1].Value) : 0;

    /// <summary>Early updates: also take pre-releases, which everyone else gets a day later.</summary>
    public static bool Early => App.Session.Settings.EarlyUpdates;

    public static async Task<Update?> CheckAsync()
    {
        JsonObject json;
        if (Early)
        {
            var all = JsonNode.Parse(await Http.GetStringAsync("https://api.github.com/repos/FatalQuackers/tethr-link/releases?per_page=10"))!.AsArray()
                .Select(r => r!.AsObject())
                .Where(r => r["draft"]?.GetValue<bool>() != true)
                .Where(r => r["assets"]?.AsArray().Any(a => a!["name"]?.GetValue<string>().EndsWith("windows-x64.zip") == true) == true)
                .Select(r => (r, v: Version.TryParse(r["tag_name"]!.GetValue<string>().TrimStart('v'), out var v) ? v : null))
                .Where(x => x.v is not null)
                .OrderByDescending(x => x.v)
                .ToList();
            if (all.Count == 0) return null;
            json = all[0].r;
        }
        else json = JsonNode.Parse(await Http.GetStringAsync(Latest))!.AsObject();
        var tag = json["tag_name"]!.GetValue<string>().TrimStart('v');
        if (!Version.TryParse(tag, out var latest)) return null;
        latest = new Version(latest.Major, latest.Minor, Math.Max(latest.Build, 0));

        var zip = json["assets"]?.AsArray()
            .Select(a => a!.AsObject())
            .FirstOrDefault(a => a["name"]?.GetValue<string>().EndsWith("windows-x64.zip") == true)?["browser_download_url"]?.GetValue<string>();
        var build = BuildOf(json["body"]?.GetValue<string>());
        if (zip is null || latest < Current || (latest == Current && build <= CurrentBuild)) return null;
        return new Update(latest, zip, json["html_url"]!.GetValue<string>(), Clean(json["body"]?.GetValue<string>()),
            json["published_at"] is { } p ? DateTimeOffset.Parse(p.GetValue<string>()) : null,
            json["prerelease"]?.GetValue<bool>() == true);
    }

    /// <summary>Recent releases with their notes, newest first.</summary>
    public static async Task<List<ReleaseInfo>> RecentReleasesAsync()
    {
        var arr = JsonNode.Parse(await Http.GetStringAsync("https://api.github.com/repos/FatalQuackers/tethr-link/releases?per_page=8"))!.AsArray();
        var list = new List<ReleaseInfo>();
        foreach (var r in arr.Select(x => x!.AsObject()))
        {
            if (r["draft"]?.GetValue<bool>() == true) continue;
            var tag = r["tag_name"]!.GetValue<string>().TrimStart('v');
            var date = r["published_at"] is { } p ? DateTimeOffset.Parse(p.GetValue<string>()).LocalDateTime.ToString("d MMMM yyyy") : "";
            var isCurrent = Version.TryParse(tag, out var v) && new Version(v.Major, v.Minor, Math.Max(v.Build, 0)) == Current;
            var early = r["prerelease"]?.GetValue<bool>() == true;
            if (early && !Early) continue; // early versions only show for people who opted in
            list.Add(new ReleaseInfo(tag, date, Clean(r["body"]?.GetValue<string>()), isCurrent, early));
        }
        return list;
    }

    /// <summary>Release notes as plain text: no Markdown symbols, bullets kept.</summary>
    private static string Clean(string? md)
    {
        if (string.IsNullOrWhiteSpace(md)) return "Bug fixes and improvements.";
        var lines = md.Replace("\r", "").Split('\n')
            .Select(l => l.Trim())
            .Where(l => l.Length > 0 && !l.StartsWith("<!--"))
            .Select(l => l.StartsWith("- ") || l.StartsWith("* ") ? "•  " + l[2..] : l)
            .Select(l => l.Replace("**", "").Replace("`", "").TrimStart('#', ' '));
        return string.Join("\n", lines);
    }

    /// <summary>Downloads and installs the update, then closes Pixlink (it restarts by itself).</summary>
    public static async Task InstallAsync(Update update, IProgress<double>? progress = null, Action<Stage>? stage = null)
    {
        stage?.Invoke(Stage.Downloading);
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
        stage?.Invoke(Stage.Installing);
        await Task.Run(() => ZipFile.ExtractToDirectory(zipPath, newDir));

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

        stage?.Invoke(Stage.Restarting);
        await Task.Delay(1200); // let the "Restarting" step show before Pixlink closes
        Process.Start(new ProcessStartInfo("cmd.exe", $"/c \"{script}\"")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            WindowStyle = ProcessWindowStyle.Hidden,
        });
        Environment.Exit(0);
    }
}

/// <summary>One release in the "What's new" history.</summary>
public sealed class ReleaseInfo(string version, string date, string notes, bool isCurrent, bool isEarly = false)
{
    public bool IsEarly { get; } = isEarly;
    public string Version { get; } = version;
    public string Date { get; } = date;
    public string Notes { get; } = notes;
    public bool IsCurrent { get; } = isCurrent;
}
