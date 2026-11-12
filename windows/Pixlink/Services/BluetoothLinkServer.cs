using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;

namespace Pixlink.Services;

/// <summary>
/// Lets the phone connect over Bluetooth when Wi-Fi isn't available. It advertises a Bluetooth
/// channel and hands each connection to <see cref="LinkServer.AcceptAsync"/>, so the same
/// encrypted, certificate-pinned link runs on top of it.
/// </summary>
public sealed class BluetoothLinkServer : IDisposable
{
    /// <summary>Must match LinkClient's UUID on the phone.</summary>
    public static readonly Guid ServiceUuid = new("6f3c1e9a-2b7d-4c5e-9a1f-8d2b3c4e5f60");

    private readonly LinkServer _link;
    private RfcommServiceProvider? _provider;
    private StreamSocketListener? _listener;
    private CancellationToken _ct;

    public BluetoothLinkServer(LinkServer link) { _link = link; }

    /// <summary>This PC's Bluetooth address ("AA:BB:CC:DD:EE:FF"), sent to the phone so it knows where to connect.</summary>
    public static async Task<string?> AddressAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter is null || !adapter.IsClassicSupported) return null;
            var hex = adapter.BluetoothAddress.ToString("X12");
            return string.Join(":", Enumerable.Range(0, 6).Select(i => hex.Substring(i * 2, 2)));
        }
        catch (Exception) { return null; }
    }

    public async Task<bool> StartAsync(CancellationToken ct)
    {
        _ct = ct;
        try
        {
            if (await AddressAsync() is null) { Log.Info("No Bluetooth adapter: Bluetooth connections are off"); return false; }
            _provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(ServiceUuid));
            _listener = new StreamSocketListener();
            _listener.ConnectionReceived += OnConnection;
            await _listener.BindServiceNameAsync(_provider.ServiceId.AsString(), SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication);
            _provider.StartAdvertising(_listener, true);
            Log.Info("Listening for the phone over Bluetooth");
            return true;
        }
        catch (Exception ex)
        {
            Log.Warn($"Bluetooth connections unavailable: {ex.Message}");
            Dispose();
            return false;
        }
    }

    private void OnConnection(StreamSocketListener sender, StreamSocketListenerConnectionReceivedEventArgs args)
    {
        var socket = args.Socket;
        var stream = new DuplexStream(socket.InputStream.AsStreamForRead(0), socket.OutputStream.AsStreamForWrite(0), socket);
        Log.Info("Phone connecting over Bluetooth");
        _ = _link.AcceptAsync(stream, stream, "bluetooth", _ct);
    }

    public void Dispose()
    {
        try { _provider?.StopAdvertising(); } catch { }
        try { _listener?.Dispose(); } catch { }
        _provider = null;
        _listener = null;
    }

    /// <summary>One read stream and one write stream as a single Stream for SslStream.</summary>
    private sealed class DuplexStream(Stream input, Stream output, IDisposable owner) : Stream
    {
        public override bool CanRead => true;
        public override bool CanWrite => true;
        public override bool CanSeek => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int offset, int count) => input.Read(buffer, offset, count);
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) => input.ReadAsync(buffer, offset, count, ct);
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default) => input.ReadAsync(buffer, ct);
        public override void Write(byte[] buffer, int offset, int count) { output.Write(buffer, offset, count); output.Flush(); }
        public override async Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) { await output.WriteAsync(buffer, offset, count, ct); await output.FlushAsync(ct); }
        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default) { await output.WriteAsync(buffer, ct); await output.FlushAsync(ct); }
        public override void Flush() => output.Flush();
        public override Task FlushAsync(CancellationToken ct) => output.FlushAsync(ct);
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        protected override void Dispose(bool disposing)
        {
            if (disposing) { try { input.Dispose(); } catch { } try { output.Dispose(); } catch { } try { owner.Dispose(); } catch { } }
            base.Dispose(disposing);
        }
    }
}
