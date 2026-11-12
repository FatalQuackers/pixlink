using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using Pixlink.Models;

namespace Pixlink.Services;

/// <summary>
/// Windows pop-ups for phone notifications: a reply box for messages, the app's own buttons
/// (Mark as read, Like…) and Dismiss — all of them act on the phone.
/// </summary>
public static class Toasts
{
    public static void Register()
    {
        AppNotificationManager.Default.NotificationInvoked += (sender, args) =>
        {
            if (!args.Arguments.TryGetValue("key", out var key)) return;
            args.Arguments.TryGetValue("action", out var action);
            App.Window?.DispatcherQueue.TryEnqueue(() =>
            {
                switch (action)
                {
                    case "reply" when args.UserInput.TryGetValue("reply", out var text) && !string.IsNullOrWhiteSpace(text):
                        _ = App.Session.ReplyToKeyAsync(key, text);
                        break;
                    case "act" when args.Arguments.TryGetValue("index", out var i) && int.TryParse(i, out var index):
                        _ = App.Session.ActionAsync(key, index);
                        break;
                    case "dismiss":
                        _ = App.Session.DismissAsync(key);
                        break;
                    default:
                        // Clicking the pop-up itself: open Pixlink with that notification expanded.
                        App.Window?.ShowNotification(key);
                        break;
                }
            });
        };
        try { AppNotificationManager.Default.Register(); }
        catch (Exception) { /* notifications unavailable (e.g. disabled by policy) */ }
    }

    /// <summary>A plain Pixlink message (low battery, file received, copied text…).</summary>
    public static void ShowSimple(string title, string text)
    {
        try
        {
            AppNotificationManager.Default.Show(new AppNotificationBuilder().AddText(title).AddText(text).BuildNotification());
        }
        catch (Exception) { /* notifications unavailable */ }
    }

    public static void Show(PhoneNotification n)
    {
        var settings = App.Session.Settings;
        if (settings.MutedApps.Contains(n.App)) return;
        try
        {
            var builder = new AppNotificationBuilder()
                .AddArgument("key", n.Key)
                .AddText(string.IsNullOrEmpty(n.Title) ? n.AppName : n.Title)
                .AddText(settings.ToastShowContent ? n.Text : "New notification")
                .SetAttributionText($"{n.AppName} · {App.Session.PhoneName}");

            if (!settings.ToastSound) builder.MuteAudio();

            // Windows allows 5 buttons: Reply (if possible), then the app's buttons, then Dismiss.
            var slots = 5;
            if (n.CanReply)
            {
                builder.AddTextBox("reply", "Type a reply", "")
                       .AddButton(new AppNotificationButton("Reply")
                           .AddArgument("key", n.Key)
                           .AddArgument("action", "reply")
                           .SetInputId("reply"));
                slots--;
            }
            // Mark as read first: it's the one people want most.
            var actions = n.Actions.OrderBy(a => a.Semantic == "markAsRead" ? 0 : 1).Take(Math.Max(0, slots - 1));
            foreach (var a in actions)
            {
                builder.AddButton(new AppNotificationButton(a.Title)
                    .AddArgument("key", n.Key)
                    .AddArgument("action", "act")
                    .AddArgument("index", a.Index.ToString()));
            }
            builder.AddButton(new AppNotificationButton("Dismiss")
                .AddArgument("key", n.Key)
                .AddArgument("action", "dismiss"));

            AppNotificationManager.Default.Show(builder.BuildNotification());
        }
        catch (Exception ex) { Crash.Log(ex, "Showing pop-up"); }
    }
}
