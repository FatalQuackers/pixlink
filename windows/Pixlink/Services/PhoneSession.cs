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
    [ObservableProperty] private string? _pairingCode;
    [ObservableProperty] private string? _hotspotSsid;
    [ObservableProperty] private string? _hotspotPassword;
    [ObservableProperty] private bool _hotspotToggleSupported;
    [ObservableProperty] private string _callState = "ended";

    // Now playing on the phone
    [ObservableProperty] private bool _mediaActive;
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(PlayPauseGlyph))] private bool _mediaPlaying;
    [ObservableProperty] private string _mediaTitle = "";
    [ObservableProperty] private string _mediaArtist = "";
    [ObservableProperty] private string _mediaApp = "";
    public string PlayPauseGlyph => MediaPlaying ? "\uE769" : "\uE768";

    private bool _lowBatteryWarned;

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

        var server = new LinkServer(_cert, () => _settings.PhoneFingerprint, () => PairingOpen);
        server.Connected += OnConnected;
        _ = Mirror.RunAsync(_cts.Token);
        _ = Beacon.RunAsync(BeaconPayload, _cts.Token);
        try { await server.RunAsync(_cts.Token); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Server stopped: {ex}"); }
    }

    // ---------- Pairing ----------

    private int _failedCodeAttempts;

    /// <summary>
    /// Opens pairing for 5 minutes: a long one-time token for the QR code, and a 5-digit code
    /// people can type instead. The phone finds this PC through the beacon while pairing is open.
    /// </summary>
    public string BeginPairing()
    {
        _pairToken = Convert.ToBase64String(RandomNumberGenerator.GetBytes(18)).Replace('+', '-').Replace('/', '_');
        _pairExpires = DateTimeOffset.Now.AddMinutes(5);
        _failedCodeAttempts = 0;
        PairingCode = RandomNumberGenerator.GetInt32(0, 100_000).ToString("D5");
        var uri = $"pixlink://pair?v=1&host={LinkServer.LocalAddress()}&port={LinkServer.ControlPort}" +
                  $"&token={_pairToken}&fp=SHA256:{PcFingerprint}&name={Uri.EscapeDataString(PcName)}";
        PairingUri = uri;
        return uri;
    }

    public bool PairingOpen => _pairToken is not null && DateTimeOffset.Now < _pairExpires;

    /// <summary>The name phones see for this PC.</summary>
    public string PcName => string.IsNullOrWhiteSpace(_settings.PcDisplayName) ? Environment.MachineName : _settings.PcDisplayName!;

    /// <summary>What the beacon announces. While pairing is open it also lets phones find us by code.</summary>
    public string BeaconPayload()
    {
        var obj = new JsonObject
        {
            ["pixlink"] = 1,
            ["pcId"] = _settings.PcId,
            ["port"] = LinkServer.ControlPort,
        };
        if (PairingOpen)
        {
            obj["pairing"] = true;
            obj["name"] = PcName;
            obj["fp"] = PcFingerprint;
        }
        return obj.ToJsonString();
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

        // Complete a waiting request, then still handle the message normally (it may update state).
        if (m.Id is not null && m.T != "file.chunk" && _pending.TryRemove(m.Id, out var tcs))
            tcs.TrySetResult(m);

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
                _ = SendAsync(Envelope.Of("media.get"));
                break;
            case "status":
                Battery = m.Int("battery") ?? -1;
                Charging = m.Bool("charging") ?? false;
                DoNotDisturb = m.Bool("dnd") ?? false;
                CheckLowBattery();
                break;
            case "dnd.state": DoNotDisturb = m.Bool("enabled") ?? false; break;
            case "media.state":
                MediaActive = m.Bool("active") ?? false;
                MediaPlaying = m.Bool("playing") ?? false;
                MediaTitle = m.Str("title") ?? "";
                MediaArtist = m.Str("artist") ?? "";
                MediaApp = m.Str("app") ?? "";
                break;
            case "share.text": OnSharedText(m.Str("text") ?? ""); break;
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
            case "power.state": PowerMode = m.Bool("enabled") ?? false; break;
        }
        Message?.Invoke(m);
    }

    private void HandlePairRequest(PhoneConnection conn, Envelope m)
    {
        var token = m.Str("token");
        if (!PairingOpen)
        {
            _ = conn.SendAsync(Envelope.Of("pair.reject", new { reason = "The code expired. Show a new one on your PC." }));
            return;
        }
        var ok = token == _pairToken || (token is { Length: 5 } && token == PairingCode);
        if (!ok)
        {
            // 5 wrong codes and the code changes, so guessing all 100,000 isn't practical.
            if (++_failedCodeAttempts >= 5) BeginPairing();
            _ = conn.SendAsync(Envelope.Of("pair.reject", new { reason = "Wrong code. Check the code on your PC and try again." }));
            return;
        }
        _pairToken = null;
        PairingUri = null;
        PairingCode = null;
        _settings.PhoneFingerprint = conn.PhoneFingerprint;
        _settings.PhoneName = m.Str("phoneName");
        _settings.Save();
        PhoneName = _settings.PhoneName ?? PhoneName;
        JustPaired?.Invoke();
        IsPaired = true;
        _ = conn.SendAsync(Envelope.Of("pair.accept", new { pcName = PcName, pcId = _settings.PcId }));
        // The phone follows up with "hello", which makes this the active connection.
    }

    /// <summary>Raised right before the UI switches from pairing to the main screen (for the success animation).</summary>
    public event Action? JustPaired;

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

    /// <summary>Decodes PNG/JPEG bytes into an image without blocking the UI thread on async I/O.</summary>
    public static BitmapImage ImageFromBytes(byte[] bytes)
    {
        var img = new BitmapImage();
        img.SetSource(new MemoryStream(bytes).AsRandomAccessStream());
        return img;
    }

    public static BitmapImage ImageFromBase64(string b64) => ImageFromBytes(Convert.FromBase64String(b64));

    // ---------- Extras ----------

    public Task RingPhoneAsync(bool ring = true) => SendAsync(Envelope.Of("phone.ring", new { ring }));
    public Task SetDndAsync(bool enabled) => SendAsync(Envelope.Of("dnd.set", new { enabled }));
    public Task MediaActionAsync(string action) => SendAsync(Envelope.Of("media.action", new { action }));

    /// <summary>Sends the text on this PC's clipboard to the phone's clipboard.</summary>
    public async Task<bool> SendClipboardAsync()
    {
        var content = Windows.ApplicationModel.DataTransfer.Clipboard.GetContent();
        if (!content.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.Text)) return false;
        var text = await content.GetTextAsync();
        await SendAsync(Envelope.Of("clipboard.set", new { text }));
        return true;
    }

    /// <summary>Text shared from the phone ("Send to PC") goes on the clipboard.</summary>
    private void OnSharedText(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
        package.SetText(text);
        Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
        Toasts.ShowSimple($"Copied from {PhoneName}", text.Length > 120 ? text[..120] + "…" : text);
    }

    private void CheckLowBattery()
    {
        if (!_settings.LowBatteryAlert || Battery < 0) return;
        if (Charging || Battery > _settings.LowBatteryLevel + 5) { _lowBatteryWarned = false; return; }
        if (Battery <= _settings.LowBatteryLevel && !_lowBatteryWarned)
        {
            _lowBatteryWarned = true;
            Toasts.ShowSimple($"{PhoneName} battery is low", $"{Battery}% left. Time to plug it in.");
        }
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

    public enum HotspotOutcome { Joined, PhoneSwitchedOnJoinFailed, TurnOnOnPhone, NoReply, TurnedOff }

    /// <summary>The PC asks once for the hotspot name and password; the phone keeps them.</summary>
    public async Task SaveHotspotConfigAsync(string ssid, string password)
    {
        HotspotSsid = ssid;
        HotspotPassword = password;
        await SendAsync(Envelope.Of("hotspot.config", new { ssid, password }));
    }

    /// <summary>
    /// One button: ask the phone to turn the hotspot on (it switches it itself in Power mode,
    /// otherwise it opens the hotspot setting), then join it from Windows.
    /// </summary>
    public async Task<HotspotOutcome> SetHotspotAsync(bool enabled, IProgress<string>? progress = null)
    {
        progress?.Report(enabled ? "Turning on your phone's hotspot…" : "Turning off hotspot…");
        var reply = await RequestAsync("hotspot.set", new { enabled }, TimeSpan.FromSeconds(20));
        if (!enabled) return HotspotOutcome.TurnedOff;
        if (reply is null) return HotspotOutcome.NoReply;

        var switched = reply.Bool("switched") == true;
        if (!switched)
        {
            // The phone opened its hotspot setting; give the user time to flip it, then try to join.
            progress?.Report("Turn on the hotspot on your phone. Pixlink will connect when it appears…");
        }
        else
        {
            progress?.Report($"Hotspot is on. Connecting to {HotspotSsid}…");
        }

        if (HotspotSsid is null) return switched ? HotspotOutcome.PhoneSwitchedOnJoinFailed : HotspotOutcome.TurnOnOnPhone;
        var joined = await HotspotJoiner.JoinAsync(HotspotSsid, HotspotPassword, attempts: switched ? 8 : 20);
        if (joined) return HotspotOutcome.Joined;
        return switched ? HotspotOutcome.PhoneSwitchedOnJoinFailed : HotspotOutcome.TurnOnOnPhone;
    }
}
