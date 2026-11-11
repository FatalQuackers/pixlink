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
        _loading = false;
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
