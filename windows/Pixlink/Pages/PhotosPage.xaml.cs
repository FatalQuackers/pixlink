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
        if (arr is null || arr.Count == 0) { StatusText.Text = "No photos found — check Tethr Link has photo access on your phone"; return; }
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

    // ---------- Viewer ----------
    private PhotoItem? _viewing;

    private void OnItemClick(object sender, ItemClickEventArgs e) { if (e.ClickedItem is PhotoItem p) _ = ShowAsync(p); }

    private async Task ShowAsync(PhotoItem p)
    {
        _viewing = p;
        Viewer.Visibility = Visibility.Visible;
        Viewer.Focus(FocusState.Programmatic);
        ViewerName.Text = p.Name;
        ViewerImage.Source = p.Thumb; // the thumbnail straight away, the sharp version a moment later
        ViewerLoading.IsActive = true;
        var r = await Session.RequestAsync("photos.thumb", new { id = p.Id, size = 1800 }, TimeSpan.FromSeconds(20));
        ViewerLoading.IsActive = false;
        if (_viewing == p && r?.Str("jpegBase64") is { } b64) ViewerImage.Source = PhoneSession.ImageFromBase64(b64);
    }

    private void Step(int by)
    {
        if (_viewing is null || Grid1.ItemsSource is not IList<PhotoItem> list) return;
        var i = list.IndexOf(_viewing) + by;
        if (i >= 0 && i < list.Count) _ = ShowAsync(list[i]);
    }

    private void OnViewerPrev(object sender, RoutedEventArgs e) => Step(-1);
    private void OnViewerNext(object sender, RoutedEventArgs e) => Step(1);
    private void OnViewerClose(object sender, RoutedEventArgs e) { Viewer.Visibility = Visibility.Collapsed; _viewing = null; }
    private void OnViewerClose(object sender, TappedRoutedEventArgs e) => OnViewerClose(sender, (RoutedEventArgs)e);

    private void OnViewerKey(object sender, KeyRoutedEventArgs e)
    {
        switch (e.Key)
        {
            case Windows.System.VirtualKey.Escape: OnViewerClose(sender, e); e.Handled = true; break;
            case Windows.System.VirtualKey.Left: Step(-1); e.Handled = true; break;
            case Windows.System.VirtualKey.Right: Step(1); e.Handled = true; break;
        }
    }

    private async void OnViewerSave(object sender, RoutedEventArgs e)
    {
        if (_viewing is null) return;
        await Session.Files.DownloadAsync(photoId: _viewing.Id, suggestedName: _viewing.Name);
        await Windows.System.Launcher.LaunchFolderPathAsync(FileTransfers.DownloadFolder);
    }

    private async void OnViewerOpen(object sender, RoutedEventArgs e)
    {
        if (_viewing is null) return;
        var path = await Session.Files.DownloadAsync(photoId: _viewing.Id, suggestedName: _viewing.Name);
        if (path is not null) await Windows.System.Launcher.LaunchUriAsync(new Uri(path));
    }

    private async void OnRefresh(object sender, RoutedEventArgs e) => await LoadAsync();
}
