using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Pixlink.Models;
using Pixlink.Services;

namespace Pixlink.Pages;

public sealed partial class CallsPage : Page
{
    private PhoneSession Session => App.Session;
    private List<Contact> _contacts = new();

    public CallsPage()
    {
        InitializeComponent();
        BuildDialPad();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        Session.PropertyChanged += OnSessionChanged;
        UpdateCallBanner();
        BluetoothBar.IsOpen = !await BluetoothCalls.IsPhonePairedAsync(Session.PhoneName);
        await LoadRecentsAsync();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => Session.PropertyChanged -= OnSessionChanged;

    private void OnSessionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PhoneSession.CallState)) UpdateCallBanner();
    }

    private void UpdateCallBanner()
    {
        var state = Session.CallState;
        CallBanner.Visibility = state == "ended" ? Visibility.Collapsed : Visibility.Visible;
        CallStateText.Text = state == "ringing" ? "Incoming call" : "On a call";
        AnswerButton.Visibility = state == "ringing" ? Visibility.Visible : Visibility.Collapsed;
    }

    private void BuildDialPad()
    {
        string[] keys = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#" };
        for (var i = 0; i < keys.Length; i++)
        {
            var key = keys[i];
            var b = new Button { Content = key, FontSize = 20, HorizontalAlignment = HorizontalAlignment.Stretch, Height = 52 };
            b.Click += (_, _) => NumberBox.Text += key;
            Grid.SetRow(b, i / 3);
            Grid.SetColumn(b, i % 3);
            Pad.Children.Add(b);
        }
    }

    private async Task LoadRecentsAsync()
    {
        var calls = await Session.GetCallLogAsync();
        PowerModeNote.Visibility = calls is null ? Visibility.Visible : Visibility.Collapsed;
        RecentList.ItemsSource = calls;
    }

    private async void OnListTab(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (ContactList is null || Search is null) return; // fires during InitializeComponent
        var contacts = (sender.SelectedItem?.Tag as string) == "contacts";
        Search.Visibility = contacts ? Visibility.Visible : Visibility.Collapsed;
        ContactList.Visibility = contacts ? Visibility.Visible : Visibility.Collapsed;
        RecentList.Visibility = contacts ? Visibility.Collapsed : Visibility.Visible;
        if (contacts)
        {
            PowerModeNote.Visibility = Visibility.Collapsed;
            if (_contacts.Count == 0) _contacts = await Session.GetContactsAsync();
            ContactList.ItemsSource = _contacts;
        }
        else
        {
            await LoadRecentsAsync();
        }
    }

    private void OnSearch(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args)
    {
        var q = sender.Text.Trim();
        ContactList.ItemsSource = q.Length == 0
            ? _contacts
            : _contacts.Where(c => c.Name.Contains(q, StringComparison.OrdinalIgnoreCase) || c.Numbers.Any(n => n.Contains(q))).ToList();
    }

    private void OnRecentClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is CallEntry c) NumberBox.Text = c.Number;
    }

    private void OnContactClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is Contact c) NumberBox.Text = c.FirstNumber;
    }

    private void OnCall(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrWhiteSpace(NumberBox.Text)) _ = Session.DialAsync(NumberBox.Text.Trim());
    }

    private void OnAnswer(object sender, RoutedEventArgs e) => _ = Session.AnswerAsync();
    private void OnHangUp(object sender, RoutedEventArgs e) => _ = Session.HangUpAsync();
    private void OnOpenBluetooth(object sender, RoutedEventArgs e) => _ = BluetoothCalls.OpenBluetoothSettingsAsync();
}
