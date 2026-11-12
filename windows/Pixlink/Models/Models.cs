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
    [ObservableProperty] [NotifyPropertyChangedFor(nameof(TextLines), nameof(ExpandGlyph), nameof(ShowSingleText))] private bool _isExpanded;
    [ObservableProperty] private string _replyDraft = "";

    public bool HasActions => Actions.Count > 0;
    public bool HasHistory => History.Count > 1;
    public bool HasMore => HasActions || CanReply || HasHistory || Text.Length > 90;
    /// <summary>Collapsed cards show 3 lines; expanded shows everything.</summary>
    public int TextLines => IsExpanded ? 0 : 3;
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
public sealed record NotifAction(string Key, int Index, string Title, string Semantic)
{
    public string Glyph => Semantic switch
    {
        "markAsRead" => "\uE8C3", "delete" => "\uE74D", "archive" => "\uE7B8", "mute" => "\uE74F",
        "like" => "\uE8E1", "call" => "\uE717", _ => "\uE8FB",
    };
}

/// <summary>One message thread in the Messages tab, built from messaging notifications.</summary>
public partial class Conversation : ObservableObject
{
    public required string Name { get; init; }
    public required string App { get; init; }

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

public sealed record ChatBubble(string Text, bool FromMe, string Time, string Sender = "")
{
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
