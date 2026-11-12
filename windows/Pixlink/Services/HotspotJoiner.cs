using Windows.Devices.WiFi;
using Windows.Security.Credentials;

namespace Pixlink.Services;

/// <summary>Joins the phone's hotspot from Windows using the name and password the phone shared.</summary>
public static class HotspotJoiner
{
    public static async Task<bool> JoinAsync(string ssid, string? password, int attempts = 8)
    {
        if (await WiFiAdapter.RequestAccessAsync() != WiFiAccessStatus.Allowed) return false;
        if (await IsConnectedToAsync(ssid)) return true;
        var adapters = await WiFiAdapter.FindAllAdaptersAsync();
        var adapter = adapters.FirstOrDefault();
        if (adapter is null) return false;

        // The hotspot can take a while to appear (longer if the user is switching it on by hand); keep scanning.
        for (var attempt = 0; attempt < attempts; attempt++)
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

    /// <summary>True if Windows is already on this Wi-Fi network.</summary>
    private static async Task<bool> IsConnectedToAsync(string ssid)
    {
        var adapters = await WiFiAdapter.FindAllAdaptersAsync();
        foreach (var a in adapters)
        {
            var profile = await a.NetworkAdapter.GetConnectedProfileAsync();
            if (profile?.WlanConnectionProfileDetails?.GetConnectedSsid() == ssid) return true;
        }
        return false;
    }
}
