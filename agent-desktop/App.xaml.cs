using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Markup;

namespace MHarness.Desktop;

/// <summary>
/// WinUI 应用入口：启动后创建并激活主窗口。
/// </summary>
public partial class App : Application
{
    private Window? window;

    public App()
    {
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            if (e.ExceptionObject is Exception ex)
            {
                CrashLog.Write("AppDomain", ex);
            }
        };
        AppDomain.CurrentDomain.FirstChanceException += (_, e) =>
        {
            if (e.Exception is XamlParseException)
            {
                CrashLog.Write("FirstChance", e.Exception);
            }
        };
        UnhandledException += (_, e) => CrashLog.Write("UnhandledException", e.Exception);
        InitializeComponent();
    }

    /// <summary>
    /// 系统启动应用时打开 <see cref="MainWindow"/>。
    /// </summary>
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            window = new MainWindow();
            window.Activate();
        }
        catch (Exception ex)
        {
            CrashLog.Write("OnLaunched", ex);
            throw;
        }
    }
}
