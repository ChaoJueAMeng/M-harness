using Microsoft.UI.Xaml;

namespace MHarness.Desktop;

/// <summary>
/// WinUI 应用入口：启动后创建并激活主窗口。
/// </summary>
public partial class App : Application
{
    private Window? window;

    public App()
    {
        InitializeComponent();
    }

    /// <summary>
    /// 系统启动应用时打开 <see cref="MainWindow"/>。
    /// </summary>
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        window = new MainWindow();
        window.Activate();
    }
}
