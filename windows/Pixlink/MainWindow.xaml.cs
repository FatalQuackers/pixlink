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
        AppWindow.SetIcon("Assets/Pixlink.ico");
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1240, 820));

        ContentFrame.Navigate(typeof(MessagesPage));
        PairingFrame.Navigate(typeof(PairingPage));

        Session.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(PhoneSession.IsPaired) && !Session.IsPaired)
                PairingFrame.Navigate(typeof(PairingPage));
        };
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
        Tabs.SelectedItem = null;
        ContentFrame.Navigate(typeof(SettingsPage));
    }

    private void OnScreen(object sender, RoutedEventArgs e) => SelectTab("Screen");

    private async void OnHotspot(object sender, RoutedEventArgs e)
    {
        var on = HotspotButton.IsChecked == true;
        await Session.SetHotspotAsync(on);
        if (on && !Session.HotspotToggleSupported)
        {
            await new ContentDialog
            {
                XamlRoot = Content.XamlRoot,
                Title = "Turn on hotspot on your phone",
                Content = "Your phone opened its hotspot settings — flip the switch there. " +
                          "Turn on Power mode in the Pixlink phone app to control the hotspot from your PC.",
                CloseButtonText = "OK",
            }.ShowAsync();
        }
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
