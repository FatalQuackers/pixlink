using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Pixlink.Services;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace Pixlink.Pages;

/// <summary>
/// Screen mirroring with mouse/keyboard control (control needs Power mode on the phone).
/// Mouse input becomes whole gestures sent on release: click = tap, hold = long press,
/// drag = swipe. One shell command per gesture is far more reliable than streaming moves.
/// </summary>
public sealed partial class ScreenPage : Page
{
    private PhoneSession Session => App.Session;
    private MediaPlayer? _player;
    private uint _width, _height;

    private (double x, double y)? _downAt;
    private DateTimeOffset _downTime;

    public ScreenPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Session.Message += OnMessage;
        Session.PropertyChanged += OnSessionChanged;
        Session.Mirror.StreamEnded += OnStreamEnded;
        StartButton.IsEnabled = Session.IsConnected;
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        Session.Message -= OnMessage;
        Session.PropertyChanged -= OnSessionChanged;
        Session.Mirror.StreamEnded -= OnStreamEnded;
        Stop();
    }

    private void OnSessionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PhoneSession.PowerMode)) UpdateControlNote();
    }

    private void UpdateControlNote() =>
        ControlNote.Visibility = Session.PowerMode ? Visibility.Collapsed : Visibility.Visible;

    private async void OnStart(object sender, RoutedEventArgs e)
    {
        StartButton.IsEnabled = false;
        Waiting.IsActive = true;
        StartHint.Text = Session.PowerMode
            ? "Check your phone and tap Start now to share your screen."
            : "Check your phone: tap the Pixlink notification, then Start now.";
        var s = Session.Settings;
        await Session.SendAsync(Envelope.Of("mirror.start", new { maxSize = s.MirrorMaxSize, bitrate = s.MirrorBitrateMbps * 1_000_000, fps = s.MirrorFps }));
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
        UpdateControlNote();
        Focus(FocusState.Programmatic);
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

    // ----- Mouse: positions are sent normalized (0..1) so the phone maps them to its real resolution.

    /// <summary>Maps a pointer position to 0..1 within the video's letterboxed area.</summary>
    private (double x, double y)? Normalize(PointerRoutedEventArgs e)
    {
        if (_width == 0) return null;
        var p = e.GetCurrentPoint(TouchLayer).Position;
        double w = TouchLayer.ActualWidth, h = TouchLayer.ActualHeight;
        var scale = Math.Min(w / _width, h / _height);
        double vw = _width * scale, vh = _height * scale;
        double ox = (w - vw) / 2, oy = (h - vh) / 2;
        var x = Math.Clamp((p.X - ox) / vw, 0, 1);
        var y = Math.Clamp((p.Y - oy) / vh, 0, 1);
        return (x, y);
    }

    private void OnPointerPressed(object sender, PointerRoutedEventArgs e)
    {
        Focus(FocusState.Pointer);
        if (!Session.PowerMode) return;
        var props = e.GetCurrentPoint(TouchLayer).Properties;
        if (props.IsRightButtonPressed) { Nav("back"); return; } // right-click = Back, like Phone Link
        if (props.IsMiddleButtonPressed) { Nav("home"); return; }

        _downAt = Normalize(e);
        _downTime = DateTimeOffset.Now;
        TouchLayer.CapturePointer(e.Pointer);
    }

    private void OnPointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (_downAt is not { } start || Normalize(e) is not { } end) { _downAt = null; return; }
        _downAt = null;
        TouchLayer.ReleasePointerCapture(e.Pointer);

        var ms = (int)(DateTimeOffset.Now - _downTime).TotalMilliseconds;
        var moved = Math.Abs(end.x - start.x) > 0.01 || Math.Abs(end.y - start.y) > 0.01;

        if (!moved && ms < 450)
            _ = Session.SendAsync(Envelope.Of("input.tap", new { x = start.x, y = start.y }));
        else if (!moved)
            _ = Session.SendAsync(Envelope.Of("input.swipe", new { x1 = start.x, y1 = start.y, x2 = start.x, y2 = start.y, ms = Math.Max(ms, 600) }));
        else
            _ = Session.SendAsync(Envelope.Of("input.swipe", new { x1 = start.x, y1 = start.y, x2 = end.x, y2 = end.y, ms = Math.Clamp(ms, 80, 1500) }));
    }

    private void OnPointerCanceled(object sender, PointerRoutedEventArgs e) => _downAt = null;

    private void OnWheel(object sender, PointerRoutedEventArgs e)
    {
        if (!Session.PowerMode || Normalize(e) is not { } pt) return;
        var delta = e.GetCurrentPoint(TouchLayer).Properties.MouseWheelDelta / 120.0;
        _ = Session.SendAsync(Envelope.Of("input.scroll", new { x = pt.x, y = pt.y, dy = delta }));
        e.Handled = true;
    }

    // ----- Keyboard (handled on the whole page so it works wherever focus lands)

    private void OnKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (!Session.PowerMode || PhoneFrame.Visibility != Visibility.Visible) return;
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
            Windows.System.VirtualKey.Tab => 61,
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
        if (!Session.PowerMode || PhoneFrame.Visibility != Visibility.Visible || char.IsControl(e.Character)) return;
        _ = Session.SendAsync(Envelope.Of("input.key", new { text = e.Character.ToString() }));
        e.Handled = true;
    }

    private void Nav(string button) => _ = Session.SendAsync(Envelope.Of("input.nav", new { button }));
    private void OnBack(object sender, RoutedEventArgs e) => Nav("back");
    private void OnHome(object sender, RoutedEventArgs e) => Nav("home");
    private void OnRecents(object sender, RoutedEventArgs e) => Nav("recents");
}
