using Windows.Networking;
using Windows.Networking.ServiceDiscovery.Dnssd;
using Windows.Networking.Sockets;

namespace Pixlink.Services;

/// <summary>
/// Advertises this PC on the local network as "&lt;pcId&gt;._pixlink._tcp" (Bonjour / mDNS).
/// iPhones can't hear the UDP beacon (Apple needs a special permission for broadcasts), so this is
/// how a paired iPhone finds the PC again when its IP address changes. The service points at a tiny
/// placeholder listener on port 47103; the iPhone only uses it to learn the PC's address.
/// </summary>
public static class Bonjour
{
    public const int Port = 47103;
    private static StreamSocketListener? _listener;
    private static DnssdServiceInstance? _instance;

    public static async Task StartAsync(string pcId)
    {
        try
        {
            _listener = new StreamSocketListener();
            _listener.ConnectionReceived += (_, a) => a.Socket.Dispose();
            await _listener.BindServiceNameAsync(Port.ToString());
            _instance = new DnssdServiceInstance($"{pcId}._pixlink._tcp.local", new HostName(Environment.MachineName + ".local"), (ushort)Port);
            _instance.TextAttributes["port"] = LinkServer.ControlPort.ToString();
            var result = await _instance.RegisterStreamSocketListenerAsync(_listener);
            Log.Info($"Advertising on the local network for iPhones ({result.Status})");
        }
        catch (Exception ex)
        {
            Log.Warn($"Couldn't advertise on the local network for iPhones: {ex.Message}");
        }
    }
}
