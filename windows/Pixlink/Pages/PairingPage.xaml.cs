using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Navigation;
using QRCoder;

namespace Pixlink.Pages;

/// <summary>Shows the pairing QR code and 5-digit code. Both are replaced every 4½ minutes (they last 5).</summary>
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
        App.Session.PropertyChanged += OnSessionChanged;
        ShowNewCode();
        _timer.Start();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        App.Session.PropertyChanged -= OnSessionChanged;
        _timer.Stop();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (!Ui.AnimationsEnabled) { Hero.Opacity = QrCard.Opacity = CodeCard.Opacity = 1; return; }
        ((Storyboard)Resources["Intro"]).Begin();
        ((Storyboard)Resources["Pulse"]).Begin();
    }

    // The code is regenerated after 5 wrong attempts: redraw when it changes.
    private void OnSessionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(Services.PhoneSession.PairingUri) && App.Session.PairingUri is { } uri)
            DispatcherQueue.TryEnqueue(() => Draw(uri));
    }

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
        Draw(App.Session.BeginPairing());
    }

    private void Draw(string uri)
    {
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(uri, QRCodeGenerator.ECCLevel.M);
        QrImage.Source = Services.PhoneSession.ImageFromBytes(new PngByteQRCode(data).GetGraphic(10));

        CodeDigits.Children.Clear();
        foreach (var digit in App.Session.PairingCode ?? "-----")
        {
            CodeDigits.Children.Add(new Border
            {
                Style = (Style)Resources["CodeDigit"],
                Child = new TextBlock
                {
                    Text = digit.ToString(),
                    FontSize = 32,
                    FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                },
            });
        }
        ExpiryText.Text = $"Waiting for your phone. Codes refresh every few minutes. This PC: {App.Session.PcName}";
    }
}
