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
    /// <summary>The paired phone is an iPhone: no notifications, texts, calls or control (Apple doesn't allow them).</summary>
    [ObservableProperty] private bool _isIPhone;
    /// <summary>The phone accepts taps and typing: Power mode, or Pixlink's accessibility service.</summary>
    [ObservableProperty] private bool _canControl;
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

    /// <summary>Notifications grouped by app, for the "Group by app" setting.</summary>
    public ObservableCollection<NotificationGroup> NotificationGroups { get; } = new();
    private readonly Dictionary<string, bool> _groupOpen = new();
    private bool _groupsHooked;

    public void RebuildGroups()
    {
        if (!_groupsHooked) { _groupsHooked = true; Notifications.CollectionChanged += (_, _) => RebuildGroups(); }
        NotificationGroups.Clear();
        foreach (var g in Notifications.GroupBy(n => string.IsNullOrEmpty(n.AppName) ? n.App : n.AppName))
        {
            var group = new NotificationGroup(g.Key, g.First().Icon, g.ToList()) { IsExpanded = _groupOpen.GetValueOrDefault(g.Key, true) };
            group.PropertyChanged += (_, e) => { if (e.PropertyName == nameof(NotificationGroup.IsExpanded)) _groupOpen[group.AppName] = group.IsExpanded; };
            NotificationGroups.Add(group);
        }
    }
    public ObservableCollection<Conversation> Conversations { get; } = new();

    // ---------- Connection status, readable at a glance ----------
    public enum LinkPhase { NotPaired, Connecting, Connected, Reconnecting, Disconnected }
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(StatusText), nameof(StatusBrush), nameof(StatusBusy))] private LinkPhase _phase = LinkPhase.Connecting;
    public string StatusText => Phase switch
    {
        LinkPhase.NotPaired => "Not paired",
        LinkPhase.Connecting => "Connecting…",
        LinkPhase.Connected => OnBluetooth ? "Connected · Bluetooth" : "Connected",
        LinkPhase.Reconnecting => "Reconnecting…",
        _ => "Disconnected",
    };
    public Microsoft.UI.Xaml.Media.SolidColorBrush StatusBrush => new(Phase switch
    {
        LinkPhase.Connected => Windows.UI.Color.FromArgb(255, 0x1E, 0x9E, 0x63),
        LinkPhase.Connecting or LinkPhase.Reconnecting => Windows.UI.Color.FromArgb(255, 0xE8, 0x9B, 0x1A),
        _ => Windows.UI.Color.FromArgb(255, 0xD1, 0x3B, 0x40),
    });
    public bool StatusBusy => Phase is LinkPhase.Connecting or LinkPhase.Reconnecting;
    /// <summary>Raised when the phone comes back after a drop (not on the first connection).</summary>
    public event Action? Reconnected;
    private bool _everConnected;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _phaseTimer;

    partial void OnIsConnectedChanged(bool value) => UpdatePhase();
    partial void OnIsPairedChanged(bool value) => UpdatePhase();

    private void UpdatePhase()
    {
        _phaseTimer?.Stop();
        if (!IsPaired) { Phase = LinkPhase.NotPaired; return; }
        if (IsConnected)
        {
            if (_everConnected && Phase != LinkPhase.Connected) Reconnected?.Invoke();
            _everConnected = true;
            Phase = LinkPhase.Connected;
            return;
        }
        // Give the phone a moment to find us before calling it disconnected.
        Phase = _everConnected ? LinkPhase.Reconnecting : LinkPhase.Connecting;
        if (_ui is null) return;
        _phaseTimer = _ui.CreateTimer();
        _phaseTimer.Interval = TimeSpan.FromSeconds(_everConnected ? 30 : 20);
        _phaseTimer.IsRepeating = false;
        _phaseTimer.Tick += (_, _) => { if (!IsConnected && IsPaired) Phase = LinkPhase.Disconnected; };
        _phaseTimer.Start();
    }

    // Notifications already on the phone when it connects are listed, not popped up again.
    private readonly HashSet<string> _seenKeys = new();
    private DateTimeOffset _connectedAt;
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
        IsIPhone = _settings.PhonePlatform == "ios";
        UpdatePhase();
        RebuildQuickActions();
        StartClipboardSync();
        Log.Info("Pixlink started" + (AppSettings.IsPortable ? " (portable)" : ""));

        var server = new LinkServer(_cert, () => _settings.PhoneFingerprint, () => PairingOpen);
        server.Connected += OnConnected;
        _bluetooth = new BluetoothLinkServer(server);
        if (_settings.AllowBluetooth) _ = _bluetooth.StartAsync(_cts.Token);
        _ = Mirror.RunAsync(_cts.Token);
        _ = Beacon.RunAsync(BeaconPayload, _cts.Token);
        _ = Bonjour.StartAsync(_settings.PcId);
        try { await server.RunAsync(_cts.Token); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Server stopped: {ex}"); }
    }

    // ---------- Bluetooth ----------
    private BluetoothLinkServer? _bluetooth;

    /// <summary>"Wi-Fi" or "Bluetooth": how the phone is connected right now.</summary>
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(StatusText), nameof(OnBluetooth))] private string _connectedVia = "Wi-Fi";
    public bool OnBluetooth => ConnectedVia == "Bluetooth";

    public async Task SetAllowBluetoothAsync(bool on)
    {
        _settings.AllowBluetooth = on;
        _settings.Save();
        if (on) await _bluetooth!.StartAsync(_cts.Token); else _bluetooth?.Dispose();
    }

    /// <summary>Tells the phone this PC's Bluetooth address, so it can fall back to Bluetooth later.</summary>
    private async Task SendPcInfoAsync()
    {
        var bt = _settings.AllowBluetooth ? await BluetoothLinkServer.AddressAsync() : null;
        await SendAsync(Envelope.Of("pc.info", new { bt, bluetooth = _settings.AllowBluetooth && bt is not null }));
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
            Log.Info("Phone disconnected");
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
                IsIPhone = (m.Str("platform") ?? "android") == "ios";
                if (_settings.PhonePlatform != (IsIPhone ? "ios" : "android")) { _settings.PhonePlatform = IsIPhone ? "ios" : "android"; _settings.Save(); }
                PowerMode = m.Bool("powerMode") ?? false;
                CanControl = m.Bool("canControl") ?? PowerMode;
                ConnectedVia = conn.Via == "bluetooth" ? "Bluetooth" : "Wi-Fi";
                _connectedAt = DateTimeOffset.Now;
                IsConnected = true;
                Log.Info($"Connected to {PhoneName} over {ConnectedVia}");
                _ = SendPcInfoAsync();
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
            case "clipboard.text": OnSharedText(m.Str("text") ?? "", quiet: m.Bool("auto") ?? false); break;
            case "clipboard.code": OnCode(m.Str("code") ?? "", m.Str("app") ?? "your phone"); break;
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
            case "power.state": PowerMode = m.Bool("enabled") ?? false; CanControl = m.Bool("canControl") ?? PowerMode; break;
            case "control.state": CanControl = m.Bool("canControl") ?? CanControl; break;
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
        _settings.PhoneName = m.Str("phoneName");
        _settings.PhonePlatform = m.Str("platform") ?? "android";
        if (m.Bool("needsIdentity") == true || string.IsNullOrEmpty(conn.PhoneFingerprint))
        {
            // iPhone: issue it a certificate. It reconnects with it and says hello.
            var (fp, p12, password) = Certificates.CreatePhoneIdentity(_settings.PhoneName ?? "iPhone");
            _settings.PhoneFingerprint = fp;
            _settings.Save();
            _ = conn.SendAsync(Envelope.Of("pair.accept", new { pcName = PcName, pcId = _settings.PcId, identityP12 = p12, identityPassword = password }));
        }
        else
        {
            _settings.PhoneFingerprint = conn.PhoneFingerprint;
            _settings.Save();
            _ = conn.SendAsync(Envelope.Of("pair.accept", new { pcName = PcName, pcId = _settings.PcId }));
        }
        PhoneName = _settings.PhoneName ?? PhoneName;
        IsIPhone = _settings.PhonePlatform == "ios";
        JustPaired?.Invoke();
        IsPaired = true;
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
        var key = m.Str("key")!;
        var n = new PhoneNotification
        {
            Key = key,
            App = m.Str("app") ?? "",
            AppName = m.Str("appName") ?? "",
            Title = m.Str("title") ?? "",
            Text = m.Str("text") ?? "",
            Time = DateTimeOffset.FromUnixTimeMilliseconds(m.Long("time") ?? 0),
            CanReply = m.Bool("canReply") ?? false,
            IsMessage = m.Bool("isMessage") ?? false,
            Conversation = m.Str("conversation"),
            Actions = ParseActions(key, m.Arr("actions")),
            Icon = _icons.GetValueOrDefault(m.Str("app") ?? ""),
        };
        foreach (var h in m.Arr("history")?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
        {
            var time = DateTimeOffset.FromUnixTimeMilliseconds(h["time"]?.GetValue<long>() ?? 0).LocalDateTime.ToString("t");
            n.History.Add(new ChatBubble(h["text"]?.GetValue<string>() ?? "", h["fromMe"]?.GetValue<bool>() ?? false, time,
                h["sender"]?.GetValue<string>() ?? ""));
        }

        var existing = Notifications.FirstOrDefault(x => x.Key == n.Key);
        if (existing is not null)
        {
            n.IsExpanded = existing.IsExpanded; // an update shouldn't collapse a card you opened
            n.ReplyDraft = existing.ReplyDraft;
            Notifications.Remove(existing);
        }
        Notifications.Insert(0, n);

        if (n.IsMessage) AddToConversation(n);
        // Pop up only what's new: not the backlog synced on (re)connect, and never the same one twice.
        var isNew = _seenKeys.Add(n.Key) && existing is null && n.Time >= _connectedAt.AddSeconds(-10);
        if (isNew && _settings.ShowToasts) Toasts.Show(n);

    }

    /// <summary>Accepts the new format ([{title, semantic}]) and the old one (["title"]).</summary>
    private static List<NotifAction> ParseActions(string key, JsonArray? arr)
    {
        var list = new List<NotifAction>();
        if (arr is null) return list;
        for (var i = 0; i < arr.Count; i++)
        {
            switch (arr[i])
            {
                case JsonObject o:
                    list.Add(new NotifAction(key, i, o["title"]?.GetValue<string>() ?? "", o["semantic"]?.GetValue<string>() ?? ""));
                    break;
                case JsonValue v when v.TryGetValue<string>(out var title):
                    list.Add(new NotifAction(key, i, title, ""));
                    break;
            }
        }
        return list;
    }

    private void AddToConversation(PhoneNotification n)
    {
        var convo = Conversations.FirstOrDefault(c => c.Name == n.ThreadName && c.App == n.App);
        if (convo is null)
        {
            convo = new Conversation { Name = n.ThreadName, App = n.App, AppName = n.AppName, Icon = n.Icon };
            convo.IsPinned = _settings.PinnedConversations.Contains(convo.Id);
            Conversations.Add(convo);
        }
        // A new message brings back a conversation you removed.
        if (_settings.HiddenConversations.Remove(convo.Id)) _settings.Save();

        if (n.History.Count > 1)
        {
            // The app sent the recent thread: show exactly that.
            convo.Messages.Clear();
            foreach (var b in n.History) convo.Messages.Add(b);
        }
        else if (convo.Messages.LastOrDefault()?.Text != n.Text)
        {
            convo.Messages.Add(new ChatBubble(n.Text, false, n.TimeText));
        }
        convo.Preview = n.Text;
        convo.Time = n.TimeText;
        convo.LatestKey = n.Key;
        convo.LatestActions = n.Actions;
        if (n.CanReply) convo.ReplyKey = n.Key;
        SortConversations(convo);
    }

    /// <summary>Pinned conversations stay at the top; the rest follow newest first.</summary>
    private void SortConversations(Conversation? justUpdated = null)
    {
        if (justUpdated is not null)
        {
            var target = justUpdated.IsPinned ? 0 : Conversations.Count(c => c.IsPinned && c != justUpdated);
            var from = Conversations.IndexOf(justUpdated);
            if (from >= 0 && from != target) Conversations.Move(from, Math.Min(target, Conversations.Count - 1));
        }
    }

    public void SetPinned(Conversation convo, bool pinned)
    {
        convo.IsPinned = pinned;
        if (pinned) _settings.PinnedConversations.Add(convo.Id); else _settings.PinnedConversations.Remove(convo.Id);
        _settings.Save();
        SortConversations(convo);
    }

    /// <summary>Removes a conversation from the list (it comes back if a new message arrives).</summary>
    public void RemoveConversation(Conversation convo)
    {
        _settings.HiddenConversations.Add(convo.Id);
        _settings.PinnedConversations.Remove(convo.Id);
        _settings.Save();
        Conversations.Remove(convo);
    }

    /// <summary>Presses one of the app's buttons (Mark as read…) on the phone.</summary>
    public Task ActionAsync(NotifAction a) =>
        SendAsync(Envelope.Of("notif.action", new { key = a.Key, actionIndex = a.Index }));

    public Task ActionAsync(string key, int index) =>
        SendAsync(Envelope.Of("notif.action", new { key, actionIndex = index }));

    // ---------- Texts (SMS history straight from the phone; no Google Messages sign-in) ----------
    public ObservableCollection<Conversation> SmsThreads { get; } = new();
    public string? SmsError { get; private set; }

    public async Task LoadSmsThreadsAsync()
    {
        var r = await RequestAsync("sms.threads", new { limit = 80 }, TimeSpan.FromSeconds(15));
        SmsError = r?.Str("error");
        if (r is null || SmsError is not null) return;
        SmsThreads.Clear();
        foreach (var t in r.Arr("threads")?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>())
        {
            var date = DateTimeOffset.FromUnixTimeMilliseconds(t["date"]?.GetValue<long>() ?? 0).LocalDateTime;
            SmsThreads.Add(new Conversation
            {
                Name = t["name"]?.GetValue<string>() ?? t["address"]?.GetValue<string>() ?? "Unknown",
                App = "sms", AppName = "Texts",
                ThreadId = t["threadId"]?.GetValue<long>(),
                Address = t["address"]?.GetValue<string>(),
                Preview = t["snippet"]?.GetValue<string>() ?? "",
                Time = date.Date == DateTime.Today ? date.ToString("t") : date.ToString("d MMM"),
            });
        }
    }

    public async Task LoadSmsMessagesAsync(Conversation c)
    {
        if (c.ThreadId is null) return;
        var r = await RequestAsync("sms.thread", new { threadId = c.ThreadId, limit = 150 }, TimeSpan.FromSeconds(15));
        if (r is null) return;
        c.Messages.Clear();
        foreach (var m in (r.Arr("messages")?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>()).Reverse())
        {
            var date = DateTimeOffset.FromUnixTimeMilliseconds(m["date"]?.GetValue<long>() ?? 0).LocalDateTime;
            c.Messages.Add(new ChatBubble(m["body"]?.GetValue<string>() ?? "", m["fromMe"]?.GetValue<bool>() ?? false,
                date.Date == DateTime.Today ? date.ToString("t") : date.ToString("d MMM, t")));
        }
    }

    public async Task<bool> SendSmsAsync(Conversation c, string text)
    {
        if (c.Address is null || string.IsNullOrWhiteSpace(text)) return false;
        var r = await RequestAsync("sms.send", new { address = c.Address, text }, TimeSpan.FromSeconds(15));
        if (r?.Bool("ok") != true) return false;
        c.Messages.Add(new ChatBubble(text, true, DateTime.Now.ToString("t")));
        c.Preview = $"You: {text}";
        return true;
    }

    public async Task ReplyAsync(Conversation convo, string text)
    {
        if (convo.ThreadId is not null) { await SendSmsAsync(convo, text); return; }
        if (convo.ReplyKey is null || string.IsNullOrWhiteSpace(text)) return;
        await SendAsync(Envelope.Of("notif.reply", new { key = convo.ReplyKey, text }));
        convo.Messages.Add(new ChatBubble(text, true, DateTime.Now.ToString("t")));
        convo.Preview = $"You: {text}";
    }

    /// <summary>Reply from an expanded notification card.</summary>
    public async Task ReplyAsync(PhoneNotification n)
    {
        var text = n.ReplyDraft.Trim();
        if (text.Length == 0 || !n.CanReply) return;
        n.ReplyDraft = "";
        await ReplyToKeyAsync(n.Key, text);
        if (n.History.Count == 0) n.History.Add(new ChatBubble(n.Text, false, n.TimeText, n.Title)); // keep their message above yours
        n.History.Add(new ChatBubble(text, true, DateTime.Now.ToString("t")));
        var convo = Conversations.FirstOrDefault(c => c.Name == n.ThreadName && c.App == n.App);
        if (convo is not null) { convo.Messages.Add(new ChatBubble(text, true, DateTime.Now.ToString("t"))); convo.Preview = $"You: {text}"; }
    }

    public Task ReplyToKeyAsync(string key, string text) => SendAsync(Envelope.Of("notif.reply", new { key, text }));
    public Task DismissAsync(PhoneNotification n) => SendAsync(Envelope.Of("notif.dismiss", new { key = n.Key }));
    public Task DismissAsync(string key) => SendAsync(Envelope.Of("notif.dismiss", new { key }));
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
        _lastClipboard = text;
        await SendAsync(Envelope.Of("clipboard.set", new { text }));
        return true;
    }

    /// <summary>Text shared or copied on the phone goes on this PC's clipboard.</summary>
    private void OnSharedText(string text, bool quiet = false)
    {
        if (string.IsNullOrEmpty(text)) return;
        PutOnClipboard(text);
        if (!quiet) Toasts.ShowSimple($"Copied from {PhoneName}", text.Length > 120 ? text[..120] + "…" : text);
    }

    /// <summary>A sign-in code arrived on the phone: it's ready to paste here.</summary>
    private void OnCode(string code, string app)
    {
        if (!_settings.CopyCodes || string.IsNullOrEmpty(code)) return;
        PutOnClipboard(code);
        Toasts.ShowSimple($"Code {code} copied", $"From {app} on {PhoneName}. Press Ctrl+V to paste it.");
    }

    // ---------- Automatic clipboard sync ----------
    private string? _lastClipboard;
    private bool _clipboardHooked;

    private void PutOnClipboard(string text)
    {
        _lastClipboard = text; // so our own change isn't sent straight back
        var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
        package.SetText(text);
        Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
    }

    /// <summary>Copy on this PC, paste on the phone: sends new copied text when sync is on.</summary>
    public void StartClipboardSync()
    {
        if (_clipboardHooked) return;
        _clipboardHooked = true;
        Windows.ApplicationModel.DataTransfer.Clipboard.ContentChanged += async (_, _) =>
        {
            if (!_settings.AutoClipboard || !IsConnected) return;
            try
            {
                var content = Windows.ApplicationModel.DataTransfer.Clipboard.GetContent();
                if (!content.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.Text)) return;
                var text = await content.GetTextAsync();
                if (string.IsNullOrEmpty(text) || text == _lastClipboard || text.Length > 100_000) return;
                _lastClipboard = text;
                await SendAsync(Envelope.Of("clipboard.set", new { text, auto = true }));
            }
            catch (Exception) { /* clipboard busy: skip this change */ }
        };
    }

    // ---------- Pinned to Home ----------
    public ObservableCollection<PhoneNotification> PinnedNotifications { get; } = new();

    public void TogglePin(PhoneNotification n)
    {
        var existing = PinnedNotifications.FirstOrDefault(x => x.Key == n.Key);
        if (existing is not null) PinnedNotifications.Remove(existing); else PinnedNotifications.Insert(0, n);
    }

    /// <summary>Opens the notification's app on the phone (what tapping it on the phone would do).</summary>
    public Task OpenNotificationAsync(string key) => SendAsync(Envelope.Of("notif.open", new { key }));

    // ---------- Quick actions ----------
    public ObservableCollection<QuickActionItem> QuickActions { get; } = new();

    public void RebuildQuickActions()
    {
        QuickActions.Clear();
        foreach (var id in _settings.QuickActions)
        {
            var c = QuickActionItem.Catalog.FirstOrDefault(x => x.Id == id);
            if (c.Id is null) continue;
            QuickActions.Add(new QuickActionItem { Id = c.Id, Glyph = c.Glyph, Name = c.Name, Tip = c.Tip, NeedsPower = c.NeedsPower, Label = c.Name,
                IsActive = c.Id == "dnd" && DoNotDisturb });
        }
    }

    public QuickActionItem? QuickAction(string id) => QuickActions.FirstOrDefault(q => q.Id == id);

    partial void OnDoNotDisturbChanged(bool value) { if (QuickAction("dnd") is { } q) q.IsActive = value; }

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
            progress?.Report(reply.Bool("openedSettings") == true
                ? "On your phone, tap the Pixlink notification \"Your PC wants to use your hotspot\" and switch Hotspot on. This PC connects as soon as it appears…"
                : "Turn on the hotspot on your phone. This PC connects as soon as it appears…");
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
