using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using Pixlink.Models;

namespace Pixlink.Services;

/// <summary>Windows notifications for phone notifications, with a reply box for messages.</summary>
public static class Toasts
{
    public static void Register()
    {
        AppNotificationManager.Default.NotificationInvoked += (sender, args) =>
        {
            if (!args.Arguments.TryGetValue("key", out var key)) return;
            if (args.Arguments.TryGetValue("action", out var action) && action == "reply"
                && args.UserInput.TryGetValue("reply", out var text) && !string.IsNullOrWhiteSpace(text))
            {
                App.Window?.DispatcherQueue.TryEnqueue(() => _ = App.Session.ReplyToKeyAsync(key, text));
            }
            else
            {
                App.Window?.DispatcherQueue.TryEnqueue(() => App.Window?.BringToFront());
            }
        };
        try { AppNotificationManager.Default.Register(); }
        catch (Exception) { /* notifications unavailable (e.g. disabled by policy) */ }
    }

    public static void Show(PhoneNotification n)
    {
        try
        {
            var builder = new AppNotificationBuilder()
                .AddArgument("key", n.Key)
                .AddText(string.IsNullOrEmpty(n.Title) ? n.AppName : n.Title)
                .AddText(n.Text)
                .SetAttributionText($"{n.AppName} · {App.Session.PhoneName}");

            if (n.CanReply)
            {
                builder.AddTextBox("reply", "Type a reply", "")
                       .AddButton(new AppNotificationButton("Send")
                           .AddArgument("key", n.Key)
                           .AddArgument("action", "reply")
                           .SetInputId("reply"));
            }
            AppNotificationManager.Default.Show(builder.BuildNotification());
        }
        catch (Exception) { /* ignore toast failures */ }
    }
}
