using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace Pixlink.Services;

/// <summary>
/// Announces "Pixlink PC is here" on every local network every few seconds (UDP broadcast,
/// port 47102), so the paired phone can find this PC again when its IP address changes —
/// for example after joining the phone's own hotspot.
/// </summary>
public static class Beacon
{
    public const int Port = 47102;

    public static async Task RunAsync(Func<string> payloadFactory, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var payload = Encoding.UTF8.GetBytes(payloadFactory());
            foreach (var (local, broadcast) in Networks())
            {
                try
                {
                    using var udp = new UdpClient(new IPEndPoint(local, 0)) { EnableBroadcast = true };
                    await udp.SendAsync(payload, new IPEndPoint(broadcast, Port), ct);
                }
                catch (Exception) { /* interface went away; try again next round */ }
            }
            // Faster while pairing is open, so a phone entering a code finds the PC quickly.
            try { await Task.Delay(TimeSpan.FromSeconds(App.Session?.PairingOpen == true ? 1 : 3), ct); } catch (TaskCanceledException) { break; }
        }
    }

    /// <summary>Each active IPv4 interface's address and its subnet broadcast address.</summary>
    private static IEnumerable<(IPAddress local, IPAddress broadcast)> Networks()
    {
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            foreach (var u in nic.GetIPProperties().UnicastAddresses)
            {
                if (u.Address.AddressFamily != AddressFamily.InterNetwork || u.IPv4Mask is null) continue;
                var ip = u.Address.GetAddressBytes();
                var mask = u.IPv4Mask.GetAddressBytes();
                var bc = new byte[4];
                for (var i = 0; i < 4; i++) bc[i] = (byte)(ip[i] | ~mask[i]);
                yield return (u.Address, new IPAddress(bc));
            }
        }
    }
}
