using System.Collections.ObjectModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Pixlink.Models;
using Pixlink.Services;

namespace Pixlink.Pages;

/// <summary>Home: the phone at a glance, plus everything you pinned.</summary>
public sealed partial class HomePage : Page
{
    public PhoneSession Session => App.Session;
    public ObservableCollection<Conversation> PinnedConversations { get; } = new();

    public HomePage() { InitializeComponent(); }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        Session.Conversations.CollectionChanged += OnChanged;
        Session.PinnedNotifications.CollectionChanged += OnChanged;
        Refresh();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        Session.Conversations.CollectionChanged -= OnChanged;
        Session.PinnedNotifications.CollectionChanged -= OnChanged;
    }

    private void OnChanged(object? s, System.Collections.Specialized.NotifyCollectionChangedEventArgs e) => Refresh();

    private void Refresh()
    {
        PinnedConversations.Clear();
        foreach (var c in Session.Conversations.Where(c => c.IsPinned)) PinnedConversations.Add(c);
        NoPins.Visibility = PinnedConversations.Count == 0 && Session.PinnedNotifications.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    public string ControlText(bool canControl, bool power) => canControl ? (power ? "On (Power mode)" : "On") : "View only";
    public string ControlHint(bool canControl) => canControl ? "Click and type on your phone's screen" : "Turn on Screen control in Pixlink on your phone";

    private void OnDnd(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleSwitch t && t.IsOn != Session.DoNotDisturb) _ = Session.SetDndAsync(t.IsOn);
    }

    private void OnOpenChat(object sender, RoutedEventArgs e) => App.Window?.SelectTab("Messages", (sender as FrameworkElement)?.Tag);
    private void OnOpenOnPhone(object sender, RoutedEventArgs e) { if ((sender as FrameworkElement)?.Tag is PhoneNotification n) App.Window?.OpenOnPhone(n.Key); }
    private void OnUnpin(object sender, RoutedEventArgs e) { if ((sender as FrameworkElement)?.Tag is PhoneNotification n) Session.TogglePin(n); }
}
