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

        ContentFrame.Navigate(typeof(HomePage));
        PairingFrame.Navigate(typeof(PairingPage));

        ApplyTheme();
        _ = CheckForUpdatesAsync();
        Session.JustPaired += () => DispatcherQueue.TryEnqueue(() =>
        {
            ShowLinkedAnimation();
            ShowSetupIfNeeded();
            if (Session.IsIPhone)
                ShowConnection(InfoBarSeverity.Informational,
                    "iPhone linked. Photos, files, clipboard and screen casting work over Wi-Fi, and notifications and call alerts come over Bluetooth. " +
                    "Apple doesn't let other devices reply to messages, read texts, carry call audio or control an iPhone, so those stay Pixel-only.");
        });
        Session.Reconnected += () => DispatcherQueue.TryEnqueue(() => ShowConnection(InfoBarSeverity.Success, $"Reconnected to {Session.PhoneName}"));

        Session.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(PhoneSession.IsPaired) && !Session.IsPaired)
                PairingFrame.Navigate(typeof(PairingPage));
            if (e.PropertyName == nameof(PhoneSession.Phase) && Session.Phase == PhoneSession.LinkPhase.Disconnected)
                ShowConnection(InfoBarSeverity.Warning, $"{Session.PhoneName} is disconnected. Check it's on the same Wi-Fi with Pixlink open.");
            if (e.PropertyName == nameof(PhoneSession.Phase) && Session.Phase == PhoneSession.LinkPhase.Connected && ConnectionBar.Severity == InfoBarSeverity.Warning)
                ConnectionBar.IsOpen = false;
            if (e.PropertyName is nameof(PhoneSession.IsIPhone) or nameof(PhoneSession.IPhoneBtState)) ApplyPlatform();
        };
        // An iPhone starts casting from its own Control Center: jump to the Screen tab to show it.
        Session.Mirror.SizeAnnounced += (_, _) => DispatcherQueue.TryEnqueue(() => SelectTab("Screen", "cast"));
        ApplyPlatform();
        Session.Notifications.CollectionChanged += (_, _) => UpdateNotificationView();
        UpdateNotificationView();

        AppWindow.Closing += OnClosing;
        AppWindow.Changed += (_, a) => { if (a.DidPresenterChange || a.DidVisibilityChange) RaiseHidden(); };
        VisibilityChanged += (_, _) => RaiseHidden();
        Tray.Create(this);
        Activated += OnFirstActivated;
        ShowSetupIfNeeded();
    }

    // ---------- Splash ----------
    private bool _splashDone;
    private async void OnFirstActivated(object sender, WindowActivatedEventArgs e)
    {
        if (_splashDone) return;
        _splashDone = true;
        if (Ui.AnimationsEnabled) ((Microsoft.UI.Xaml.Media.Animation.Storyboard)Splash.Resources["SplashIn"]).Begin();
        await Task.Delay(Environment.GetCommandLineArgs().Contains("--background") ? 0 : 1300);
        var outro = (Microsoft.UI.Xaml.Media.Animation.Storyboard)Splash.Resources["SplashOut"];
        outro.Completed += (_, _) => Splash.Visibility = Visibility.Collapsed;
        outro.Begin();
    }

    // ---------- Minimized / hidden (screen sharing stops, tray) ----------
    /// <summary>True while the window is minimized or hidden in the tray.</summary>
    public event Action<bool>? HiddenChanged;
    private bool _hidden;
    public bool IsHidden => !AppWindow.IsVisible || (AppWindow.Presenter is OverlappedPresenter p && p.State == OverlappedPresenterState.Minimized);
    private void RaiseHidden()
    {
        var h = IsHidden;
        if (h == _hidden) return;
        _hidden = h;
        HiddenChanged?.Invoke(h);
    }

    private bool _quitting, _trayHintShown;
    private void OnClosing(AppWindow sender, AppWindowClosingEventArgs e)
    {
        if (_quitting || !Session.Settings.CloseToTray || !Session.IsPaired) { Tray.Dispose(); return; }
        e.Cancel = true;
        AppWindow.Hide();
        RaiseHidden();
        if (!_trayHintShown)
        {
            _trayHintShown = true;
            Toasts.ShowSimple("Pixlink is still running", "It's in the system tray by the clock, so notifications keep coming. Right-click the icon to quit.");
        }
    }

    public void Quit()
    {
        _quitting = true;
        Tray.Dispose();
        Application.Current.Exit();
    }

    // ---------- Setup after linking ----------
    private void ShowSetupIfNeeded()
    {
        if (!Session.IsPaired || Session.Settings.SetupDone) { SetupFrame.Visibility = Visibility.Collapsed; return; }
        SetupFrame.Navigate(typeof(SetupPage));
        SetupFrame.Visibility = Visibility.Visible;
    }

    public void FinishSetup()
    {
        Session.Settings.SetupDone = true;
        Session.Settings.Save();
        SetupFrame.Visibility = Visibility.Collapsed;
        Session.RebuildQuickActions();
        UpdateNotificationView();
    }

    public void RefreshLayout() => UpdateNotificationView();

    public void RunSetup()
    {
        SetupFrame.Navigate(typeof(SetupPage));
        SetupFrame.Visibility = Visibility.Visible;
    }

    private void ShowConnection(InfoBarSeverity severity, string message)
    {
        ConnectionBar.Severity = severity;
        ConnectionBar.Title = "";
        ConnectionBar.Message = message;
        ConnectionBar.IsOpen = true;
        if (severity == InfoBarSeverity.Success)
            _ = Task.Delay(4000).ContinueWith(_ => DispatcherQueue.TryEnqueue(() => { if (ConnectionBar.Severity == InfoBarSeverity.Success) ConnectionBar.IsOpen = false; }));
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
        if (!await RunUpdateAsync(update, automatic: true)) ShowUpdate(update);
    }

    // ---------- Updating screen ----------
    /// <summary>Shows the updating screen and installs. Returns false if it failed (Pixlink restarts on success).</summary>
    public async Task<bool> RunUpdateAsync(Updater.Update update, bool automatic = false)
    {
        UpdateVersions.Text = $"{Updater.Current}  →  {update.Version}";
        UpdatePercent.Text = "0%";
        UpdateRing.Value = 0;
        UpdateNote.Text = "Pixlink restarts by itself when it's done. Your phone reconnects automatically.";
        UpdateCancel.Visibility = Visibility.Collapsed;
        SetUpdateStage(Updater.Stage.Downloading);
        UpdatingOverlay.Visibility = Visibility.Visible;
        if (Ui.AnimationsEnabled)
        {
            ((Microsoft.UI.Xaml.Media.Animation.Storyboard)UpdatingOverlay.Resources["UpdatingIn"]).Begin();
            ((Microsoft.UI.Xaml.Media.Animation.Storyboard)UpdatingOverlay.Resources["UpdatingLoop"]).Begin();
        }
        if (automatic && IsHidden) Toasts.ShowSimple("Updating Pixlink", $"Installing {update.Version}. It restarts by itself in a moment.");
        try
        {
            var progress = new Progress<double>(p =>
            {
                UpdateRing.Value = p * 100;
                UpdatePercent.Text = $"{p:P0}";
            });
            await Updater.InstallAsync(update, progress, stage => DispatcherQueue.TryEnqueue(() => SetUpdateStage(stage)));
            return true; // not reached: Pixlink exits to restart
        }
        catch (Exception ex)
        {
            Crash.Log(ex, automatic ? "Automatic update" : "Update");
            UpdatePercent.Text = "Didn't finish";
            UpdateNote.Text = $"The update couldn't install ({ex.Message}). Pixlink keeps working; try again from Settings → Updates, or download it from GitHub.";
            UpdateCancel.Visibility = Visibility.Visible;
            return false;
        }
    }

    private void SetUpdateStage(Updater.Stage stage)
    {
        void Paint(Border chip, FontIcon icon, string glyph, int order)
        {
            var current = (int)stage;
            var done = order < current;
            var active = order == current;
            chip.Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources[active ? "AccentFillColorDefaultBrush" : done ? "SystemFillColorSuccessBackgroundBrush" : "ControlFillColorDefaultBrush"];
            chip.RequestedTheme = active ? ElementTheme.Dark : ElementTheme.Default;
            icon.Glyph = done ? "\uE73E" : glyph;
        }
        Paint(StepDownload, StepDownloadIcon, "\uE896", 0);
        Paint(StepInstall, StepInstallIcon, "\uE912", 1);
        Paint(StepRestart, StepRestartIcon, "\uE777", 2);
        if (stage == Updater.Stage.Installing) { UpdateRing.IsIndeterminate = true; UpdatePercent.Text = "Installing…"; }
        if (stage == Updater.Stage.Restarting) { UpdateRing.IsIndeterminate = false; UpdateRing.Value = 100; UpdatePercent.Text = "Restarting…"; }
        if (stage == Updater.Stage.Downloading) UpdateRing.IsIndeterminate = false;
    }

    private void OnHideUpdating(object sender, RoutedEventArgs e)
    {
        UpdatingOverlay.Visibility = Visibility.Collapsed;
        ((Microsoft.UI.Xaml.Media.Animation.Storyboard)UpdatingOverlay.Resources["UpdatingLoop"]).Stop();
    }

    private void OnWhatsNew(object sender, RoutedEventArgs e) => ShowUpdatesPage();

    public void ShowUpdatesPage()
    {
        SelectTab("Settings");
        ContentFrame.Navigate(typeof(UpdatesPage));
    }

    public Updater.Update? PendingUpdate => _update;

    public void ShowUpdate(Updater.Update update)
    {
        _update = update;
        UpdateBar.Message = $"Pixlink {update.Version} is ready (you have {Updater.Current}).";
        UpdateBar.IsOpen = true;
    }

    private async void OnUpdateNow(object sender, RoutedEventArgs e)
    {
        if (_update is null) return;
        UpdateBar.IsOpen = false;
        await RunUpdateAsync(_update);
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
        if (!AppWindow.IsVisible) AppWindow.Show();
        if (AppWindow.Presenter is OverlappedPresenter p && p.State == OverlappedPresenterState.Minimized) p.Restore();
        Activate();
        RaiseHidden();
    }

    private void OnTabChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (ContentFrame is null || sender.SelectedItem is null) return; // fires during InitializeComponent
        ContentFrame.Navigate(PageFor(sender.SelectedItem.Tag as string));
    }

    private static Type PageFor(string? tag) => tag switch
    {
        "Messages" => typeof(MessagesPage),
        "Calls" => typeof(CallsPage),
        "Photos" => typeof(PhotosPage),
        "Files" => typeof(FilesPage),
        "Screen" => typeof(ScreenPage),
        "Settings" => typeof(SettingsPage),
        _ => typeof(HomePage),
    };

    /// <summary>Switches tab; picking the tab you're already on still reloads it (e.g. to leave a sub-page).</summary>
    public void SelectTab(string tag, object? parameter = null)
    {
        foreach (var item in Tabs.Items)
        {
            if ((item.Tag as string) != tag) continue;
            if (Tabs.SelectedItem == item) ContentFrame.Navigate(PageFor(tag), parameter);
            else if (parameter is null) Tabs.SelectedItem = item;
            else { Tabs.SelectionChanged -= OnTabChanged; Tabs.SelectedItem = item; Tabs.SelectionChanged += OnTabChanged; ContentFrame.Navigate(PageFor(tag), parameter); }
        }
    }

    /// <summary>iPhones don't let apps read texts or handle calls, so hide those tabs. Files shows Pixlink's own folder.</summary>
    private void ApplyPlatform()
    {
        var iphone = Session.IsIPhone;
        MessagesTab.Visibility = CallsTab.Visibility = iphone ? Visibility.Collapsed : Visibility.Visible;
        if (iphone && Tabs.SelectedItem is { Visibility: Visibility.Collapsed }) SelectTab("Home");
        NoNotificationsText.Text = !iphone ? "You're all caught up" : Session.IPhoneBtState switch
        {
            IPhoneBluetooth.LinkState.Connected => "You're all caught up",
            IPhoneBluetooth.LinkState.NeedsPermission => "Almost there. On your iPhone, open Settings → Bluetooth, tap (i) next to this PC and turn on Share System Notifications.",
            IPhoneBluetooth.LinkState.NotSupported => "This PC's Bluetooth can't receive iPhone notifications. A Bluetooth 4.0+ USB adapter fixes that. Photos, files, clipboard and casting still work.",
            _ => "To see your iPhone's notifications here, open Pixlink on your iPhone and tap Set up notifications. Accept the pairing request on both.",
        };
    }

    // ---------- Notifications list ----------
    private void UpdateNotificationView()
    {
        var grouped = Session.Settings.GroupNotificationsByApp;
        GroupToggle.IsChecked = grouped;
        FlatList.Visibility = grouped ? Visibility.Collapsed : Visibility.Visible;
        GroupedList.Visibility = grouped ? Visibility.Visible : Visibility.Collapsed;
        NoNotifications.Visibility = Session.Notifications.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (grouped) Session.RebuildGroups();
    }

    private void OnGroupToggle(object sender, RoutedEventArgs e)
    {
        Session.Settings.GroupNotificationsByApp = GroupToggle.IsChecked == true;
        Session.Settings.Save();
        UpdateNotificationView();
    }

    private void OnToggleGroup(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is NotificationGroup g) g.IsExpanded = !g.IsExpanded;
    }

    private void OnPinNotification(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not PhoneNotification n) return;
        Session.TogglePin(n);
        ShowHotspot(InfoBarSeverity.Success, Session.PinnedNotifications.Contains(n) ? "Pinned to Home." : "Unpinned from Home.");
    }

    // ---------- Quick actions ----------
    private bool _hotspotBusy, _hotspotOn, _ringing;

    private async void OnQuickAction(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not QuickActionItem q) return;
        if (!Session.IsConnected && q.Id is not ("photos" or "messages"))
        {
            ShowHotspot(InfoBarSeverity.Warning, $"Connect {Session.PhoneName} first. It connects by itself when it's on the same Wi-Fi with Pixlink open.");
            return;
        }
        if (q.NeedsPower && !Session.PowerMode)
        {
            ShowHotspot(InfoBarSeverity.Warning, $"{q.Name} needs Power mode. Open Pixlink on your phone and tap Power mode to turn it on.");
            return;
        }
        switch (q.Id)
        {
            case "hotspot": await ToggleHotspotAsync(q); break;
            case "screen": SelectTab("Screen"); break;
            case "photos": SelectTab("Photos"); break;
            case "messages": SelectTab("Messages"); break;
            case "sendfile": await SendFilesAsync(); break;
            case "ring":
                _ringing = !_ringing;
                await Session.RingPhoneAsync(_ringing);
                q.Label = _ringing ? "Stop ringing" : q.Name; q.IsActive = _ringing;
                if (_ringing)
                {
                    await Task.Delay(30_000); // the phone stops by itself after 30 seconds
                    _ringing = false; q.Label = q.Name; q.IsActive = false;
                }
                break;
            case "clipboard":
                var sent = await Session.SendClipboardAsync();
                q.Label = sent ? "Sent" : "Nothing copied";
                await Task.Delay(1500);
                q.Label = q.Name;
                break;
            case "dnd": _ = Session.SetDndAsync(!Session.DoNotDisturb); break;
            case "lock":
                await Session.SendAsync(Envelope.Of("power.lock"));
                ShowHotspot(InfoBarSeverity.Success, "Phone locked.");
                break;
            case "screenshot":
                ShowHotspot(InfoBarSeverity.Informational, "Taking a screenshot on your phone…");
                var r = await Session.RequestAsync("power.screenshot", null, TimeSpan.FromSeconds(20));
                ShowHotspot(r?.Bool("ok") == true ? InfoBarSeverity.Success : InfoBarSeverity.Error,
                    r?.Bool("ok") == true ? "Screenshot saved to your phone's Screenshots folder. It'll appear in Photos." : "Couldn't take the screenshot.");
                break;
        }
    }

    /// <summary>One button: turn on the phone's hotspot and connect this PC to it.</summary>
    private async Task ToggleHotspotAsync(QuickActionItem q)
    {
        if (_hotspotBusy) return;
        var on = !_hotspotOn;
        if (on && string.IsNullOrEmpty(Session.HotspotSsid) && !await AskHotspotDetailsAsync()) return;

        _hotspotBusy = true;
        q.IsActive = on;
        ShowHotspot(InfoBarSeverity.Informational, on ? "Turning on your phone's hotspot…" : "Turning off hotspot…");
        try
        {
            var progress = new Progress<string>(msg => ShowHotspot(InfoBarSeverity.Informational, msg));
            var outcome = await Session.SetHotspotAsync(on, progress);
            switch (outcome)
            {
                case PhoneSession.HotspotOutcome.Joined:
                    _hotspotOn = true;
                    ShowHotspot(InfoBarSeverity.Success, $"Connected to {Session.HotspotSsid}. Your PC is using your phone's internet.");
                    break;
                case PhoneSession.HotspotOutcome.TurnedOff:
                    _hotspotOn = false;
                    ShowHotspot(InfoBarSeverity.Informational, "Hotspot off. Windows will reconnect to your usual Wi-Fi.");
                    break;
                case PhoneSession.HotspotOutcome.PhoneSwitchedOnJoinFailed:
                    _hotspotOn = true;
                    ShowHotspot(InfoBarSeverity.Warning, $"The hotspot is on, but this PC couldn't join \"{Session.HotspotSsid}\". Check the name and password in Settings.");
                    break;
                case PhoneSession.HotspotOutcome.TurnOnOnPhone:
                    _hotspotOn = false;
                    ShowHotspot(InfoBarSeverity.Warning, "Couldn't find the hotspot. Switch it on from the Pixlink notification on your phone, then click Hotspot again. Turn on Power mode on the phone to skip this step.");
                    break;
                default:
                    _hotspotOn = false;
                    ShowHotspot(InfoBarSeverity.Error, "Your phone didn't respond. Check it's connected.");
                    break;
            }
        }
        finally { _hotspotBusy = false; q.IsActive = _hotspotOn; }
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

    private async Task SendFilesAsync()
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
        var files = await picker.PickMultipleFilesAsync();
        foreach (var f in files) await Session.Files.UploadAsync(f.Path);
    }

    private void OnClearAll(object sender, RoutedEventArgs e) => _ = Session.DismissAllAsync();

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

    private void OnToggleExpand(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is PhoneNotification n) n.IsExpanded = !n.IsExpanded;
    }

    private void OnCardTapped(object sender, Microsoft.UI.Xaml.Input.TappedRoutedEventArgs e)
    {
        if (e.OriginalSource is Microsoft.UI.Xaml.Documents.Hyperlink) return;
        if ((sender as FrameworkElement)?.Tag is PhoneNotification n && n.HasMore) n.IsExpanded = !n.IsExpanded;
    }

    /// <summary>An app button (Mark as read, Like…) pressed on the phone; "Open on phone" opens the app and shows the phone screen.</summary>
    private void OnNotifAction(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is not NotifAction a) return;
        if (a.Semantic == "open") OpenOnPhone(a.Key);
        else _ = Session.ActionAsync(a);
    }

    public async void OpenOnPhone(string key)
    {
        BringToFront();
        await Session.OpenNotificationAsync(key);
        if (Session.OnBluetooth) { ShowHotspot(InfoBarSeverity.Informational, "Opened on your phone. Showing its screen here needs Wi-Fi."); return; }
        if (Session.Mirror.IsStreaming) { SelectTab("Screen"); return; }
        SelectTab("Screen", "starting");
        var s = Session.Settings;
        await Session.SendAsync(Envelope.Of("mirror.start", new { maxSize = s.MirrorMaxSize, bitrate = s.MirrorBitrateMbps * 1_000_000, fps = s.MirrorFps }));
    }

    private void OnNotifReply(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is PhoneNotification n) _ = Session.ReplyAsync(n);
    }

    private void OnNotifReplyKeyDown(object sender, Microsoft.UI.Xaml.Input.KeyRoutedEventArgs e)
    {
        if (e.Key != Windows.System.VirtualKey.Enter || (sender as FrameworkElement)?.Tag is not PhoneNotification n) return;
        if (sender is TextBox box) n.ReplyDraft = box.Text; // Enter can arrive before the binding updates
        _ = Session.ReplyAsync(n);
        e.Handled = true;
    }

    /// <summary>Opens Pixlink with one notification expanded (clicking a Windows pop-up).</summary>
    public void ShowNotification(string key)
    {
        BringToFront();
        var n = Session.Notifications.FirstOrDefault(x => x.Key == key);
        if (n is not null && n.HasMore) n.IsExpanded = true;
    }
}
