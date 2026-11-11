using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Pixlink.Models;
using Pixlink.Services;

namespace Pixlink.Pages;

public sealed partial class MessagesPage : Page
{
    public PhoneSession Session => App.Session;
    private Conversation? _current;

    public MessagesPage()
    {
        InitializeComponent();
    }

    private void OnConversationSelected(object sender, SelectionChangedEventArgs e)
    {
        if (ConversationList.SelectedItem is not Conversation c) return;
        _current = c;
        ThreadTitle.Text = c.Name;
        ThreadPicture.DisplayName = c.Name;
        Bubbles.ItemsSource = c.Messages;
        EmptyView.Visibility = Visibility.Collapsed;
        WebView.Visibility = Visibility.Collapsed;
        ThreadView.Visibility = Visibility.Visible;
        UpdateReplyState();
        if (c.Messages.Count > 0) Bubbles.ScrollIntoView(c.Messages[^1]);
    }

    private void UpdateReplyState()
    {
        var canReply = _current?.ReplyKey is not null;
        ReplyBox.IsEnabled = canReply;
        NoReplyNote.Visibility = canReply ? Visibility.Collapsed : Visibility.Visible;
    }

    private async void OnSend(object sender, RoutedEventArgs e)
    {
        if (_current is null) return;
        var text = ReplyBox.Text;
        ReplyBox.Text = "";
        await Session.ReplyAsync(_current, text);
        if (_current.Messages.Count > 0) Bubbles.ScrollIntoView(_current.Messages[^1]);
    }

    private void OnReplyKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == Windows.System.VirtualKey.Enter) { OnSend(sender, e); e.Handled = true; }
    }

    /// <summary>Google Messages for Web gives full history; the user pairs it once with their own Google account.</summary>
    private async void OnFullHistory(object sender, RoutedEventArgs e)
    {
        ConversationList.SelectedItem = null;
        EmptyView.Visibility = Visibility.Collapsed;
        ThreadView.Visibility = Visibility.Collapsed;
        WebView.Visibility = Visibility.Visible;
        await MessagesWeb.EnsureCoreWebView2Async();
        if (MessagesWeb.Source is null) MessagesWeb.Source = new Uri("https://messages.google.com/web");
    }
}
