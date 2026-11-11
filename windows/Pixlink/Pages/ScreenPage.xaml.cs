using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Pixlink.Services;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace Pixlink.Pages;

/// <summary>Screen mirroring with mouse/keyboard control (control needs Power mode on the phone).</summary>
public sealed partial class ScreenPage : Page
{
    private PhoneSession Session => App.Session;
    private MediaPlayer? _player;
    private uint _width, _height;
    private bool _pointerDown;

    public ScreenPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Session.Message += OnMessage;
        Session.Mirror.StreamEnded += OnStreamEnded;
        StartButton.IsEnabled = Session.IsConnected;
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        Session.Message -= OnMessage;
        Session.Mirror.StreamEnded -= OnStreamEnded;
        Stop();
    }

    private async void OnStart(object sender, RoutedEventArgs e)
    {
        StartButton.IsEnabled = false;
        Waiting.IsActive = true;
        StartHint.Text = "Check your phone and tap Start (or the notification) to share your screen.";
        await Session.SendAsync(Envelope.Of("mirror.start", new { maxSize = 1600, bitrate = 8_000_000, fps = 60 }));
    }

    private void OnMessage(Envelope m)
    {
        if (m.T == "mirror.started")
        {
            _width = (uint)(m.Int("width") ?? 720);
            _height = (uint)(m.Int("height") ?? 1600);
            ShowStream();
        }
        else if (m.T == "mirror.stop")
        {
            Stop();
        }
    }

    private void ShowStream()
    {
        var source = Session.Mirror.CreateSource(_width, _height);
        _player = new MediaPlayer
        {
            Source = MediaSource.CreateFromMediaStreamSource(source),
            RealTimePlayback = true,
            IsMuted = true,
        };
        Player.SetMediaPlayer(_player);
        _player.Play();

        StartPanel.Visibility = Visibility.Collapsed;
        PhoneFrame.Visibility = Visibility.Visible;
        Controls.Visibility = Visibility.Visible;
        ControlNote.Visibility = Session.PowerMode ? Visibility.Collapsed : Visibility.Visible;
        Player.Focus(FocusState.Programmatic);
    }

    private void OnStreamEnded() => DispatcherQueue.TryEnqueue(Stop);

    private void Stop()
    {
        _player?.Dispose();
        _player = null;
        PhoneFrame.Visibility = Visibility.Collapsed;
        Controls.Visibility = Visibility.Collapsed;
        StartPanel.Visibility = Visibility.Visible;
        Waiting.IsActive = false;
        StartButton.IsEnabled = Session.IsConnected;
        StartHint.Text = "Your phone will ask you to confirm each time — that's an Android rule.";
    }

    private void OnStop(object sender, RoutedEventArgs e)
    {
        _ = Session.SendAsync(Envelope.Of("mirror.stop"));
        Stop();
    }

    // ----- Input: positions are sent normalized (0..1) so the phone maps them to its real resolution.

    /// <summary>Maps a pointer position to 0..1 within the video's letterboxed area.</summary>
    private (double x, double y)? Normalize(PointerRoutedEventArgs e)
    {
        if (_width == 0 || !Session.PowerMode) return null;
        var p = e.GetCurrentPoint(Player).Position;
        double w = Player.ActualWidth, h = Player.ActualHeight;
        var scale = Math.Min(w / _width, h / _height);
        double vw = _width * scale, vh = _height * scale;
        double ox = (w - vw) / 2, oy = (h - vh) / 2;
        var x = (p.X - ox) / vw;
        var y = (p.Y - oy) / vh;
        if (x < 0 || x > 1 || y < 0 || y > 1) return null;
        return (x, y);
    }

    private void SendTouch(string action, PointerRoutedEventArgs e)
    {
        if (Normalize(e) is not { } pt) return;
        _ = Session.SendAsync(Envelope.Of("input.touch", new { action, x = pt.x, y = pt.y }));
    }

    private void OnPointerPressed(object sender, PointerRoutedEventArgs e)
    {
        Player.Focus(FocusState.Pointer);
        if (e.GetCurrentPoint(Player).Properties.IsRightButtonPressed) { OnBack(sender, e); return; }
        _pointerDown = true;
        Player.CapturePointer(e.Pointer);
        SendTouch("down", e);
    }

    private void OnPointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (_pointerDown) SendTouch("move", e);
    }

    private void OnPointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (!_pointerDown) return;
        _pointerDown = false;
        Player.ReleasePointerCapture(e.Pointer);
        SendTouch("up", e);
    }

    private void OnWheel(object sender, PointerRoutedEventArgs e)
    {
        if (Normalize(e) is not { } pt) return;
        var delta = e.GetCurrentPoint(Player).Properties.MouseWheelDelta / 120.0;
        _ = Session.SendAsync(Envelope.Of("input.scroll", new { x = pt.x, y = pt.y, dy = delta }));
    }

    private void OnKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (!Session.PowerMode) return;
        // Special keys map to Android keycodes; normal characters arrive via CharacterReceived.
        int? keycode = e.Key switch
        {
            Windows.System.VirtualKey.Back => 67,     // KEYCODE_DEL
            Windows.System.VirtualKey.Enter => 66,    // KEYCODE_ENTER
            Windows.System.VirtualKey.Escape => 4,    // KEYCODE_BACK
            Windows.System.VirtualKey.Left => 21,
            Windows.System.VirtualKey.Right => 22,
            Windows.System.VirtualKey.Up => 19,
            Windows.System.VirtualKey.Down => 20,
            Windows.System.VirtualKey.Delete => 112,  // KEYCODE_FORWARD_DEL
            _ => null,
        };
        if (keycode is { } k)
        {
            _ = Session.SendAsync(Envelope.Of("input.key", new { keycode = k }));
            e.Handled = true;
        }
    }

    private void OnChar(UIElement sender, CharacterReceivedRoutedEventArgs e)
    {
        if (!Session.PowerMode || char.IsControl(e.Character)) return;
        _ = Session.SendAsync(Envelope.Of("input.key", new { text = e.Character.ToString() }));
    }

    private void Nav(string button) => _ = Session.SendAsync(Envelope.Of("input.nav", new { button }));
    private void OnBack(object sender, RoutedEventArgs e) => Nav("back");
    private void OnHome(object sender, RoutedEventArgs e) => Nav("home");
    private void OnRecents(object sender, RoutedEventArgs e) => Nav("recents");
}
