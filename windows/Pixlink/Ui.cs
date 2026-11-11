using Microsoft.UI.Xaml;

namespace Pixlink;

/// <summary>Small helpers for x:Bind function bindings in XAML.</summary>
public static class Ui
{
    public static Visibility Visible(bool value) => value ? Visibility.Visible : Visibility.Collapsed;
    public static Visibility Collapsed(bool value) => value ? Visibility.Collapsed : Visibility.Visible;
    public static Visibility VisibleIfText(string? value) => string.IsNullOrEmpty(value) ? Visibility.Collapsed : Visibility.Visible;
    public static bool Not(bool value) => !value;
    public static string ConnectedDot(bool connected) => connected ? "" : "";
}
