using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Pixlink.Services;

namespace Pixlink.Pages;

/// <summary>First-run choices after linking: startup, tray, pop-ups, clipboard, grouping and theme.</summary>
public sealed partial class SetupPage : Page
{
    public SetupPage()
    {
        InitializeComponent();
        var s = App.Session.Settings;
        Startup.IsOn = StartupTask.IsEnabled || !s.SetupDone;
        Tray.IsOn = s.CloseToTray;
        Toasts.IsOn = s.ShowToasts;
        Sound.IsOn = s.ToastSound;
        Clip.IsOn = s.AutoClipboard;
        Codes.IsOn = s.CopyCodes;
        Group.IsOn = s.GroupNotificationsByApp;
        Theme.SelectedIndex = s.Theme switch { "Light" => 1, "Dark" => 2, _ => 0 };
    }

    private void OnDone(object sender, RoutedEventArgs e)
    {
        var s = App.Session.Settings;
        StartupTask.Set(Startup.IsOn);
        s.StartWithWindows = Startup.IsOn;
        s.CloseToTray = Tray.IsOn;
        s.ShowToasts = Toasts.IsOn;
        s.ToastSound = Sound.IsOn;
        s.AutoClipboard = Clip.IsOn;
        s.CopyCodes = Codes.IsOn;
        s.GroupNotificationsByApp = Group.IsOn;
        s.Theme = Theme.SelectedIndex switch { 1 => "Light", 2 => "Dark", _ => "System" };
        s.Save();
        App.Window?.ApplyTheme();
        App.Window?.FinishSetup();
        Log.Info("Setup finished");
    }
}
