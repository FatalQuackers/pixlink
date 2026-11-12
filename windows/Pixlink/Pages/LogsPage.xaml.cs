using System.Collections.ObjectModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Pixlink.Services;

namespace Pixlink.Pages;

public sealed partial class LogsPage : Page
{
    public ObservableCollection<LogEntry> Entries => Log.Entries;

    public LogsPage() { InitializeComponent(); }

    private void OnBack(object sender, RoutedEventArgs e) { if (Frame.CanGoBack) Frame.GoBack(); else Frame.Navigate(typeof(SettingsPage)); }

    private void OnCopy(object sender, RoutedEventArgs e)
    {
        var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
        package.SetText(string.Join(Environment.NewLine, Entries.Reverse().Select(x => x.Line)));
        Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
    }

    private async void OnOpenFolder(object sender, RoutedEventArgs e) => await Windows.System.Launcher.LaunchFolderPathAsync(AppSettings.Folder);
}
