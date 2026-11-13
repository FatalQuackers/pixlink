using System.Runtime.InteropServices.WindowsRuntime;
using System.Text;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace Pixlink.Services;

/// <summary>
/// iPhone notifications over Bluetooth, the way Phone Link does it.
///
/// The PC publishes a small Bluetooth LE service. Pixlink on the iPhone connects to it and reads a
/// value that needs an encrypted link, which makes the iPhone and PC pair. Once paired, the PC can
/// use Apple's Notification Center Service (ANCS) on the iPhone: every notification's app, title
/// and text, plus its buttons (Answer/Decline for calls, Clear for the rest). The iPhone keeps the
/// Bluetooth link up even when Pixlink isn't open, so notifications keep coming.
/// </summary>
public sealed class IPhoneBluetooth
{
    public static readonly Guid ServiceUuid = Guid.Parse("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f61");
    private static readonly Guid PcIdUuid = Guid.Parse("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f62");
    private static readonly Guid StatusUuid = Guid.Parse("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f63");
    private static readonly Guid KeepAliveUuid = Guid.Parse("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f64");

    private static readonly Guid AncsUuid = Guid.Parse("7905F431-B5CE-4E99-A40F-4B1E122D00D0");
    private static readonly Guid NotificationSourceUuid = Guid.Parse("9FBF120D-6301-42D9-8C58-25E699A21DBD");
    private static readonly Guid ControlPointUuid = Guid.Parse("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9");
    private static readonly Guid DataSourceUuid = Guid.Parse("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB");

    public enum LinkState { Off, NotSupported, Waiting, NeedsPermission, Connected }

    public LinkState State { get; private set; } = LinkState.Off;
    public event Action? StateChanged;
    /// <summary>A notification arrived or changed. The bool is true for ones already on the iPhone when it connected.</summary>
    public event Action<Envelope, bool>? NotificationPosted;
    public event Action<string>? NotificationRemoved;
    public event Action? Disconnected;
    public event Action<int, bool>? StatusReceived;

    private string _pcId = "";
    private GattServiceProvider? _provider;
    private BluetoothLEDevice? _device;
    private GattCharacteristic? _controlPoint;
    private GattCharacteristic? _notificationSource;
    private GattCharacteristic? _dataSource;
    private readonly SemaphoreSlim _commandLock = new(1, 1);
    private readonly List<byte> _data = new();
    private TaskCompletionSource<Dictionary<byte, string>>? _pendingNotif;
    private TaskCompletionSource<string>? _pendingApp;
    private readonly Dictionary<string, string> _appNames = new();
    private readonly Dictionary<uint, List<byte>> _actionIds = new();
    private readonly Dictionary<uint, byte> _categories = new();
    private bool _opening;

    /// <summary>
    /// True for iPhones (read notifications over ANCS). Pixels use the same service only to know when
    /// this PC is nearby, so they can run without an always-on notification.
    /// </summary>
    public bool ReadNotifications { get; set; }

    public async Task StartAsync(string pcId)
    {
        _pcId = pcId;
        if (_provider is not null) return;
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter is null || !adapter.IsLowEnergySupported || !adapter.IsPeripheralRoleSupported)
            {
                SetState(LinkState.NotSupported);
                Log.Warn("This PC's Bluetooth can't host the iPhone notification link (no LE peripheral support).");
                return;
            }
            var created = await GattServiceProvider.CreateAsync(ServiceUuid);
            if (created.Error != BluetoothError.Success) { SetState(LinkState.NotSupported); Log.Warn($"Bluetooth service failed: {created.Error}"); return; }
            _provider = created.ServiceProvider;

            // Reading the PC id needs an encrypted link: that's what makes the iPhone pair with the PC.
            var pcIdChar = await _provider.Service.CreateCharacteristicAsync(PcIdUuid, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Read,
                ReadProtectionLevel = GattProtectionLevel.EncryptionRequired,
                UserDescription = "Tethr Link PC",
            });
            pcIdChar.Characteristic.ReadRequested += OnPcIdRead;

            var status = await _provider.Service.CreateCharacteristicAsync(StatusUuid, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse,
                WriteProtectionLevel = GattProtectionLevel.EncryptionRequired,
            });
            status.Characteristic.WriteRequested += OnStatusWrite;

            var keepAlive = await _provider.Service.CreateCharacteristicAsync(KeepAliveUuid, new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Notify,
                ReadProtectionLevel = GattProtectionLevel.EncryptionRequired,
            });
            keepAlive.Characteristic.SubscribedClientsChanged += (c, _) =>
            {
                foreach (var client in c.SubscribedClients) _ = OpenAncsAsync(client.Session.DeviceId.Id);
            };

            _provider.StartAdvertising(new GattServiceProviderAdvertisingParameters { IsConnectable = true, IsDiscoverable = true });
            SetState(LinkState.Waiting);
            Log.Info("Waiting for your iPhone over Bluetooth");
        }
        catch (Exception ex)
        {
            SetState(LinkState.NotSupported);
            Log.Error("Couldn't start the iPhone Bluetooth link", ex);
        }
    }

    public void Stop()
    {
        try { _provider?.StopAdvertising(); } catch { }
        _provider = null;
        CloseDevice();
        SetState(LinkState.Off);
    }

    private void SetState(LinkState state)
    {
        if (State == state) return;
        State = state;
        StateChanged?.Invoke();
    }

    private async void OnPcIdRead(GattLocalCharacteristic sender, GattReadRequestedEventArgs args)
    {
        using var deferral = args.GetDeferral();
        var request = await args.GetRequestAsync();
        request?.RespondWithValue(Encoding.UTF8.GetBytes(_pcId).AsBuffer());
        _ = OpenAncsAsync(args.Session.DeviceId.Id);
    }

    private async void OnStatusWrite(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        using var deferral = args.GetDeferral();
        var request = await args.GetRequestAsync();
        if (request is null) return;
        var bytes = request.Value.ToArray();
        if (request.Option == GattWriteOption.WriteWithResponse) request.Respond();
        if (bytes.Length >= 2) StatusReceived?.Invoke(bytes[0] == 255 ? -1 : bytes[0], bytes[1] != 0);
        _ = OpenAncsAsync(args.Session.DeviceId.Id);
    }

    // ---------- ANCS ----------

    private async Task OpenAncsAsync(string deviceId)
    {
        if (!ReadNotifications) return;
        if (_opening || (_device is not null && _notificationSource is not null && _device.DeviceId == deviceId)) return;
        _opening = true;
        try
        {
            CloseDevice();
            _device = await BluetoothLEDevice.FromIdAsync(deviceId);
            if (_device is null) return;
            _device.ConnectionStatusChanged += OnConnectionChanged;

            // The iPhone may take a moment after pairing before it shares notifications.
            for (var attempt = 0; attempt < 6; attempt++)
            {
                if (await TrySubscribeAsync()) { SetState(LinkState.Connected); Log.Info($"iPhone notifications on ({_device.Name})"); return; }
                SetState(LinkState.NeedsPermission);
                await Task.Delay(TimeSpan.FromSeconds(5));
                if (_device is null) return;
            }
            Log.Warn("The iPhone is connected over Bluetooth but isn't sharing notifications yet.");
        }
        catch (Exception ex) { Log.Error("iPhone notifications failed", ex); }
        finally { _opening = false; }
    }

    private async Task<bool> TrySubscribeAsync()
    {
        if (_device is null) return false;
        var services = await _device.GetGattServicesForUuidAsync(AncsUuid, BluetoothCacheMode.Uncached);
        if (services.Status != GattCommunicationStatus.Success || services.Services.Count == 0) return false;
        var ancs = services.Services[0];

        async Task<GattCharacteristic?> Get(Guid uuid)
        {
            var r = await ancs.GetCharacteristicsForUuidAsync(uuid, BluetoothCacheMode.Uncached);
            return r.Status == GattCommunicationStatus.Success && r.Characteristics.Count > 0 ? r.Characteristics[0] : null;
        }
        _controlPoint = await Get(ControlPointUuid);
        _dataSource = await Get(DataSourceUuid);
        _notificationSource = await Get(NotificationSourceUuid);
        if (_controlPoint is null || _dataSource is null || _notificationSource is null) return false;

        // Data Source first: the iPhone starts sending notifications as soon as Notification Source is on.
        _dataSource.ValueChanged += OnDataSource;
        var ds = await _dataSource.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify);
        if (ds != GattCommunicationStatus.Success) { _notificationSource = null; return false; }
        _notificationSource.ValueChanged += OnNotificationSource;
        var ns = await _notificationSource.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify);
        if (ns != GattCommunicationStatus.Success) { _notificationSource = null; return false; }
        return true;
    }

    private void OnConnectionChanged(BluetoothLEDevice sender, object args)
    {
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Connected) return;
        CloseDevice();
        SetState(_provider is null ? LinkState.Off : LinkState.Waiting);
        Disconnected?.Invoke();
    }

    private void CloseDevice()
    {
        if (_notificationSource is not null) _notificationSource.ValueChanged -= OnNotificationSource;
        if (_dataSource is not null) _dataSource.ValueChanged -= OnDataSource;
        _notificationSource = _dataSource = _controlPoint = null;
        if (_device is not null)
        {
            _device.ConnectionStatusChanged -= OnConnectionChanged;
            _device.Dispose();
            _device = null;
        }
        _actionIds.Clear();
        _categories.Clear();
    }

    private const byte EventAdded = 0, EventModified = 1, EventRemoved = 2;
    private const byte FlagPreExisting = 4, FlagPositive = 8, FlagNegative = 16;
    private const byte CategoryIncomingCall = 1;

    private void OnNotificationSource(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        var b = args.CharacteristicValue.ToArray();
        if (b.Length < 8) return;
        byte eventId = b[0], flags = b[1], category = b[2];
        var uid = BitConverter.ToUInt32(b, 4);
        if (eventId == EventRemoved)
        {
            _actionIds.Remove(uid);
            _categories.Remove(uid);
            NotificationRemoved?.Invoke(Key(uid));
            return;
        }
        var actions = new List<byte>();
        if ((flags & FlagPositive) != 0) actions.Add(0);
        if ((flags & FlagNegative) != 0) actions.Add(1);
        _actionIds[uid] = actions;
        _categories[uid] = category;
        _ = FetchAsync(uid, category, (flags & FlagPreExisting) != 0);
    }

    private static string Key(uint uid) => $"ios-{uid}";

    /// <summary>Asks the iPhone for a notification's details, then its app's name (cached).</summary>
    private async Task FetchAsync(uint uid, byte category, bool preExisting)
    {
        await _commandLock.WaitAsync();
        Dictionary<byte, string>? attrs = null;
        try
        {
            if (_controlPoint is null) return;
            _data.Clear();
            _pendingNotif = new TaskCompletionSource<Dictionary<byte, string>>(TaskCreationOptions.RunContinuationsAsynchronously);
            var cmd = new List<byte> { 0 };
            cmd.AddRange(BitConverter.GetBytes(uid));
            cmd.Add(0);                                        // app identifier
            cmd.Add(1); cmd.AddRange(BitConverter.GetBytes((ushort)120)); // title
            cmd.Add(2); cmd.AddRange(BitConverter.GetBytes((ushort)120)); // subtitle
            cmd.Add(3); cmd.AddRange(BitConverter.GetBytes((ushort)600)); // message
            cmd.Add(5);                                        // date
            cmd.Add(6);                                        // positive action label
            cmd.Add(7);                                        // negative action label
            var write = await _controlPoint.WriteValueWithResultAsync(cmd.ToArray().AsBuffer(), GattWriteOption.WriteWithResponse);
            if (write.Status != GattCommunicationStatus.Success) return; // already gone
            var done = await Task.WhenAny(_pendingNotif.Task, Task.Delay(3000));
            if (done != _pendingNotif.Task) return;
            attrs = _pendingNotif.Task.Result;

            var app = attrs.GetValueOrDefault((byte)0, "");
            if (app.Length > 0 && !_appNames.ContainsKey(app))
            {
                _data.Clear();
                _pendingApp = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
                var appCmd = new List<byte> { 1 };
                appCmd.AddRange(Encoding.UTF8.GetBytes(app));
                appCmd.Add(0);
                appCmd.Add(0); // display name
                var w = await _controlPoint.WriteValueWithResultAsync(appCmd.ToArray().AsBuffer(), GattWriteOption.WriteWithResponse);
                if (w.Status == GattCommunicationStatus.Success && await Task.WhenAny(_pendingApp.Task, Task.Delay(2000)) == _pendingApp.Task)
                    _appNames[app] = _pendingApp.Task.Result;
            }
        }
        catch (Exception ex) { Log.Error("Reading an iPhone notification failed", ex); }
        finally
        {
            _pendingNotif = null;
            _pendingApp = null;
            _commandLock.Release();
        }
        if (attrs is null) return;

        var bundle = attrs.GetValueOrDefault((byte)0, "");
        var title = attrs.GetValueOrDefault((byte)1, "");
        var subtitle = attrs.GetValueOrDefault((byte)2, "");
        var message = attrs.GetValueOrDefault((byte)3, "");
        var appName = _appNames.GetValueOrDefault(bundle) ?? FriendlyAppName(bundle);
        if (category == CategoryIncomingCall && string.IsNullOrEmpty(message)) message = "Incoming call";

        var labels = new Dictionary<byte, string> { [0] = attrs.GetValueOrDefault((byte)6, ""), [1] = attrs.GetValueOrDefault((byte)7, "") };
        var actions = (_actionIds.GetValueOrDefault(uid) ?? new List<byte>())
            .Select(id => new
            {
                title = string.IsNullOrEmpty(labels[id]) ? (id == 0 ? (category == CategoryIncomingCall ? "Answer" : "Open") : (category == CategoryIncomingCall ? "Decline" : "Clear")) : labels[id],
                semantic = category == CategoryIncomingCall && id == 0 ? "call" : (id == 1 ? "delete" : ""),
            }).ToArray();

        var env = Envelope.Of("notif.posted", new
        {
            key = Key(uid),
            app = bundle,
            appName,
            title = string.IsNullOrEmpty(subtitle) ? title : $"{title} · {subtitle}",
            text = message,
            time = ParseDate(attrs.GetValueOrDefault((byte)5, "")).ToUnixTimeMilliseconds(),
            canReply = false,
            isMessage = false,
            actions,
        });
        NotificationPosted?.Invoke(env, preExisting);
    }

    private void OnDataSource(GattCharacteristic sender, GattValueChangedEventArgs args)
    {
        _data.AddRange(args.CharacteristicValue.ToArray());
        if (_data.Count == 0) return;
        if (_data[0] == 0 && _pendingNotif is { } notif && TryParseNotification(out var attrs)) { _data.Clear(); notif.TrySetResult(attrs); }
        else if (_data[0] == 1 && _pendingApp is { } app && TryParseApp(out var name)) { _data.Clear(); app.TrySetResult(name); }
    }

    /// <summary>[0][uid x4] then 7 × [attr id][uint16 length][value]; may arrive in several pieces.</summary>
    private bool TryParseNotification(out Dictionary<byte, string> attrs)
    {
        attrs = new();
        var i = 5;
        for (var n = 0; n < 7; n++)
        {
            if (i + 3 > _data.Count) return false;
            var id = _data[i];
            var len = _data[i + 1] | (_data[i + 2] << 8);
            if (i + 3 + len > _data.Count) return false;
            attrs[id] = Encoding.UTF8.GetString(_data.GetRange(i + 3, len).ToArray());
            i += 3 + len;
        }
        return true;
    }

    /// <summary>[1][app id][0][attr id][uint16 length][value].</summary>
    private bool TryParseApp(out string name)
    {
        name = "";
        var end = _data.IndexOf(0, 1);
        if (end < 0 || end + 4 > _data.Count) return false;
        var len = _data[end + 2] | (_data[end + 3] << 8);
        if (end + 4 + len > _data.Count) return false;
        name = Encoding.UTF8.GetString(_data.GetRange(end + 4, len).ToArray());
        return true;
    }

    /// <summary>Presses a notification's button on the iPhone (index into the actions sent).</summary>
    public async Task PerformActionAsync(string key, int index)
    {
        if (!TryUid(key, out var uid) || _controlPoint is null) return;
        var ids = _actionIds.GetValueOrDefault(uid);
        if (ids is null || index < 0 || index >= ids.Count) return;
        await PerformAsync(uid, ids[index]);
    }

    /// <summary>Dismiss from the PC = the iPhone's Clear button (never declines a call).</summary>
    public async Task DismissAsync(string key)
    {
        if (!TryUid(key, out var uid) || _categories.GetValueOrDefault(uid) == CategoryIncomingCall) return;
        if (_actionIds.GetValueOrDefault(uid)?.Contains(1) == true) await PerformAsync(uid, 1);
    }

    private async Task PerformAsync(uint uid, byte actionId)
    {
        await _commandLock.WaitAsync();
        try
        {
            if (_controlPoint is null) return;
            var cmd = new List<byte> { 2 };
            cmd.AddRange(BitConverter.GetBytes(uid));
            cmd.Add(actionId);
            await _controlPoint.WriteValueWithResultAsync(cmd.ToArray().AsBuffer(), GattWriteOption.WriteWithResponse);
        }
        catch (Exception ex) { Log.Error("iPhone notification action failed", ex); }
        finally { _commandLock.Release(); }
    }

    public static bool IsIPhoneKey(string? key) => key?.StartsWith("ios-") == true;

    private static bool TryUid(string key, out uint uid)
    {
        uid = 0;
        return IsIPhoneKey(key) && uint.TryParse(key.AsSpan(4), out uid);
    }

    /// <summary>ANCS dates look like 20261113T104522 (local time).</summary>
    private static DateTimeOffset ParseDate(string s) =>
        DateTime.TryParseExact(s, "yyyyMMdd'T'HHmmss", null, System.Globalization.DateTimeStyles.AssumeLocal, out var d)
            ? new DateTimeOffset(d) : DateTimeOffset.Now;

    private static string FriendlyAppName(string bundle) => bundle switch
    {
        "com.apple.MobileSMS" => "Messages",
        "com.apple.mobilephone" => "Phone",
        "com.apple.mobilemail" => "Mail",
        "com.apple.mobilecal" => "Calendar",
        "com.apple.reminders" => "Reminders",
        "com.apple.facetime" => "FaceTime",
        "" => "iPhone",
        _ => bundle.Split('.').LastOrDefault() ?? bundle,
    };
}
