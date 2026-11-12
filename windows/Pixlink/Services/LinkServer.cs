using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Pixlink.Services;

/// <summary>
/// TLS server on port 47100. Only accepts the paired phone's certificate, except while a
/// pairing QR code is showing — then any phone may connect, but must send the one-time token.
/// </summary>
public sealed class LinkServer
{
    public const int ControlPort = 47100;

    private readonly X509Certificate2 _cert;
    private readonly Func<string?> _pinnedPhone;
    private readonly Func<bool> _pairingOpen;
    private TcpListener? _listener;

    public event Action<PhoneConnection>? Connected;

    public LinkServer(X509Certificate2 cert, Func<string?> pinnedPhoneFingerprint, Func<bool> pairingOpen)
    {
        _cert = cert;
        _pinnedPhone = pinnedPhoneFingerprint;
        _pairingOpen = pairingOpen;
    }

    public async Task RunAsync(CancellationToken ct)
    {
        _listener = new TcpListener(IPAddress.Any, ControlPort);
        _listener.Start();
        while (!ct.IsCancellationRequested)
        {
            var tcp = await _listener.AcceptTcpClientAsync(ct);
            _ = HandleAsync(tcp, ct);
        }
    }

    private Task HandleAsync(TcpClient tcp, CancellationToken ct) => AcceptAsync(tcp.GetStream(), tcp, "wifi", ct);

    /// <summary>
    /// The same TLS handshake and certificate pinning for every route (Wi-Fi or Bluetooth),
    /// so a Bluetooth connection is exactly as private as a Wi-Fi one.
    /// </summary>
    public async Task AcceptAsync(Stream transport, IDisposable owner, string via, CancellationToken ct)
    {
        string? phoneFp = null;
        var ssl = new SslStream(transport, false, (_, cert, _, _) =>
        {
            // An iPhone pairs without a certificate (it can't make one); the PC issues it one in pair.accept.
            if (cert is null) { phoneFp = ""; return _pairingOpen(); }
            phoneFp = Certificates.Fingerprint(cert);
            var pinned = _pinnedPhone();
            // Paired phone, or any phone while the pairing QR is on screen (token checked next).
            return string.Equals(phoneFp, pinned, StringComparison.OrdinalIgnoreCase) || _pairingOpen();
        });

        try
        {
            await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
            {
                ServerCertificate = _cert,
                ClientCertificateRequired = true,
                EnabledSslProtocols = SslProtocols.Tls13 | SslProtocols.Tls12,
            }, ct);
        }
        catch (Exception)
        {
            owner.Dispose();
            return;
        }

        var conn = new PhoneConnection(owner, ssl, phoneFp!, via);
        Connected?.Invoke(conn);
        await conn.ReadLoopAsync(ct);
    }

    /// <summary>The PC's LAN address to put in the QR code (first private IPv4).</summary>
    public static string LocalAddress()
    {
        var addrs = System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up
                     && n.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Select(a => a.Address)
            .Where(a => a.AddressFamily == AddressFamily.InterNetwork)
            .ToList();
        var priv = addrs.FirstOrDefault(a =>
        {
            var b = a.GetAddressBytes();
            return b[0] == 192 && b[1] == 168 || b[0] == 10 || b[0] == 172 && b[1] >= 16 && b[1] <= 31;
        });
        return (priv ?? addrs.FirstOrDefault() ?? IPAddress.Loopback).ToString();
    }
}

/// <summary>One live connection from the phone.</summary>
public sealed class PhoneConnection : IDisposable
{
    private readonly IDisposable _transport;
    private readonly SslStream _ssl;
    private readonly StreamWriter _writer;
    private readonly SemaphoreSlim _writeLock = new(1, 1);

    public string PhoneFingerprint { get; }
    /// <summary>"wifi" or "bluetooth".</summary>
    public string Via { get; }
    public event Action<Envelope>? MessageReceived;
    public event Action? Closed;

    public PhoneConnection(IDisposable transport, SslStream ssl, string fingerprint, string via = "wifi")
    {
        _transport = transport;
        _ssl = ssl;
        Via = via;
        PhoneFingerprint = fingerprint;
        _writer = new StreamWriter(ssl, new UTF8Encoding(false)) { AutoFlush = true, NewLine = "\n" };
    }

    public async Task ReadLoopAsync(CancellationToken ct)
    {
        try
        {
            using var reader = new StreamReader(_ssl, Encoding.UTF8);
            while (!ct.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line is null) break;
                if (Envelope.Parse(line) is { } env) MessageReceived?.Invoke(env);
            }
        }
        catch (Exception) { /* connection dropped */ }
        finally
        {
            Dispose();
            Closed?.Invoke();
        }
    }

    public async Task SendAsync(Envelope env)
    {
        await _writeLock.WaitAsync();
        try { await _writer.WriteLineAsync(env.ToLine()); }
        catch (Exception) { Dispose(); }
        finally { _writeLock.Release(); }
    }

    public void Dispose()
    {
        try { _ssl.Dispose(); } catch { }
        try { _transport.Dispose(); } catch { }
    }
}
