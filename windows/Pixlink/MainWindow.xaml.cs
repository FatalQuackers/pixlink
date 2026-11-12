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

        _ = CheckForUpdatesAsync();

        Session.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(PhoneSession.IsPaired) && !Session.IsPaired)
                PairingFrame.Navigate(typeof(PairingPage));
        };
    }

    private Updater.Update? _update;

    /// <summary>Quietly checks GitHub a few seconds after start; shows a banner if there's a newer version.</summary>
    public async Task CheckForUpdatesAsync(bool quiet = true)
    {
        try
        {
            if (quiet) await Task.Delay(TimeSpan.FromSeconds(5));
            _update = await Updater.CheckAsync();
            if (_update is not null) ShowUpdate(_update);
        }
        catch (Exception ex) when (quiet) { Crash.Log(ex); }
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

    private void OnDismiss(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is PhoneNotification n)
        {
            _ = Session.DismissAsync(n);
            Session.Notifications.Remove(n);
        }
    }
}
