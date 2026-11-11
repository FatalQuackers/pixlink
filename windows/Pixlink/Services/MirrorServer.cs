using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Storage.Streams;

namespace Pixlink.Services;

/// <summary>
/// Receives the phone's H.264 screen stream on port 47101 and feeds it to a
/// MediaStreamSource, which a MediaPlayerElement on the Screen page plays with low latency.
/// </summary>
public sealed class MirrorServer
{
    public const int MirrorPort = 47101;

    private readonly X509Certificate2 _cert;
    private readonly Func<string?> _pinnedPhone;
    private BlockingCollection<(byte[] data, long ptsUs)> _frames = new(boundedCapacity: 120);

    public event Action? StreamStarted;
    public event Action? StreamEnded;

    public MirrorServer(X509Certificate2 cert, Func<string?> pinnedPhone)
    {
        _cert = cert;
        _pinnedPhone = pinnedPhone;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        var listener = new TcpListener(IPAddress.Any, MirrorPort);
        listener.Start();
        while (!ct.IsCancellationRequested)
        {
            var tcp = await listener.AcceptTcpClientAsync(ct);
            tcp.NoDelay = true;
            _ = ReceiveAsync(tcp, ct);
        }
    }

    private async Task ReceiveAsync(TcpClient tcp, CancellationToken ct)
    {
        using var client = tcp;
        var ssl = new SslStream(tcp.GetStream(), false, (_, cert, _, _) =>
            cert is not null && string.Equals(Certificates.Fingerprint(cert), _pinnedPhone(), StringComparison.OrdinalIgnoreCase));
        try
        {
            await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificate = _cert,
                ClientCertificateRequired = true,
                EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
            }, ct);

            _frames = new BlockingCollection<(byte[], long)>(boundedCapacity: 120);
            StreamStarted?.Invoke();

            var header = new byte[12];
            while (!ct.IsCancellationRequested)
            {
                await ssl.ReadExactlyAsync(header, ct);
                var len = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(0, 4));
                var pts = BinaryPrimitives.ReadInt64BigEndian(header.AsSpan(4, 8));
                if (len <= 0 || len > 8 * 1024 * 1024) break;
                var data = new byte[len];
                await ssl.ReadExactlyAsync(data, ct);
                // If the player falls behind, drop old frames instead of building latency.
                if (!_frames.TryAdd((data, pts))) { _frames.TryTake(out var _drop); _frames.TryAdd((data, pts)); }
            }
        }
        catch (Exception) { /* stream ended */ }
        finally
        {
            _frames.CompleteAdding();
            StreamEnded?.Invoke();
        }
    }

    /// <summary>Creates a media source for the current stream at the size the phone reported.</summary>
    public MediaStreamSource CreateSource(uint width, uint height)
    {
        var props = VideoEncodingProperties.CreateH264();
        props.Width = width;
        props.Height = height;
        var source = new MediaStreamSource(new VideoStreamDescriptor(props))
        {
            IsLive = true,
            BufferTime = TimeSpan.Zero,
            CanSeek = false,
        };
        long? firstPts = null;
        source.SampleRequested += (_, args) =>
        {
            var frames = _frames;
            try
            {
                var (data, pts) = frames.Take();
                firstPts ??= pts;
                var sample = MediaStreamSample.CreateFromBuffer(
                    data.ToBuffer(), TimeSpan.FromTicks((pts - firstPts.Value) * 10));
                args.Request.Sample = sample;
            }
            catch (InvalidOperationException)
            {
                args.Request.Sample = null; // stream finished
            }
        };
        return source;
    }
}

internal static class BufferExtensions
{
    public static IBuffer ToBuffer(this byte[] data)
    {
        var writer = new DataWriter();
        writer.WriteBytes(data);
        return writer.DetachBuffer();
    }
}
