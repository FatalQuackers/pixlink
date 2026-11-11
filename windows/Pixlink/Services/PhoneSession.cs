using System.Collections.Concurrent;
using System.Collections.ObjectModel;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml.Media.Imaging;
using Pixlink.Models;

namespace Pixlink.Services;

/// <summary>
/// Everything about the paired phone: connection, pairing, live state and requests.
/// UI binds to this. All property changes happen on the UI thread.
/// </summary>
public partial class PhoneSession : ObservableObject
{
    private readonly AppSettings _settings = AppSettings.Load();
    private readonly System.Security.Cryptography.X509Certificates.X509Certificate2 _cert = Certificates.LoadOrCreate();
    private readonly ConcurrentDictionary<string, TaskCompletionSource<Envelope>> _pending = new();
    private readonly Dictionary<string, BitmapImage> _icons = new();
    private DispatcherQueue _ui = null!;
    private PhoneConnection? _conn;
    private readonly CancellationTokenSource _cts = new();
    private PeriodicTimer? _pingTimer;

    // Pairing
    private string? _pairToken;
    private DateTimeOffset _pairExpires;

    public MirrorServer Mirror { get; }
    public FileTransfers Files { get; }

    public PhoneSession()
    {
        Mirror = new MirrorServer(_cert, () => _settings.PhoneFingerprint);
        Files = new FileTransfers(this);
    }

    [ObservableProperty] [NotifyPropertyChangedFor(nameof(StatusText))] private bool _isPaired;
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(StatusText))] private bool _isConnected;
    [ObservableProperty] private string _phoneName = "Your Pixel";
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(BatteryText), nameof(BatteryGlyph))] private int _battery = -1;
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(BatteryGlyph))] private bool _charging;
    [ObservableProperty] private bool _doNotDisturb;
    [ObservableProperty] private bool _powerMode;
    [ObservableProperty] private string? _pairingUri;
    [ObservableProperty] private string? _hotspotSsid;
    [ObservableProperty] private string? _hotspotPassword;
    [ObservableProperty] private bool _hotspotToggleSupported;
    [ObservableProperty] private string _callState = "ended";

    public ObservableCollection<PhoneNotification> Notifications { get; } = new();
    public ObservableCollection<Conversation> Conversations { get; } = new();

    public string StatusText => !IsPaired ? "Not paired" : IsConnected ? "Connected" : "Not connected";
    public string BatteryText => Battery < 0 ? "" : $"{Battery}%";
    public string BatteryGlyph => Charging ? "" : Battery switch
    {
        < 0 => "",
        < 10 => "", < 30 => "", < 50 => "", < 70 => "", < 90 => "", _ => "",
    };

    public AppSettings Settings => _settings;
    public string PcFingerprint => Certificates.Fingerprint(_cert);

    public event Action<Envelope>? Message;

    public async Task StartAsync(DispatcherQueue ui)
    {
        _ui = ui;
        IsPaired = _settings.PhoneFingerprint is not null;
        if (_settings.PhoneName is { } n) PhoneName = n;

        var server = new LinkServer(_cert, () => _settings.PhoneFingerprint, () => _pairToken is not null && DateTimeOffset.Now < _pairExpires);
        server.Connected += OnConnected;
        _ = Mirror.RunAsync(_cts.Token);
        try { await server.RunAsync(_cts.Token); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Server stopped: {ex}"); }
    }

    // ---------- Pairing ----------

    /// <summary>Creates a fresh one-time token and the pixlink:// URI shown as a QR code.</summary>
    public string BeginPairing()
    {
        _pairToken = Convert.ToBase64String(RandomNumberGenerator.GetBytes(18)).Replace('+', '-').Replace('/', '_');
        _pairExpires = DateTimeOffset.Now.AddMinutes(5);
        var uri = $"pixlink://pair?v=1&host={LinkServer.LocalAddress()}&port={LinkServer.ControlPort}" +
                  $"&token={_pairToken}&fp=SHA256:{PcFingerprint}&name={Uri.EscapeDataString(Environment.MachineName)}";
        PairingUri = uri;
        return uri;
    }

    public void Unpair()
    {
        _settings.PhoneFingerprint = null;
        _settings.PhoneName = null;
        _settings.Save();
        _conn?.Dispose();
        IsPaired = false;
        IsConnected = false;
        Notifications.Clear();
        Conversations.Clear();
    }

    // ---------- Connection ----------

    private void OnConnected(PhoneConnection conn)
    {
        conn.MessageReceived += env => _ui.TryEnqueue(() => Handle(conn, env));
        conn.Closed += () => _ui.TryEnqueue(() =>
        {
            if (_conn != conn) return;
            _conn = null;
            IsConnected = false;
            _pingTimer?.Dispose();
        });
    }

    private void Handle(PhoneConnection conn, Envelope m)
    {
        // Until a connection says "hello" as the paired phone, it may only pair.
        if (conn != _conn && m.T is not ("pair.request" or "hello")) return;

        if (m.Id is not null && _pending.TryRemove(m.Id, out var tcs) && m.T != "file.chunk")
        {
            tcs.TrySetResult(m);
            return;
        }

        switch (m.T)
        {
            case "pair.request": HandlePairRequest(conn, m); break;
            case "hello":
                if (conn.PhoneFingerprint != _settings.PhoneFingerprint) { conn.Dispose(); return; }
                _conn?.Dispose();
                _conn = conn;
                PhoneName = m.Str("phoneName") ?? PhoneName;
                PowerMode = m.Bool("powerMode") ?? false;
                IsConnected = true;
                StartPing();
                _ = SendAsync(Envelope.Of("notif.sync"));
                _ = SendAsync(Envelope.Of("hotspot.get"));
                break;
            case "status":
                Battery = m.Int("battery") ?? -1;
                Charging = m.Bool("charging") ?? false;
                DoNotDisturb = m.Bool("dnd") ?? false;
                break;
            case "notif.posted": OnNotification(m); break;
            case "notif.removed":
                var key = m.Str("key");
                var n = Notifications.FirstOrDefault(x => x.Key == key);
                if (n is not null) Notifications.Remove(n);
                break;
            case "app.icon": OnAppIcon(m); break;
            case "hotspot.state":
                HotspotSsid = m.Str("ssid");
                HotspotPassword = m.Str("password");
                HotspotToggleSupported = m.Bool("toggleSupported") ?? false;
                break;
            case "call.state": CallState = m.Str("state") ?? "ended"; break;
        }
        Message?.Invoke(m);
    }

    private void HandlePairRequest(PhoneConnection conn, Envelope m)
    {
        var ok = _pairToken is not null && DateTimeOffset.Now < _pairExpires && m.Str("token") == _pairToken;
        if (!ok)
        {
            _ = conn.SendAsync(Envelope.Of("pair.reject", new { reason = "Code expired — show a new QR code" }));
            return;
        }
        _pairToken = null;
        PairingUri = null;
        _settings.PhoneFingerprint = conn.PhoneFingerprint;
        _settings.PhoneName = m.Str("phoneName");
        _settings.Save();
        PhoneName = _settings.PhoneName ?? PhoneName;
        IsPaired = true;
        _ = conn.SendAsync(Envelope.Of("pair.accept", new { pcName = Environment.MachineName, pcId = _settings.PcId }));
        // The phone follows up with "hello", which makes this the active connection.
    }

    private void StartPing()
    {
        _pingTimer?.Dispose();
        _pingTimer = new PeriodicTimer(TimeSpan.FromSeconds(15));
        var timer = _pingTimer;
        _ = Task.Run(async () =>
        {
            while (await timer.WaitForNextTickAsync()) await SendAsync(Envelope.Of("ping"));
        });
    }

    // ---------- Sending ----------

    public Task SendAsync(Envelope env) => _conn?.SendAsync(env) ?? Task.CompletedTask;

    /// <summary>Sends a request and waits for the reply with the same id.</summary>
    public async Task<Envelope?> RequestAsync(string type, object? data = null, TimeSpan? timeout = null)
    {
        if (_conn is null) return null;
        var id = Guid.NewGuid().ToString("N")[..12];
        var tcs = new TaskCompletionSource<Envelope>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[id] = tcs;
        await SendAsync(Envelope.Of(type, data, id));
        var done = await Task.WhenAny(tcs.Task, Task.Delay(timeout ?? TimeSpan.FromSeconds(15)));
        _pending.TryRemove(id, out _);
        return done == tcs.Task ? tcs.Task.Result : null;
    }

    // ---------- Notifications & messages ----------

    private void OnNotification(Envelope m)
    {
        var n = new PhoneNotification
        {
            Key = m.Str("key")!,
            App = m.Str("app") ?? "",
            AppName = m.Str("appName") ?? "",
            Title = m.Str("title") ?? "",
            Text = m.Str("text") ?? "",
            Time = DateTimeOffset.FromUnixTimeMilliseconds(m.Long("time") ?? 0),
            CanReply = m.Bool("canReply") ?? false,
            IsMessage = m.Bool("isMessage") ?? false,
            Conversation = m.Str("conversation"),
            Actions = m.Arr("actions")?.Select(a => a?.GetValue<string>() ?? "").ToList() ?? new(),
            Icon = _icons.GetValueOrDefault(m.Str("app") ?? ""),
        };

        var existing = Notifications.FirstOrDefault(x => x.Key == n.Key);
        if (existing is not null) Notifications.Remove(existing);
        Notifications.Insert(0, n);

        if (n.IsMessage) AddToConversation(n);
        if (existing is null && _settings.ShowToasts) Toasts.Show(n);
    }

    private void AddToConversation(PhoneNotification n)
    {
        var convo = Conversations.FirstOrDefault(c => c.Name == n.ThreadName && c.App == n.App);
        if (convo is null)
        {
            convo = new Conversation { Name = n.ThreadName, App = n.App, Icon = n.Icon };
            Conversations.Insert(0, convo);
        }
        else
        {
            Conversations.Move(Conversations.IndexOf(convo), 0);
        }
        if (convo.Messages.LastOrDefault()?.Text != n.Text) convo.Messages.Add(new ChatBubble(n.Text, false, n.TimeText));
        convo.Preview = n.Text;
        convo.Time = n.TimeText;
        if (n.CanReply) convo.ReplyKey = n.Key;
    }

    public async Task ReplyAsync(Conversation convo, string text)
    {
        if (convo.ReplyKey is null || string.IsNullOrWhiteSpace(text)) return;
        await SendAsync(Envelope.Of("notif.reply", new { key = convo.ReplyKey, text }));
        convo.Messages.Add(new ChatBubble(text, true, DateTime.Now.ToString("t")));
        convo.Preview = $"You: {text}";
    }

    public Task ReplyToKeyAsync(string key, string text) => SendAsync(Envelope.Of("notif.reply", new { key, text }));
    public Task DismissAsync(PhoneNotification n) => SendAsync(Envelope.Of("notif.dismiss", new { key = n.Key }));
    public Task DismissAllAsync() => Task.WhenAll(Notifications.ToList().Select(DismissAsync));

    private void OnAppIcon(Envelope m)
    {
        var app = m.Str("app");
        var b64 = m.Str("pngBase64");
        if (app is null || b64 is null) return;
        var img = ImageFromBase64(b64);
        _icons[app] = img;
        foreach (var n in Notifications.Where(n => n.App == app)) n.Icon = img;
        foreach (var c in Conversations.Where(c => c.App == app)) c.Icon = img;
    }

    public static BitmapImage ImageFromBase64(string b64)
    {
        var img = new BitmapImage();
        var stream = new Windows.Storage.Streams.InMemoryRandomAccessStream();
        using (var writer = new Windows.Storage.Streams.DataWriter(stream.GetOutputStreamAt(0)))
        {
            writer.WriteBytes(Convert.FromBase64String(b64));
            writer.StoreAsync().AsTask().Wait();
        }
        stream.Seek(0);
        img.SetSource(stream);
        return img;
    }

    // ---------- Calls ----------

    public Task DialAsync(string number) => SendAsync(Envelope.Of("call.dial", new { number }));
    public Task AnswerAsync() => SendAsync(Envelope.Of("call.answer"));
    public Task HangUpAsync() => SendAsync(Envelope.Of("call.hangup"));

    public async Task<List<Contact>> GetContactsAsync(string? query = null)
    {
        var r = await RequestAsync("contacts.list", new { query });
        return r?.Arr("contacts")?.OfType<JsonObject>().Select(o => new Contact(
            o["name"]?.GetValue<string>() ?? "",
            o["numbers"]?.AsArray().Select(x => x?.GetValue<string>() ?? "").ToList() ?? new())).ToList() ?? new();
    }

    /// <summary>Returns null when Power mode is needed.</summary>
    public async Task<List<CallEntry>?> GetCallLogAsync(int limit = 100)
    {
        var r = await RequestAsync("calllog.list", new { limit });
        if (r is null || r.Str("error") == "power_mode_required") return null;
        return r.Arr("calls")?.OfType<JsonObject>().Select(o => new CallEntry(
            o["number"]?.GetValue<string>() ?? "",
            o["name"]?.GetValue<string>(),
            o["type"]?.GetValue<string>() ?? "",
            DateTimeOffset.FromUnixTimeMilliseconds(o["date"]?.GetValue<long>() ?? 0),
            o["duration"]?.GetValue<int>() ?? 0)).ToList() ?? new();
    }

    // ---------- Hotspot ----------

    public async Task SetHotspotAsync(bool enabled)
    {
        await SendAsync(Envelope.Of("hotspot.set", new { enabled }));
        if (enabled && _settings.AutoJoinHotspot && HotspotSsid is not null)
        {
            // Give the phone a few seconds to bring the hotspot up, then join.
            await Task.Delay(TimeSpan.FromSeconds(6));
            await HotspotJoiner.JoinAsync(HotspotSsid, HotspotPassword);
        }
    }
}
