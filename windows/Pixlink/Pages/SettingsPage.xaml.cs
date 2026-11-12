using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Pixlink.Pages;

public sealed partial class SettingsPage : Page
{
    private bool _loading = true;

    public SettingsPage()
    {
        InitializeComponent();
        var s = App.Session.Settings;
        ToastsSwitch.IsOn = s.ShowToasts;
        HotspotSwitch.IsOn = s.AutoJoinHotspot;
        FingerprintText.Text = $"This PC's certificate: {App.Session.PcFingerprint}";
        PhoneText.Text = $"Linked to {App.Session.PhoneName}";
        VersionText.Text = $"Pixlink {Services.Updater.Current}";
        UpdateHotspotText();
        _loading = false;
    }

    private void UpdateHotspotText() =>
        HotspotText.Text = string.IsNullOrEmpty(App.Session.HotspotSsid)
            ? "Not set. You'll be asked the first time you use Hotspot."
            : $"Joins \"{App.Session.HotspotSsid}\" when you turn on Hotspot";

    private async void OnChangeHotspot(object sender, RoutedEventArgs e)
    {
        if (App.Window is { } w && await w.AskHotspotDetailsAsync()) UpdateHotspotText();
    }

    private async void OnCheckUpdates(object sender, RoutedEventArgs e)
    {
        CheckButton.IsEnabled = false;
        UpdateStatus.Text = "Checking…";
        try
        {
            var update = await Services.Updater.CheckAsync();
            if (update is null) UpdateStatus.Text = "You're on the latest version.";
            else
            {
                UpdateStatus.Text = $"Pixlink {update.Version} is available. Click Update now at the top of the window.";
                App.Window?.ShowUpdate(update);
            }
        }
        catch (Exception)
        {
            UpdateStatus.Text = "Couldn't check for updates. Are you online?";
        }
        CheckButton.IsEnabled = true;
    }

    private void OnToggle(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        var s = App.Session.Settings;
        s.ShowToasts = ToastsSwitch.IsOn;
        s.AutoJoinHotspot = HotspotSwitch.IsOn;
        s.Save();
    }

    private async void OnUnpair(object sender, RoutedEventArgs e)
    {
        var result = await new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = $"Unlink {App.Session.PhoneName}?",
            Content = "You'll need to scan a new QR code to link again.",
            PrimaryButtonText = "Unlink",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
        }.ShowAsync();
        if (result == ContentDialogResult.Primary) App.Session.Unpair();
    }
}
