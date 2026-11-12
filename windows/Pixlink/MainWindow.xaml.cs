using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Pixlink.Models;
using Pixlink.Pages;
using Pixlink.Services;
using Windows.Storage.Pickers;

namespace Pixlink;

public sealed partial class MainWindow : Window
{
    public PhoneSession Session => App.Session;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "Pixlink.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1240, 820));

        ContentFrame.Navigate(typeof(MessagesPage));
        PairingFrame.Navigate(typeof(PairingPage));

        ApplyTheme();
        _ = CheckForUpdatesAsync();
        Session.JustPaired += () => DispatcherQueue.TryEnqueue(ShowLinkedAnimation);

        Session.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(PhoneSession.IsPaired) && !Session.IsPaired)
                PairingFrame.Navigate(typeof(PairingPage));
        };
    }

    /// <summary>Light, Dark or follow Windows (from Settings).</summary>
    public void ApplyTheme()
    {
        if (Content is FrameworkElement root)
        {
            root.RequestedTheme = Session.Settings.Theme switch
            {
                "Light" => ElementTheme.Light,
                "Dark" => ElementTheme.Dark,
                _ => ElementTheme.Default,
            };
        }
    }

    private Updater.Update? _update;

    private DispatcherTimer? _updateTimer;

    /// <summary>
    /// Automatic updates: check a few seconds after start and every 6 hours. With automatic
    /// updates on, a new version installs by itself (and Pixlink restarts) as soon as you're not
    /// on a call or mirroring your screen. With it off, a banner offers Update now instead.
    /// </summary>
    public async Task CheckForUpdatesAsync(bool quiet = true)
    {
        if (_updateTimer is null)
        {
            _updateTimer = new DispatcherTimer { Interval = TimeSpan.FromHours(6) };
            _updateTimer.Tick += (_, _) => { if (Session.Settings.AutoCheckUpdates) _ = CheckForUpdatesAsync(); };
            _updateTimer.Start();
        }
        try
        {
            if (quiet) await Task.Delay(TimeSpan.FromSeconds(5));
            _update = await Updater.CheckAsync();
            if (_update is null) return;
            if (Session.Settings.AutoCheckUpdates) await InstallWhenIdleAsync(_update);
            else ShowUpdate(_update);
        }
        catch (Exception ex) when (quiet) { Crash.Log(ex, "Update check"); }
    }

    private bool Busy => Session.CallState != "ended" || Session.Mirror.IsStreaming;

    private async Task InstallWhenIdleAsync(Updater.Update update)
    {
        // Don't cut off a call or a screen-mirroring session; check again every minute.
        while (Busy) await Task.Delay(TimeSpan.FromMinutes(1));
        _update = update;
        UpdateBar.Title = "Updating Pixlink";
        UpdateBar.IsOpen = true;
        UpdateNowButton.Visibility = Visibility.Collapsed;
        try
        {
            var progress = new Progress<double>(p => UpdateBar.Message = $"Installing Pixlink {update.Version}… {p:P0}. Pixlink will restart by itself.");
            await Updater.InstallAsync(update, progress); // closes and restarts Pixlink
        }
        catch (Exception ex)
        {
            // Fall back to the manual button if the automatic install fails (for example, no write access to the folder).
            Crash.Log(ex, "Automatic update");
            UpdateBar.Title = "Update available";
            UpdateNowButton.Visibility = Visibility.Visible;
            ShowUpdate(update);
        }
    }

    public void ShowUpdate(Updater.Update update)
    {
        _update = update;
        UpdateBar.Message = $"Pixlink {update.Version} is ready (you have {Updater.Current}).";
        UpdateBar.IsOpen = true;
    }

    private async void OnUpdateNow(object sender, RoutedEventArgs e)
    {
        if (_update is null) return;
        UpdateNowButton.IsEnabled = false;
        try
        {
            var progress = new Progress<double>(p => UpdateBar.Message = $"Downloading Pixlink {_update.Version}… {p:P0}");
            await Updater.InstallAsync(_update, progress); // closes and restarts Pixlink
        }
        catch (Exception ex)
        {
            Crash.Log(ex);
            UpdateBar.Severity = InfoBarSeverity.Error;
            UpdateBar.Message = $"Update failed: {ex.Message}. You can download it from GitHub instead.";
            UpdateNowButton.IsEnabled = true;
        }
    }

    private async void ShowLinkedAnimation()
    {
        SuccessText.Text = $"{Session.PhoneName} is connected to this PC";
        SuccessOverlay.Visibility = Visibility.Visible;
        if (Ui.AnimationsEnabled) ((Microsoft.UI.Xaml.Media.Animation.Storyboard)SuccessOverlay.Resources["SuccessIn"]).Begin();
        else SuccessCheck.Opacity = 1;
        await Task.Delay(2200);
        var outro = (Microsoft.UI.Xaml.Media.Animation.Storyboard)SuccessOverlay.Resources["SuccessOut"];
        outro.Completed += (_, _) => { SuccessOverlay.Visibility = Visibility.Collapsed; SuccessOverlay.Opacity = 1; };
        outro.Begin();
    }

    public void BringToFront()
    {
        if (AppWindow.Presenter is OverlappedPresenter p && p.State == OverlappedPresenterState.Minimized) p.Restore();
        Activate();
    }

    private void OnTabChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (ContentFrame is null || sender.SelectedItem is null) return; // fires during InitializeComponent
        var page = (sender.SelectedItem?.Tag as string) switch
        {
            "Calls" => typeof(CallsPage),
            "Photos" => typeof(PhotosPage),
            "Files" => typeof(FilesPage),
            "Screen" => typeof(ScreenPage),
            _ => typeof(MessagesPage),
        };
        ContentFrame.Navigate(page);
    }

    private void SelectTab(string tag)
    {
        foreach (var item in Tabs.Items)
            if ((item.Tag as string) == tag) Tabs.SelectedItem = item;
    }

    private void OnSettings(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(SettingsPage));
    }

    private void OnScreen(object sender, RoutedEventArgs e) => SelectTab("Screen");

    private bool _hotspotBusy;

    /// <summary>One button: turn on the phone's hotspot and connect this PC to it.</summary>
    private async void OnHotspot(object sender, RoutedEventArgs e)
    {
        var on = HotspotButton.IsChecked == true;
        if (_hotspotBusy) { HotspotButton.IsChecked = !on; return; }

        if (on && string.IsNullOrEmpty(Session.HotspotSsid))
        {
            if (!await AskHotspotDetailsAsync()) { HotspotButton.IsChecked = false; return; }
        }

        _hotspotBusy = true;
        ShowHotspot(InfoBarSeverity.Informational, on ? "Turning on your phone's hotspot…" : "Turning off hotspot…");
        try
        {
            var progress = new Progress<string>(msg => ShowHotspot(InfoBarSeverity.Informational, msg));
            var outcome = await Session.SetHotspotAsync(on, progress);
            switch (outcome)
            {
                case PhoneSession.HotspotOutcome.Joined:
                    ShowHotspot(InfoBarSeverity.Success, $"Connected to {Session.HotspotSsid}. Your PC is using your phone's internet.");
                    break;
                case PhoneSession.HotspotOutcome.TurnedOff:
                    ShowHotspot(InfoBarSeverity.Informational, "Hotspot off. Windows will reconnect to your usual Wi-Fi.");
                    break;
                case PhoneSession.HotspotOutcome.PhoneSwitchedOnJoinFailed:
                    ShowHotspot(InfoBarSeverity.Warning, $"The hotspot is on, but this PC couldn't join \"{Session.HotspotSsid}\". Check the name and password in Settings.");
                    break;
                case PhoneSession.HotspotOutcome.TurnOnOnPhone:
                    ShowHotspot(InfoBarSeverity.Warning, "Turn on the hotspot on your phone, then click Hotspot again. Turn on Power mode to skip this step.");
                    HotspotButton.IsChecked = false;
                    break;
                default:
                    ShowHotspot(InfoBarSeverity.Error, "Your phone didn't respond. Check it's connected.");
                    HotspotButton.IsChecked = false;
                    break;
            }
        }
        finally { _hotspotBusy = false; }
    }

    private void ShowHotspot(InfoBarSeverity severity, string message)
    {
        HotspotBar.Severity = severity;
        HotspotBar.Message = message;
        HotspotBar.IsOpen = true;
    }

    /// <summary>Asks once for the phone's hotspot name and password; the phone remembers them.</summary>
    public async Task<bool> AskHotspotDetailsAsync()
    {
        var name = new TextBox { Header = "Hotspot name", PlaceholderText = "e.g. Pixel 9", Text = Session.HotspotSsid ?? "" };
        var pass = new PasswordBox { Header = "Hotspot password", Password = Session.HotspotPassword ?? "" };
        var panel = new StackPanel { Spacing = 12, Width = 340 };
        panel.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = "On your phone, open Settings → Network & internet → Hotspot & tethering → Wi-Fi hotspot to see these. You only need to do this once.",
        });
        panel.Children.Add(name);
        panel.Children.Add(pass);

        var result = await new ContentDialog
        {
            XamlRoot = Content.XamlRoot,
            Title = "Your phone's hotspot",
            Content = panel,
            PrimaryButtonText = "Save",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        }.ShowAsync();

        if (result != ContentDialogResult.Primary || string.IsNullOrWhiteSpace(name.Text)) return false;
        await Session.SaveHotspotConfigAsync(name.Text.Trim(), pass.Password);
        return true;
    }

    private async void OnSendFile(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
        var files = await picker.PickMultipleFilesAsync();
        foreach (var f in files) await Session.Files.UploadAsync(f.Path);
    }

    private void OnClearAll(object sender, RoutedEventArgs e) => _ = Session.DismissAllAsync();

    private bool _ringing;

    private async void OnRing(object sender, RoutedEventArgs e)
    {
        _ringing = !_ringing;
        await Session.RingPhoneAsync(_ringing);
        RingLabel.Text = _ringing ? "Stop ringing" : "Find phone";
        if (_ringing)
        {
            await Task.Delay(30_000); // the phone stops by itself after 30 seconds
            _ringing = false;
            RingLabel.Text = "Find phone";
        }
    }

    private async void OnSendClipboard(object sender, RoutedEventArgs e)
    {
        var sent = await Session.SendClipboardAsync();
        ClipboardLabel.Text = sent ? "Sent" : "Nothing copied";
        await Task.Delay(1500);
        ClipboardLabel.Text = "Clipboard";
    }

    private void OnDnd(object sender, RoutedEventArgs e)
    {
        if (sender is Microsoft.UI.Xaml.Controls.Primitives.ToggleButton t) _ = Session.SetDndAsync(t.IsChecked == true);
    }

    private void OnMediaPrevious(object sender, RoutedEventArgs e) => _ = Session.MediaActionAsync("previous");
    private void OnMediaToggle(object sender, RoutedEventArgs e) => _ = Session.MediaActionAsync("toggle");
    private void OnMediaNext(object sender, RoutedEventArgs e) => _ = Session.MediaActionAsync("next");

    private void OnDismiss(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is PhoneNotification n)
        {
            _ = Session.DismissAsync(n);
            Session.Notifications.Remove(n);
        }
    }
}
