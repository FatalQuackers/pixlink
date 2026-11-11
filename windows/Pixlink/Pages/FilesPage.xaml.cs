using System.Collections.ObjectModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Pixlink.Models;
using Pixlink.Services;
using Windows.ApplicationModel.DataTransfer;
using Windows.Storage;
using Windows.Storage.Pickers;

namespace Pixlink.Pages;

public sealed partial class FilesPage : Page
{
    private PhoneSession Session => App.Session;

    private sealed record Crumb(string Name, string? Path)
    {
        public override string ToString() => Name;
    }

    private readonly ObservableCollection<Crumb> _crumbs = new();

    public FilesPage()
    {
        InitializeComponent();
        Crumbs.ItemsSource = _crumbs;
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e) => await ShowRootsAsync();

    private async Task ShowRootsAsync()
    {
        _crumbs.Clear();
        _crumbs.Add(new Crumb(Session.PhoneName, null));
        if (!Session.IsConnected) { EmptyText.Text = "Connect your phone to browse files"; return; }

        Loading.IsActive = true;
        var (roots, power) = await Session.Files.GetRootsAsync();
        Loading.IsActive = false;
        PowerBar.IsOpen = !power;
        FileList.ItemsSource = roots;
        EmptyText.Text = roots.Count == 0
            ? "Nothing shared yet. On your phone, open Pixlink → Settings → Add a folder, or turn on Power mode."
            : "";
    }

    private async Task OpenFolderAsync(string name, string path)
    {
        _crumbs.Add(new Crumb(name, path));
        await LoadAsync(path);
    }

    private async Task LoadAsync(string path)
    {
        Loading.IsActive = true;
        EmptyText.Text = "";
        var items = await Session.Files.ListAsync(path);
        Loading.IsActive = false;
        FileList.ItemsSource = items;
        if (items.Count == 0) EmptyText.Text = "This folder is empty";
    }

    private async void OnItemClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is FileEntry { IsDir: true } f) await OpenFolderAsync(f.Name, f.Path);
    }

    private async void OnCrumb(BreadcrumbBar sender, BreadcrumbBarItemClickedEventArgs args)
    {
        while (_crumbs.Count > args.Index + 1) _crumbs.RemoveAt(_crumbs.Count - 1);
        var c = _crumbs[args.Index];
        if (c.Path is null) await ShowRootsAsync(); else await LoadAsync(c.Path);
    }

    private void OnSelection(object sender, SelectionChangedEventArgs e) =>
        DownloadButton.IsEnabled = FileList.SelectedItems.OfType<FileEntry>().Any(f => !f.IsDir);

    private async void OnDownload(object sender, RoutedEventArgs e)
    {
        foreach (var f in FileList.SelectedItems.OfType<FileEntry>().Where(f => !f.IsDir).ToList())
            await Session.Files.DownloadAsync(path: f.Path, suggestedName: f.Name);
        await Windows.System.Launcher.LaunchFolderPathAsync(FileTransfers.DownloadFolder);
    }

    private async void OnUpload(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(App.Window!));
        foreach (var f in await picker.PickMultipleFilesAsync()) await Session.Files.UploadAsync(f.Path);
    }

    private void OnDragOver(object sender, DragEventArgs e)
    {
        if (e.DataView.Contains(StandardDataFormats.StorageItems))
        {
            e.AcceptedOperation = DataPackageOperation.Copy;
            e.DragUIOverride.Caption = "Send to phone";
        }
    }

    private async void OnDrop(object sender, DragEventArgs e)
    {
        if (!e.DataView.Contains(StandardDataFormats.StorageItems)) return;
        foreach (var item in await e.DataView.GetStorageItemsAsync())
            if (item is StorageFile f) await Session.Files.UploadAsync(f.Path);
    }
}
