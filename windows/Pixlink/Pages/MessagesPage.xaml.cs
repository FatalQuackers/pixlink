using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
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
        Session.Conversations.CollectionChanged += OnConversationsChanged;
        Unloaded += (_, _) => Session.Conversations.CollectionChanged -= OnConversationsChanged;
        UpdateEmptyList();
        ViewPicker.SelectedItem = Session.Settings.MessagesView switch { "google" => GoogleItem, "texts" => TextsItem, _ => PixlinkItem };
    }

    protected override void OnNavigatedTo(Microsoft.UI.Xaml.Navigation.NavigationEventArgs e)
    {
        // Opened from a pinned chat on Home.
        if (e.Parameter is Conversation c)
        {
            ViewPicker.SelectedItem = c.ThreadId is null ? PixlinkItem : TextsItem;
            ConversationList.SelectedItem = c;
        }
    }

    private bool _texts;

    private void OnConversationsChanged(object? sender, System.Collections.Specialized.NotifyCollectionChangedEventArgs e)
    {
        UpdateEmptyList();
        if (!_texts && _current is not null && !Session.Conversations.Contains(_current)) ShowNothingSelected();
    }

    private void UpdateEmptyList() =>
        NoChats.Visibility = (_texts ? Session.SmsThreads.Count : Session.Conversations.Count) == 0 ? Visibility.Visible : Visibility.Collapsed;

    // ---------- Recent chats / All texts ----------

    private async void OnViewChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        var google = sender.SelectedItem == GoogleItem;
        _texts = sender.SelectedItem == TextsItem;
        PixlinkView.Visibility = google ? Visibility.Collapsed : Visibility.Visible;
        GoogleView.Visibility = google ? Visibility.Visible : Visibility.Collapsed;
        var view = google ? "google" : _texts ? "texts" : "pixlink";
        ShowNothingSelected();
        ConversationList.ItemsSource = _texts ? Session.SmsThreads : Session.Conversations;
        UpdateEmptyList();
        if (_texts)
        {
            NoChatsTitle.Text = "Loading your texts…";
            NoChatsText.Text = "";
            await Session.LoadSmsThreadsAsync();
            NoChatsTitle.Text = Session.SmsError is null ? "No texts" : "Allow Pixlink to read texts";
            NoChatsText.Text = Session.SmsError is null ? "Texts on your phone appear here." :
                "On your phone, open Pixlink and allow SMS in Setup. If Android says \"Restricted setting\", open App info → ⋮ → Allow restricted settings first.";
            UpdateEmptyList();
        }
        else { NoChatsTitle.Text = "No chats yet"; NoChatsText.Text = "Messages from any app appear here when they arrive on your phone. For your full text history, open Texts."; }
        if (Session.Settings.MessagesView != view) { Session.Settings.MessagesView = view; Session.Settings.Save(); }
        if (google) await OpenGoogleMessagesAsync();
    }

    /// <summary>
    /// Google Messages for Web gives the full text history. The user pairs it once with their own Google
    /// account; the sign-in is kept in %LOCALAPPDATA%\Pixlink\WebView2 so it survives Pixlink updates.
    /// </summary>
    private async Task OpenGoogleMessagesAsync()
    {
        try
        {
            if (MessagesWeb.CoreWebView2 is null)
            {
                var folder = Path.Combine(AppSettings.Folder, "WebView2");
                Directory.CreateDirectory(folder);
                Environment.SetEnvironmentVariable("WEBVIEW2_USER_DATA_FOLDER", folder);
                await MessagesWeb.EnsureCoreWebView2Async();
                MessagesWeb.NavigationCompleted += (_, _) => WebLoading.Visibility = Visibility.Collapsed;
            }
            if (MessagesWeb.Source is null) MessagesWeb.Source = new Uri("https://messages.google.com/web");
        }
        catch (Exception ex)
        {
            Crash.Log(ex, "Opening Google Messages");
            WebLoading.Children.Clear();
            WebLoading.Children.Add(new TextBlock
            {
                Text = "Google Messages couldn't open. Install the Microsoft Edge WebView2 Runtime, then try again.",
                TextWrapping = TextWrapping.Wrap, MaxWidth = 360, TextAlignment = TextAlignment.Center,
            });
        }
    }

    // ---------- Thread ----------

    private void OnConversationSelected(object sender, SelectionChangedEventArgs e)
    {
        if (ConversationList.SelectedItem is not Conversation c) return;
        _current = c;
        ThreadTitle.Text = c.Name;
        ThreadPicture.DisplayName = c.Name;
        Bubbles.ItemsSource = c.Messages;
        EmptyView.Visibility = Visibility.Collapsed;
        ThreadView.Visibility = Visibility.Visible;
        UpdateThreadState();
        if (c.ThreadId is not null) _ = LoadThreadAsync(c);
        else if (c.Messages.Count > 0) Bubbles.ScrollIntoView(c.Messages[^1]);
    }

    private async Task LoadThreadAsync(Conversation c)
    {
        await Session.LoadSmsMessagesAsync(c);
        if (_current == c && c.Messages.Count > 0) Bubbles.ScrollIntoView(c.Messages[^1]);
    }

    private void ShowNothingSelected()
    {
        _current = null;
        ConversationList.SelectedItem = null;
        ThreadView.Visibility = Visibility.Collapsed;
        EmptyView.Visibility = Visibility.Visible;
    }

    private void UpdateThreadState()
    {
        if (_current is null) return;
        var canReply = _current.ReplyKey is not null || _current.ThreadId is not null;
        ReplyRow.Visibility = canReply ? Visibility.Visible : Visibility.Collapsed;
        NoReplyNote.Visibility = canReply ? Visibility.Collapsed : Visibility.Visible;
        ThreadMarkRead.Visibility = MarkReadAction(_current) is null ? Visibility.Collapsed : Visibility.Visible;
        ThreadPin.IsChecked = _current.IsPinned;
    }

    private async void OnSend(object sender, RoutedEventArgs e)
    {
        if (_current is null || string.IsNullOrWhiteSpace(ReplyBox.Text)) return;
        var text = ReplyBox.Text;
        ReplyBox.Text = "";
        await Session.ReplyAsync(_current, text);
        if (_current.Messages.Count > 0) Bubbles.ScrollIntoView(_current.Messages[^1]);
    }

    private void OnReplyKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == Windows.System.VirtualKey.Enter) { OnSend(sender, e); e.Handled = true; }
    }

    private void OnThreadMarkRead(object sender, RoutedEventArgs e)
    {
        if (_current is not null) MarkRead(_current);
    }

    private void OnThreadPin(object sender, RoutedEventArgs e)
    {
        if (_current is not null) Session.SetPinned(_current, ThreadPin.IsChecked == true);
    }

    private void OnThreadRemove(object sender, RoutedEventArgs e)
    {
        if (_current is not null) Session.RemoveConversation(_current);
    }

    // ---------- Right-click / ⋯ menu ----------

    private void OnMore(object sender, RoutedEventArgs e)
    {
        // The ⋯ button opens the same menu as right-clicking the chat.
        DependencyObject? node = sender as DependencyObject;
        while (node is not null && !(node is UIElement u && u.ContextFlyout is not null)) node = VisualTreeHelper.GetParent(node);
        if (node is UIElement owner) owner.ContextFlyout.ShowAt((FrameworkElement)sender);
    }

    private void OnMenuOpening(object? sender, object e)
    {
        if (sender is not MenuFlyout menu || menu.Items[0].Tag is not Conversation c) return;
        menu.Items[0].Visibility = c.IsPinned ? Visibility.Collapsed : Visibility.Visible;  // Pin to top
        menu.Items[1].Visibility = c.IsPinned ? Visibility.Visible : Visibility.Collapsed;  // Unpin
        menu.Items[2].Visibility = MarkReadAction(c) is null ? Visibility.Collapsed : Visibility.Visible;
    }

    private void OnPin(object sender, RoutedEventArgs e) { if (Tagged(sender) is { } c) { Session.SetPinned(c, true); UpdateThreadState(); } }
    private void OnUnpin(object sender, RoutedEventArgs e) { if (Tagged(sender) is { } c) { Session.SetPinned(c, false); UpdateThreadState(); } }
    private void OnMarkRead(object sender, RoutedEventArgs e) { if (Tagged(sender) is { } c) MarkRead(c); }
    private void OnRemove(object sender, RoutedEventArgs e) { if (Tagged(sender) is { } c) Session.RemoveConversation(c); }

    private static Conversation? Tagged(object sender) => (sender as FrameworkElement)?.Tag as Conversation;

    private static NotifAction? MarkReadAction(Conversation c) => c.LatestActions.FirstOrDefault(a => a.Semantic == "markAsRead");

    private void MarkRead(Conversation c)
    {
        if (MarkReadAction(c) is not { } a) return;
        _ = Session.ActionAsync(a);
        c.LatestActions = c.LatestActions.Where(x => x != a).ToList();
        if (c == _current) UpdateThreadState();
    }
}
