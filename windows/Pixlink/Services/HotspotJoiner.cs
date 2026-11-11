using Windows.Devices.WiFi;
using Windows.Security.Credentials;

namespace Pixlink.Services;

/// <summary>Joins the phone's hotspot from Windows using the name and password the phone shared.</summary>
public static class HotspotJoiner
{
    public static async Task<bool> JoinAsync(string ssid, string? password)
    {
        if (await WiFiAdapter.RequestAccessAsync() != WiFiAccessStatus.Allowed) return false;
        var adapters = await WiFiAdapter.FindAllAdaptersAsync();
        var adapter = adapters.FirstOrDefault();
        if (adapter is null) return false;

        // The hotspot can take a few seconds to appear; scan a few times.
        for (var attempt = 0; attempt < 5; attempt++)
        {
            await adapter.ScanAsync();
            var network = adapter.NetworkReport.AvailableNetworks.FirstOrDefault(n => n.Ssid == ssid);
            if (network is not null)
            {
                var result = password is null
                    ? await adapter.ConnectAsync(network, WiFiReconnectionKind.Automatic)
                    : await adapter.ConnectAsync(network, WiFiReconnectionKind.Automatic, new PasswordCredential { Password = password });
                return result.ConnectionStatus == WiFiConnectionStatus.Success;
            }
            await Task.Delay(2000);
        }
        return false;
    }
}
