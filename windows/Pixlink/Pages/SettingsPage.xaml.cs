using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Pixlink.Services;

namespace Pixlink.Pages;

public sealed partial class SettingsPage : Page
{
    private bool _loading = true;
    private AppSettings S => App.Session.Settings;

    public SettingsPage()
    {
        InitializeComponent();

        PcNameBox.Text = App.Session.PcName;
        ThemeBox.SelectedIndex = S.Theme switch { "Light" => 1, "Dark" => 2, _ => 0 };
        StartupSwitch.IsOn = StartupTask.IsEnabled;

        ToastsSwitch.IsOn = S.ShowToasts;
        ContentSwitch.IsOn = S.ToastShowContent;
        SoundSwitch.IsOn = S.ToastSound;
        BuildAppsList();

        BatterySwitch.IsOn = S.LowBatteryAlert;
        BatterySlider.Value = S.LowBatteryLevel;
        BatterySlider.IsEnabled = S.LowBatteryAlert;
        HotspotSwitch.IsOn = S.AutoJoinHotspot;
        UpdateHotspotText();

        QualityBox.SelectedIndex = S.MirrorMaxSize switch { <= 1280 => 0, 1920 => 1, 1600 => 2, _ => 3 };
        FpsBox.SelectedIndex = S.MirrorFps switch { <= 30 => 0, <= 60 => 1, _ => 2 };
        BitrateSlider.Value = S.MirrorBitrateMbps;

        DownloadText.Text = $"Saved in {FileTransfers.DownloadFolder}";
        VersionText.Text = $"Pixlink {Updater.Current}";
        AutoUpdateSwitch.IsOn = S.AutoCheckUpdates;
        FingerprintText.Text = $"This PC's certificate: {App.Session.PcFingerprint}";
        PhoneText.Text = $"Linked to {App.Session.PhoneName}";
        _loading = false;
    }

    private void Save() { if (!_loading) S.Save(); }

    private void OnToggle(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        S.ShowToasts = ToastsSwitch.IsOn;
        S.ToastShowContent = ContentSwitch.IsOn;
        S.ToastSound = SoundSwitch.IsOn;
        S.LowBatteryAlert = BatterySwitch.IsOn;
        S.AutoJoinHotspot = HotspotSwitch.IsOn;
        S.AutoCheckUpdates = AutoUpdateSwitch.IsOn;
        if (S.StartWithWindows != StartupSwitch.IsOn)
        {
            S.StartWithWindows = StartupSwitch.IsOn;
            try { StartupTask.Set(StartupSwitch.IsOn); } catch (Exception ex) { Crash.Log(ex, "Start with Windows"); }
        }
        BatterySlider.IsEnabled = BatterySwitch.IsOn;
        Save();
    }

    private void OnPcNameChanged(object sender, RoutedEventArgs e)
    {
        var name = PcNameBox.Text.Trim();
        S.PcDisplayName = name.Length == 0 || name == Environment.MachineName ? null : name;
        Save();
    }

    private void OnThemeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_loading) return;
        S.Theme = (ThemeBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "System";
        App.Window?.ApplyTheme();
        Save();
    }

    private void OnBatteryLevel(object sender, Microsoft.UI.Xaml.Controls.Primitives.RangeBaseValueChangedEventArgs e)
    {
        if (_loading) return;
        S.LowBatteryLevel = (int)BatterySlider.Value;
        Save();
    }

    private void OnMirrorChanged(object sender, object e)
    {
        if (_loading) return;
        if ((QualityBox.SelectedItem as ComboBoxItem)?.Tag is string q) S.MirrorMaxSize = int.Parse(q);
        if ((FpsBox.SelectedItem as ComboBoxItem)?.Tag is string f) S.MirrorFps = int.Parse(f);
        S.MirrorBitrateMbps = (int)BitrateSlider.Value;
        Save();
    }

    /// <summary>One switch per app that has sent a notification: off = no pop-ups from it.</summary>
    private void BuildAppsList()
    {
        var apps = App.Session.Notifications
            .GroupBy(n => n.App)
            .Select(g => (pkg: g.Key, name: g.First().AppName))
            .Concat(S.MutedApps.Select(pkg => (pkg, name: pkg)))
            .GroupBy(a => a.pkg).Select(g => g.First())
            .OrderBy(a => a.name)
            .ToList();

        AppsList.Children.Clear();
        foreach (var (pkg, name) in apps)
        {
            var toggle = new ToggleSwitch
            {
                IsOn = !S.MutedApps.Contains(pkg), OnContent = "", OffContent = "", MinWidth = 0,
                HorizontalAlignment = HorizontalAlignment.Right,
            };
            toggle.Toggled += (_, _) =>
            {
                if (toggle.IsOn) S.MutedApps.Remove(pkg); else S.MutedApps.Add(pkg);
                Save();
            };
            var row = new Grid();
            row.Children.Add(new TextBlock { Text = string.IsNullOrEmpty(name) ? pkg : name, VerticalAlignment = VerticalAlignment.Center });
            row.Children.Add(toggle);
            AppsList.Children.Add(row);
        }
    }

    private void UpdateHotspotText() =>
        HotspotText.Text = string.IsNullOrEmpty(App.Session.HotspotSsid)
            ? "Not set. You'll be asked the first time you use Hotspot."
            : $"Joins \"{App.Session.HotspotSsid}\" when you turn on Hotspot";

    private async void OnChangeHotspot(object sender, RoutedEventArgs e)
    {
        if (App.Window is { } w && await w.AskHotspotDetailsAsync()) UpdateHotspotText();
    }

    private async void OnOpenDownloads(object sender, RoutedEventArgs e) =>
        await Windows.System.Launcher.LaunchFolderPathAsync(FileTransfers.DownloadFolder);

    private async void OnOpenLogs(object sender, RoutedEventArgs e) =>
        await Windows.System.Launcher.LaunchFolderPathAsync(AppSettings.Folder);

    private async void OnCheckUpdates(object sender, RoutedEventArgs e)
    {
        CheckButton.IsEnabled = false;
        UpdateStatus.Text = "Checking…";
        try
        {
            var update = await Updater.CheckAsync();
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

    private async void OnUnpair(object sender, RoutedEventArgs e)
    {
        var result = await new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = $"Unlink {App.Session.PhoneName}?",
            Content = "You'll need to scan a new QR code or enter a new code to link again.",
            PrimaryButtonText = "Unlink",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
        }.ShowAsync();
        if (result == ContentDialogResult.Primary) App.Session.Unpair();
    }
}
