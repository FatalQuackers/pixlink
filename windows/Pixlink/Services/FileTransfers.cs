using System.Collections.Concurrent;
using System.Text.Json.Nodes;
using Pixlink.Models;

namespace Pixlink.Services;

/// <summary>Files tab and photo downloads: browse the phone and move files both ways.</summary>
public sealed class FileTransfers
{
    private const int Chunk = 256 * 1024;
    private readonly PhoneSession _session;
    private readonly ConcurrentDictionary<string, (FileStream stream, TaskCompletionSource<string> done)> _incoming = new();

    public FileTransfers(PhoneSession session)
    {
        _session = session;
        session.Message += OnMessage;
    }

    public static string DownloadFolder
    {
        get
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "Pixlink");
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    public async Task<(List<FileEntry> roots, bool powerMode)> GetRootsAsync()
    {
        var r = await _session.RequestAsync("files.roots");
        var roots = r?.Arr("roots")?.OfType<JsonObject>()
            .Select(o => new FileEntry(o["name"]!.GetValue<string>(), o["path"]!.GetValue<string>(), true, 0, default))
            .ToList() ?? new();
        return (roots, r?.Bool("powerMode") ?? false);
    }

    public async Task<List<FileEntry>> ListAsync(string path)
    {
        var r = await _session.RequestAsync("files.list", new { path }, TimeSpan.FromSeconds(30));
        return r?.Arr("entries")?.OfType<JsonObject>().Select(o => new FileEntry(
            o["name"]?.GetValue<string>() ?? "",
            o["path"]?.GetValue<string>() ?? "",
            o["dir"]?.GetValue<bool>() ?? false,
            o["size"]?.GetValue<long>() ?? 0,
            DateTimeOffset.FromUnixTimeMilliseconds(o["modified"]?.GetValue<long>() ?? 0))).ToList() ?? new();
    }

    /// <summary>Downloads a file (by path) or a photo (by id) into Downloads\Pixlink. Returns the saved path.</summary>
    public async Task<string?> DownloadAsync(string? path = null, long? photoId = null, string? suggestedName = null)
    {
        var id = Guid.NewGuid().ToString("N")[..12];
        var name = suggestedName ?? Path.GetFileName(path ?? $"photo_{photoId}.jpg");
        var target = UniquePath(Path.Combine(DownloadFolder, name));
        var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        _incoming[id] = (File.Create(target), tcs);

        await _session.SendAsync(Envelope.Of("file.get", new { path, photoId }, id));
        var done = await Task.WhenAny(tcs.Task, Task.Delay(TimeSpan.FromMinutes(10)));
        if (done != tcs.Task && _incoming.TryRemove(id, out var t)) { t.stream.Dispose(); return null; }
        return target;
    }

    /// <summary>Sends a PC file to the phone (saved in Downloads/Pixlink on the phone).</summary>
    public async Task UploadAsync(string filePath, IProgress<double>? progress = null)
    {
        var id = Guid.NewGuid().ToString("N")[..12];
        var info = new FileInfo(filePath);
        await _session.SendAsync(Envelope.Of("file.put", new { transferId = id, name = info.Name, size = info.Length }, id));

        await using var fs = File.OpenRead(filePath);
        var buf = new byte[Chunk];
        long seq = 0, sent = 0;
        while (true)
        {
            var n = await fs.ReadAtLeastAsync(buf, Chunk, throwOnEndOfStream: false);
            sent += n;
            var last = sent >= info.Length;
            await _session.SendAsync(Envelope.Of("file.chunk", new
            {
                transferId = id, seq, last, dataBase64 = Convert.ToBase64String(buf, 0, n),
            }));
            progress?.Report(info.Length == 0 ? 1 : (double)sent / info.Length);
            if (last) break;
            seq++;
        }
    }

    private void OnMessage(Envelope m)
    {
        if (m.T != "file.chunk") return;
        var id = m.Str("transferId");
        if (id is null) return;
        if (!_incoming.TryGetValue(id, out var t))
        {
            // A file the phone sent by itself ("Send to PC" in the share menu).
            if (m.Long("seq") != 0 || m.Str("name") is not { } name) return;
            var path = UniquePath(Path.Combine(DownloadFolder, Path.GetFileName(name)));
            var tcs = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
            tcs.Task.ContinueWith(done => Toasts.ShowSimple($"Received from {_session.PhoneName}", Path.GetFileName(done.Result)),
                TaskContinuationOptions.OnlyOnRanToCompletion);
            t = (File.Create(path), tcs);
            _incoming[id] = t;
        }
        var data = Convert.FromBase64String(m.Str("dataBase64") ?? "");
        t.stream.Write(data);
        if (m.Bool("last") == true)
        {
            var path = t.stream.Name;
            t.stream.Dispose();
            _incoming.TryRemove(id, out _);
            t.done.TrySetResult(path);
        }
    }

    private static string UniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        var dir = Path.GetDirectoryName(path)!;
        var stem = Path.GetFileNameWithoutExtension(path);
        var ext = Path.GetExtension(path);
        for (var i = 1; ; i++)
        {
            var p = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(p)) return p;
        }
    }
}
