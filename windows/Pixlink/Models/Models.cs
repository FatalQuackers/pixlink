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
    public List<string> Actions { get; init; } = new();

    [ObservableProperty] private BitmapImage? _icon;

    public string TimeText => Time.LocalDateTime.Date == DateTime.Today
        ? Time.LocalDateTime.ToString("t")
        : Time.LocalDateTime.ToString("d MMM");

    /// <summary>Messages are grouped by conversation (group chats) or by sender.</summary>
    public string ThreadName => Conversation ?? Title;
}

/// <summary>One message thread in the Messages tab, built from messaging notifications.</summary>
public partial class Conversation : ObservableObject
{
    public required string Name { get; init; }
    public required string App { get; init; }
    public System.Collections.ObjectModel.ObservableCollection<ChatBubble> Messages { get; } = new();

    [ObservableProperty] private string _preview = "";
    [ObservableProperty] private string _time = "";
    [ObservableProperty] private string? _replyKey;
    [ObservableProperty] private BitmapImage? _icon;

    public string Initial => string.IsNullOrEmpty(Name) ? "?" : Name[..1].ToUpperInvariant();
}

public sealed record ChatBubble(string Text, bool FromMe, string Time)
{
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
