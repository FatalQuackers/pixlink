using System.Collections.ObjectModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Pixlink.Models;
using Pixlink.Services;

namespace Pixlink.Pages;

public sealed partial class PhotosPage : Page
{
    private PhoneSession Session => App.Session;
    private readonly ObservableCollection<PhotoItem> _photos = new();
    private readonly HashSet<long> _thumbRequested = new();

    public PhotosPage()
    {
        InitializeComponent();
        Grid1.ItemsSource = _photos;
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e) => await LoadAsync();

    private async Task LoadAsync()
    {
        if (!Session.IsConnected) { StatusText.Text = "Connect your phone to see photos"; return; }
        StatusText.Text = "";
        Loading.IsActive = true;
        _photos.Clear();
        _thumbRequested.Clear();
        var r = await Session.RequestAsync("photos.list", new { offset = 0, limit = 2000 }, TimeSpan.FromSeconds(30));
        Loading.IsActive = false;
        var arr = r?.Arr("photos");
        if (arr is null || arr.Count == 0) { StatusText.Text = "No photos found — check Pixlink has photo access on your phone"; return; }
        foreach (var o in arr.OfType<System.Text.Json.Nodes.JsonObject>())
        {
            _photos.Add(new PhotoItem
            {
                Id = o["id"]!.GetValue<long>(),
                Name = o["name"]?.GetValue<string>() ?? "photo.jpg",
                Size = o["size"]?.GetValue<long>() ?? 0,
            });
        }
    }

    /// <summary>Loads thumbnails lazily as tiles scroll into view.</summary>
    private async void OnContainerChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
    {
        if (args.InRecycleQueue || args.Item is not PhotoItem p || p.Thumb is not null || !_thumbRequested.Add(p.Id)) return;
        var r = await Session.RequestAsync("photos.thumb", new { id = p.Id, size = 300 });
        if (r?.Str("jpegBase64") is { } b64) p.Thumb = PhoneSession.ImageFromBase64(b64);
    }

    private void OnSelection(object sender, SelectionChangedEventArgs e) => SaveButton.IsEnabled = Grid1.SelectedItems.Count > 0;

    private async void OnSave(object sender, RoutedEventArgs e)
    {
        foreach (var p in Grid1.SelectedItems.OfType<PhotoItem>().ToList())
            await Session.Files.DownloadAsync(photoId: p.Id, suggestedName: p.Name);
        await Windows.System.Launcher.LaunchFolderPathAsync(FileTransfers.DownloadFolder);
    }

    private async void OnOpen(object sender, DoubleTappedRoutedEventArgs e)
    {
        if ((e.OriginalSource as FrameworkElement)?.DataContext is not PhotoItem p) return;
        var path = await Session.Files.DownloadAsync(photoId: p.Id, suggestedName: p.Name);
        if (path is not null) await Windows.System.Launcher.LaunchUriAsync(new Uri(path));
    }

    private async void OnRefresh(object sender, RoutedEventArgs e) => await LoadAsync();
}
