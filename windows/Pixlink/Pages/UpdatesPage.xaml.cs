using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Navigation;
using Pixlink.Services;

namespace Pixlink.Pages;

/// <summary>Updates: whether you're up to date, install with one click, and what changed in each version.</summary>
public sealed partial class UpdatesPage : Page
{
    private Updater.Update? _update;

    public UpdatesPage()
    {
        InitializeComponent();
        AutoSwitch.IsOn = App.Session.Settings.AutoCheckUpdates;
        EarlySwitch.IsOn = App.Session.Settings.EarlyUpdates;
        _ready = true;
    }

    private bool _ready;

    private void OnEarlyToggled(object sender, RoutedEventArgs e)
    {
        if (!_ready) return;
        App.Session.Settings.EarlyUpdates = EarlySwitch.IsOn;
        App.Session.Settings.Save();
        _ = CheckAsync();
        _ = LoadNotesAsync();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _update = App.Window?.PendingUpdate;
        if (_update is not null) ShowAvailable(_update); else _ = CheckAsync();
        _ = LoadNotesAsync();
    }

    private enum Look { Checking, UpToDate, Available, Error }

    private void SetLook(Look look)
    {
        foreach (var name in new[] { "Spin", "Bounce" }) ((Storyboard)Resources[name]).Stop();
        SpinIcon.Visibility = look == Look.Checking ? Visibility.Visible : Visibility.Collapsed;
        ArrowIcon.Visibility = look == Look.Available ? Visibility.Visible : Visibility.Collapsed;
        CheckIcon.Visibility = look == Look.UpToDate ? Visibility.Visible : Visibility.Collapsed;
        ErrorIcon.Visibility = look == Look.Error ? Visibility.Visible : Visibility.Collapsed;
        StatusDisc.Fill = look switch
        {
            Look.UpToDate => new SolidColorBrush(Windows.UI.Color.FromArgb(255, 0x1E, 0x9E, 0x63)),
            Look.Available => (Brush)Application.Current.Resources["AccentFillColorDefaultBrush"],
            _ => (Brush)Application.Current.Resources["ControlFillColorDefaultBrush"],
        };
        if (!Ui.AnimationsEnabled) return;
        if (look == Look.Checking) ((Storyboard)Resources["Spin"]).Begin();
        if (look == Look.Available) ((Storyboard)Resources["Bounce"]).Begin();
        if (look == Look.UpToDate) ((Storyboard)Resources["Pop"]).Begin();
    }

    private async Task CheckAsync()
    {
        SetLook(Look.Checking);
        StatusTitle.Text = "Checking for updates…";
        StatusDetail.Text = $"Installed: Tethr Link {Updater.Current}";
        InstallButton.Visibility = Visibility.Collapsed;
        CheckButton.IsEnabled = false;
        try
        {
            await Task.Delay(600); // long enough to see the check happen
            _update = await Updater.CheckAsync();
            if (_update is null)
            {
                SetLook(Look.UpToDate);
                StatusTitle.Text = "You're up to date";
                StatusDetail.Text = $"Tethr Link {Updater.Current} is the latest version. Last checked at {DateTime.Now:t}.";
            }
            else ShowAvailable(_update);
        }
        catch (Exception ex)
        {
            SetLook(Look.Error);
            StatusTitle.Text = "Couldn't check for updates";
            StatusDetail.Text = $"Check your internet connection and try again. ({ex.Message})";
        }
        finally { CheckButton.IsEnabled = true; }
    }

    private void ShowAvailable(Updater.Update u)
    {
        SetLook(Look.Available);
        StatusTitle.Text = u.IsEarly ? $"Tethr Link {u.Version} is available early" : $"Tethr Link {u.Version} is available";
        StatusDetail.Text = $"You have {Updater.Current}." + (u.Published is { } p ? $" Released {p.LocalDateTime:d MMMM}." : "");
        InstallLabel.Text = $"Install {u.Version}";
        InstallButton.Visibility = Visibility.Visible;
    }

    private async Task LoadNotesAsync()
    {
        try
        {
            var list = await Updater.RecentReleasesAsync();
            Releases.ItemsSource = list;
            NotesError.Visibility = list.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception) { NotesError.Visibility = Visibility.Visible; }
        finally { NotesLoading.IsActive = false; NotesLoading.Visibility = Visibility.Collapsed; }
    }

    private void OnCheck(object sender, RoutedEventArgs e) => _ = CheckAsync();

    private async void OnInstall(object sender, RoutedEventArgs e)
    {
        if (_update is null || App.Window is null) return;
        await App.Window.RunUpdateAsync(_update);
    }

    private void OnAutoToggled(object sender, RoutedEventArgs e)
    {
        App.Session.Settings.AutoCheckUpdates = AutoSwitch.IsOn;
        App.Session.Settings.Save();
    }

    private void OnBack(object sender, RoutedEventArgs e)
    {
        if (Frame.CanGoBack) Frame.GoBack(); else Frame.Navigate(typeof(SettingsPage));
    }
}
