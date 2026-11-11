using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using QRCoder;

namespace Pixlink.Pages;

/// <summary>Shows the pairing QR code. A new code is made every 4½ minutes (codes last 5).</summary>
public sealed partial class PairingPage : Page
{
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(270) };

    public PairingPage()
    {
        InitializeComponent();
        _timer.Tick += (_, _) => ShowNewCode();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        ShowNewCode();
        _timer.Start();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => _timer.Stop();

    private void OnRefresh(object sender, RoutedEventArgs e) => ShowNewCode();

    /// <summary>Copies the pairing link so it can be pasted into the phone app (handy for the Android emulator).</summary>
    private async void OnCopy(object sender, RoutedEventArgs e)
    {
        if (App.Session.PairingUri is not { } uri) return;
        var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
        package.SetText(uri);
        Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
        CopyButton.Content = "Copied";
        await Task.Delay(2000);
        CopyButton.Content = "Copy link";
    }

    private void ShowNewCode()
    {
        if (App.Session.IsPaired) { _timer.Stop(); return; }
        var uri = App.Session.BeginPairing();

        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(uri, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(10);

        var img = Services.PhoneSession.ImageFromBytes(png);
        QrImage.Source = img;
        ExpiryText.Text = $"This code works for 5 minutes · PC: {Environment.MachineName}";
    }
}
