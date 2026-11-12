using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Documents;

namespace Pixlink;

/// <summary>Message text where web addresses are clickable links (they open in your browser).</summary>
public sealed partial class LinkTextBlock : UserControl
{
    private static readonly Regex Url = new(@"\b(?:https?://|www\.)[^\s<>""']+[^\s<>""'.,;:!?)\]]", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private readonly TextBlock _block = new() { TextWrapping = TextWrapping.Wrap, IsTextSelectionEnabled = true, TextTrimming = TextTrimming.CharacterEllipsis };

    public LinkTextBlock() { Content = _block; }

    public static readonly DependencyProperty TextProperty = DependencyProperty.Register(nameof(Text), typeof(string), typeof(LinkTextBlock),
        new PropertyMetadata("", (d, _) => ((LinkTextBlock)d).Rebuild()));
    public static readonly DependencyProperty MaxLinesProperty = DependencyProperty.Register(nameof(MaxLines), typeof(int), typeof(LinkTextBlock),
        new PropertyMetadata(0, (d, e) => ((LinkTextBlock)d)._block.MaxLines = (int)e.NewValue));

    public string Text { get => (string)GetValue(TextProperty); set => SetValue(TextProperty, value); }
    public int MaxLines { get => (int)GetValue(MaxLinesProperty); set => SetValue(MaxLinesProperty, value); }

    private void Rebuild()
    {
        _block.Inlines.Clear();
        var text = Text ?? "";
        var at = 0;
        foreach (Match m in Url.Matches(text))
        {
            if (m.Index > at) _block.Inlines.Add(new Run { Text = text[at..m.Index] });
            var href = m.Value.StartsWith("www.", StringComparison.OrdinalIgnoreCase) ? "https://" + m.Value : m.Value;
            if (Uri.TryCreate(href, UriKind.Absolute, out var uri) && uri.Scheme is "http" or "https")
            {
                var link = new Hyperlink { NavigateUri = uri };
                link.Inlines.Add(new Run { Text = m.Value });
                _block.Inlines.Add(link);
            }
            else _block.Inlines.Add(new Run { Text = m.Value });
            at = m.Index + m.Length;
        }
        if (at < text.Length) _block.Inlines.Add(new Run { Text = text[at..] });
    }
}
