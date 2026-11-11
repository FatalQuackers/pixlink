using Windows.Devices.Bluetooth;
using Windows.Devices.Enumeration;

namespace Pixlink.Services;

/// <summary>
/// Call audio. The phone places/answers the call (over Wi-Fi), and audio is routed to the PC
/// over Bluetooth with the PC acting as a hands-free headset.
///
/// EXPERIMENTAL: Windows only exposes the hands-free (HFP) role to some apps and adapters.
/// For v1, Pixlink checks the phone is paired over Bluetooth and relies on Windows' own
/// hands-free audio routing. This needs testing on a range of PCs — see the tracker.
/// </summary>
public static class BluetoothCalls
{
    /// <summary>True if a Bluetooth device whose name matches the phone is paired with Windows.</summary>
    public static async Task<bool> IsPhonePairedAsync(string phoneName)
    {
        var selector = BluetoothDevice.GetDeviceSelectorFromPairingState(true);
        var devices = await DeviceInformation.FindAllAsync(selector);
        return devices.Any(d => d.Name.Contains(phoneName, StringComparison.OrdinalIgnoreCase)
                             || d.Name.Contains("Pixel", StringComparison.OrdinalIgnoreCase));
    }

    /// <summary>Opens Windows Bluetooth settings so the user can pair their phone.</summary>
    public static Task OpenBluetoothSettingsAsync() =>
        Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:bluetooth")).AsTask();
}
