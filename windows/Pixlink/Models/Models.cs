using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Xaml.Media.Imaging;

namespace Pixlink.Models;

public partial class PhoneNotification : ObservableObject
{
    public required string Key { get; init; }
    public required string App { get; init; }
    public string AppName { get; init; } = "";
    public string Title { get; init; } = "";
    public string Text { get; init; } = "";
    public DateTimeOffset Time { get; init; }
    public bool CanReply { get; init; }
    public bool IsMessage { get; init; }
    public string? Conversation { get; init; }

    /// <summary>The app's own buttons (Mark as read, Like…), in the phone's order.</summary>
    public List<NotifAction> Actions { get; init; } = new();

    /// <summary>Recent messages in this conversation (for chat apps), shown when the card is expanded.</summary>
    public System.Collections.ObjectModel.ObservableCollection<ChatBubble> History { get; } = new();

    public PhoneNotification()
    {
        History.CollectionChanged += (_, _) =>
        {
            OnPropertyChanged(nameof(HasHistory));
            OnPropertyChanged(nameof(ShowSingleText));
        };
    }

    [ObservableProperty] private BitmapImage? _icon;
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(TextLines), nameof(TitleLines), nameof(ExpandGlyph), nameof(ShowSingleText))] private bool _isExpanded;
    [ObservableProperty] private string _replyDraft = "";

    public bool HasActions => Actions.Count > 0;
    public bool HasHistory => History.Count > 1;
    public bool HasMore => true; // every card can open its app on the phone
    /// <summary>Collapsed cards show 3 lines; expanded shows everything.</summary>
    public int TextLines => IsExpanded ? 0 : 3;
    public int TitleLines => IsExpanded ? 0 : 1;
    /// <summary>The app's own buttons, plus "Open on phone" (opens the app there and shows your phone screen).</summary>
    public List<NotifAction> AllButtons => [new NotifAction(Key, -1, "Open on phone", "open"), .. Actions];
    /// <summary>When expanded with a chat history, the bubbles replace the single preview line.</summary>
    public bool ShowSingleText => !(IsExpanded && HasHistory);
    public string ExpandGlyph => IsExpanded ? "\uE70E" : "\uE70D";

    public string TimeText => Time.LocalDateTime.Date == DateTime.Today
        ? Time.LocalDateTime.ToString("t")
        : Time.LocalDateTime.ToString("d MMM");

    /// <summary>Messages are grouped by conversation (group chats) or by sender.</summary>
    public string ThreadName => Conversation ?? Title;
}

/// <summary>A button from the phone notification (pressed on the phone when clicked on the PC).</summary>
public sealed class NotifAction(string key, int index, string title, string semantic)
{
    public string Key { get; } = key;
    public int Index { get; } = index;
    public string Title { get; } = title;
    public string Semantic { get; } = semantic;
    public string Glyph => Semantic switch
    {
        "markAsRead" => "\uE8C3", "delete" => "\uE74D", "archive" => "\uE7B8", "mute" => "\uE74F",
        "like" => "\uE8E1", "call" => "\uE717", "open" => "\uE8A7", _ => "\uE8FB",
    };
}

/// <summary>One message thread in the Messages tab, built from messaging notifications.</summary>
public partial class Conversation : ObservableObject
{
    public string Name { get; set; } = "";
    public string App { get; set; } = "";
    public string AppName { get; set; } = "";
    /// <summary>Set for SMS threads read from the phone ("Texts"); replies go out as SMS.</summary>
    public long? ThreadId { get; set; }
    public string? Address { get; set; }

    /// <summary>Remembered key for pinning/removing ("app|name").</summary>
    public string Id => $"{App}|{Name}";

    [ObservableProperty] [NotifyPropertyChangedFor(nameof(PinVisibility))] private bool _isPinned;
    public Microsoft.UI.Xaml.Visibility PinVisibility => IsPinned ? Microsoft.UI.Xaml.Visibility.Visible : Microsoft.UI.Xaml.Visibility.Collapsed;

    /// <summary>Key of the newest phone notification in this conversation (for Mark as read).</summary>
    [ObservableProperty] private string? _latestKey;
    public List<NotifAction> LatestActions { get; set; } = new();
    public System.Collections.ObjectModel.ObservableCollection<ChatBubble> Messages { get; } = new();

    [ObservableProperty] private string _preview = "";
    [ObservableProperty] private string _time = "";
    [ObservableProperty] private string? _replyKey;
    [ObservableProperty] private BitmapImage? _icon;

    public string Initial => string.IsNullOrEmpty(Name) ? "?" : Name[..1].ToUpperInvariant();
}

public sealed class ChatBubble(string text, bool fromMe, string time, string sender = "")
{
    public string Text { get; } = text;
    public bool FromMe { get; } = fromMe;
    public string Time { get; } = time;
    public string Sender { get; } = sender;
    public bool ShowSender => !FromMe && !string.IsNullOrEmpty(Sender);
    public Microsoft.UI.Xaml.Media.Brush BubbleBrush => (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources[
        FromMe ? "ControlAltFillColorTertiaryBrush" : "ControlFillColorDefaultBrush"];
    public Microsoft.UI.Xaml.HorizontalAlignment Align =>
        FromMe ? Microsoft.UI.Xaml.HorizontalAlignment.Right : Microsoft.UI.Xaml.HorizontalAlignment.Left;
}

public sealed record Contact(string Name, List<string> Numbers)
{
    public string Initial => string.IsNullOrEmpty(Name) ? "?" : Name[..1].ToUpperInvariant();
    public string FirstNumber => Numbers.FirstOrDefault() ?? "";
}

public sealed record CallEntry(string Number, string? Name, string Type, DateTimeOffset Date, int Duration)
{
    public string Display => Name ?? Number;
    public string Glyph => Type switch { "incoming" => "", "outgoing" => "", "missed" => "", _ => "" };
    public string When => Date.LocalDateTime.ToString("g");
}

public partial class PhotoItem : ObservableObject
{
    public required long Id { get; init; }
    public required string Name { get; init; }
    public long Size { get; init; }
    [ObservableProperty] private BitmapImage? _thumb;
}

public sealed record FileEntry(string Name, string Path, bool IsDir, long Size, DateTimeOffset Modified)
{
    public string Glyph => IsDir ? "" : "";
    public string SizeText => IsDir ? "" : Size switch
    {
        < 1024 => $"{Size} B",
        < 1024 * 1024 => $"{Size / 1024.0:0.#} KB",
        < 1024L * 1024 * 1024 => $"{Size / 1024.0 / 1024:0.#} MB",
        _ => $"{Size / 1024.0 / 1024 / 1024:0.##} GB",
    };
    public string ModifiedText => Modified.LocalDateTime.ToString("g");
}


/// <summary>All of one app's notifications, shown together when "Group by app" is on.</summary>
public sealed partial class NotificationGroup(string appName, Microsoft.UI.Xaml.Media.ImageSource? icon, List<PhoneNotification> items)
    : CommunityToolkit.Mvvm.ComponentModel.ObservableObject
{
    public string AppName { get; } = appName;
    public Microsoft.UI.Xaml.Media.ImageSource? Icon { get; } = icon;
    public List<PhoneNotification> Items { get; } = items;
    public string CountText => Items.Count == 1 ? "1 notification" : $"{Items.Count} notifications";
    [CommunityToolkit.Mvvm.ComponentModel.ObservableProperty]
    [CommunityToolkit.Mvvm.ComponentModel.NotifyPropertyChangedFor(nameof(ChevronGlyph))]
    private bool _isExpanded = true;
    public string ChevronGlyph => IsExpanded ? "\uE70E" : "\uE70D";
}

/// <summary>A button in the quick actions grid. Which ones show is chosen in Settings.</summary>
public sealed partial class QuickActionItem : ObservableObject
{
    public required string Id { get; init; }
    public required string Glyph { get; init; }
    public required string Name { get; init; }
    public string Tip { get; init; } = "";
    public bool NeedsPower { get; init; }
    [ObservableProperty] private string _label = "";
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(Background), nameof(Foreground))] private bool _isActive;
    public Microsoft.UI.Xaml.Media.Brush Background => (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources[
        IsActive ? "AccentFillColorDefaultBrush" : "ControlFillColorDefaultBrush"];
    public Microsoft.UI.Xaml.Media.Brush Foreground => (Microsoft.UI.Xaml.Media.Brush)Microsoft.UI.Xaml.Application.Current.Resources[
        IsActive ? "TextOnAccentFillColorPrimaryBrush" : "TextFillColorPrimaryBrush"];

    public static readonly (string Id, string Glyph, string Name, string Tip, bool NeedsPower)[] Catalog =
    [
        ("hotspot", "", "Hotspot", "Use your phone's internet", false),
        ("screen", "", "Screen", "Show your phone screen", false),
        ("sendfile", "", "Send file", "Send a file to your phone", false),
        ("ring", "", "Find phone", "Ring your phone at full volume, even on silent", false),
        ("clipboard", "", "Clipboard", "Send this PC's copied text to your phone", false),
        ("dnd", "", "Do not disturb", "Turn Do Not Disturb on or off on your phone", false),
        ("photos", "", "Photos", "Open your phone's photos", false),
        ("messages", "", "Messages", "Open your texts", false),
        ("lock", "", "Lock phone", "Lock your phone's screen", true),
        ("screenshot", "", "Screenshot", "Take a screenshot on your phone and send it here", true),
    ];

    public static readonly string[] Defaults = ["hotspot", "screen", "sendfile", "ring", "clipboard", "dnd"];
}
