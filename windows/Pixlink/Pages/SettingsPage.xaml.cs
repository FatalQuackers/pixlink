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
        LoadLayout();
        VersionText.Text = $"Pixlink {Updater.Current}" + (AppSettings.IsPortable ? " · Portable (settings are saved in the Data folder next to Pixlink.exe)" : "");
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

    // ---------- Layout, clipboard, screen, help ----------
    private bool _loadingLayout;

    private void LoadLayout()
    {
        _loadingLayout = true;
        var st = App.Session.Settings;
        GroupSwitch.IsOn = st.GroupNotificationsByApp;
        TraySwitch.IsOn = st.CloseToTray;
        AutoClipSwitch.IsOn = st.AutoClipboard;
        CodesSwitch.IsOn = st.CopyCodes;
        StopHiddenSwitch.IsOn = st.StopMirrorWhenHidden;
        BluetoothSwitch.IsOn = st.AllowBluetooth;
        PhoneWakeSwitch.IsOn = st.AllowPhoneWake;
        UpdatePhoneWakeStatus();
        QuickActionsHint.Text = string.Join(", ", st.QuickActions
            .Select(id => Pixlink.Models.QuickActionItem.Catalog.FirstOrDefault(c => c.Id == id).Name)
            .Where(n => n is not null));
        _loadingLayout = false;
    }

    private void OnLayoutToggled(object sender, RoutedEventArgs e)
    {
        if (_loadingLayout) return;
        var st = App.Session.Settings;
        st.GroupNotificationsByApp = GroupSwitch.IsOn;
        st.CloseToTray = TraySwitch.IsOn;
        st.AutoClipboard = AutoClipSwitch.IsOn;
        st.CopyCodes = CodesSwitch.IsOn;
        st.StopMirrorWhenHidden = StopHiddenSwitch.IsOn;
        st.Save();
        App.Window?.RefreshLayout();
    }

    private async void OnBluetoothToggled(object sender, RoutedEventArgs e)
    {
        if (_loadingLayout) return;
        await App.Session.SetAllowBluetoothAsync(BluetoothSwitch.IsOn);
    }

    private async void OnPhoneWakeToggled(object sender, RoutedEventArgs e)
    {
        if (_loadingLayout) return;
        App.Session.SetAllowPhoneWake(PhoneWakeSwitch.IsOn);
        await Task.Delay(1500); // let Bluetooth start, then say if it couldn't
        UpdatePhoneWakeStatus();
    }

    private void UpdatePhoneWakeStatus()
    {
        var unsupported = App.Session.Settings.AllowPhoneWake && App.Session.IPhoneBtState == Pixlink.Services.IPhoneBluetooth.LinkState.NotSupported;
        PhoneWakeStatus.Text = "This PC's Bluetooth can't do this (it needs Bluetooth 4.0+ with LE peripheral support). A USB Bluetooth adapter fixes it.";
        PhoneWakeStatus.Visibility = unsupported ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void OnChooseQuickActions(object sender, RoutedEventArgs e)
    {
        var st = App.Session.Settings;
        var panel = new StackPanel { Spacing = 4, Width = 360 };
        panel.Children.Add(new TextBlock
        {
            Text = "Pick up to 9. The ones marked Power mode need Power mode on your phone.",
            TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 8),
        });
        var boxes = new List<(string Id, CheckBox Box)>();
        foreach (var c in Pixlink.Models.QuickActionItem.Catalog)
        {
            var box = new CheckBox { Content = c.NeedsPower ? $"{c.Name} (Power mode)" : c.Name, IsChecked = st.QuickActions.Contains(c.Id) };
            boxes.Add((c.Id, box));
            panel.Children.Add(box);
        }
        var result = await new ContentDialog
        {
            XamlRoot = XamlRoot, Title = "Quick actions", Content = new ScrollViewer { Content = panel, MaxHeight = 460 },
            PrimaryButtonText = "Save", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Primary,
        }.ShowAsync();
        if (result != ContentDialogResult.Primary) return;
        // Keep the existing order, then add newly ticked ones at the end.
        var chosen = boxes.Where(b => b.Box.IsChecked == true).Select(b => b.Id).ToList();
        st.QuickActions = st.QuickActions.Where(chosen.Contains).Concat(chosen.Where(id => !st.QuickActions.Contains(id))).Take(9).ToList();
        st.Save();
        App.Session.RebuildQuickActions();
        LoadLayout();
    }

    private void OnViewLogs(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(LogsPage));

    private void OnOpenUpdates(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(UpdatesPage));

    private void OnRunSetup(object sender, RoutedEventArgs e)
    {
        App.Session.Settings.SetupDone = false;
        App.Session.Settings.Save();
        App.Window?.RunSetup();
    }
}
