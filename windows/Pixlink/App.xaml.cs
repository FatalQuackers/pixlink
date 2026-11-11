using Microsoft.UI.Xaml;
using Pixlink.Services;

namespace Pixlink;

public partial class App : Application
{
    /// <summary>The one paired phone and everything we know about it.</summary>
    public static PhoneSession Session { get; } = new();

    public static MainWindow? Window { get; private set; }

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Toasts.Register();
        Window = new MainWindow();
        Window.Activate();
        _ = Session.StartAsync(Window.DispatcherQueue);
    }
}
