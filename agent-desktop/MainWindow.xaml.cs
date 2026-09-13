using System.Text.Json;
using Microsoft.UI;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.Storage.Pickers;
using Windows.System;
using WinRT.Interop;

namespace MHarness.Desktop;

/// <summary>
/// 主窗口：启动本机 Java 服务，选择工作区，以 Ask/Agent 发任务，处理 SSE 事件、批准框、设置和回滚。
/// </summary>
public sealed partial class MainWindow : Window
{
    private ServerProcess? server;
    private AgentApiClient? client;
    private CancellationTokenSource? runCts;
    private string? currentRunId;
    private bool running;
    private bool agentMode;

    public MainWindow()
    {
        InitializeComponent();
        Title = "M Bot";
        string icon = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
        if (File.Exists(icon))
        {
            AppWindow.SetIcon(icon);
        }
        ApplyWin11TitleBar();
        Closed += MainWindow_Closed;
        Activated += MainWindow_Activated;
        UpdateModeControls();
    }

    /// <summary>
    /// 用 WinUI TitleBar + Mica 替换系统默认标题栏，让顶栏和内容区是同一块 Win11 表面。
    /// </summary>
    private void ApplyWin11TitleBar()
    {
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        string png = Path.Combine(AppContext.BaseDirectory, "Assets", "m-harness-icon.png");
        if (File.Exists(png))
        {
            AppTitleBar.IconSource = new BitmapIconSource
            {
                UriSource = new Uri(png),
                ShowAsMonochrome = false,
            };
        }

        ApplyCaptionButtonColors();
    }

    /// <summary>
    /// 系统标题栏按钮叠在 Mica 上；悬停色（含关闭键红色）交给系统。
    /// </summary>
    private void ApplyCaptionButtonColors()
    {
        var titleBar = AppWindow.TitleBar;
        titleBar.ButtonBackgroundColor = Colors.Transparent;
        titleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
        titleBar.ButtonHoverBackgroundColor = null;
        titleBar.ButtonPressedBackgroundColor = null;
        titleBar.ButtonForegroundColor = null;
        titleBar.ButtonHoverForegroundColor = null;
        titleBar.ButtonPressedForegroundColor = null;
        titleBar.ButtonInactiveForegroundColor = null;
    }

    /// <summary>
    /// 窗口第一次激活时再启动 Java 服务，避免在构造函数里做耗时 IO。
    /// </summary>
    private async void MainWindow_Activated(object sender, WindowActivatedEventArgs args)
    {
        Activated -= MainWindow_Activated;
        await StartServerAsync();
    }

    /// <summary>
    /// 拉起 server jar、创建 API 客户端并做健康检查。失败则禁用发送按钮。
    /// </summary>
    private async Task StartServerAsync()
    {
        try
        {
            SetStatus("正在启动本地服务…");
            server = await Task.Run(ServerProcess.Start);
            client = new AgentApiClient(server.Port, server.Token);
            bool ok = await client.HealthAsync(CancellationToken.None);
            SetStatus(ok ? "Bot 已就绪" : "服务已启动但健康检查失败");
        }
        catch (Exception ex)
        {
            SetStatus("启动失败");
            Append(TranscriptBox, TranscriptScroll, "无法启动本地 Java 服务：\n" + ex.Message + "\n");
            SendButton.IsEnabled = false;
        }
    }

    /// <summary>
    /// 关窗口时取消当前任务并结束 Java 子进程。
    /// </summary>
    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        try
        {
            runCts?.Cancel();
        }
        catch
        {
            // ignore
        }
        client?.Dispose();
        server?.Dispose();
    }

    /// <summary>
    /// 打开文件夹选择器，选定后刷新 checkpoint 状态。
    /// </summary>
    private async void PickWorkspace_Click(object sender, RoutedEventArgs e)
    {
        var picker = new FolderPicker();
        picker.SuggestedStartLocation = PickerLocationId.ComputerFolder;
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var folder = await picker.PickSingleFolderAsync();
        if (folder != null)
        {
            WorkspaceBox.Text = folder.Path;
            await RefreshCheckpointAsync(folder.Path);
        }
    }

    /// <summary>
    /// 切到 Ask：只读问答，禁用 dry-run / 自动批准。
    /// </summary>
    private void SelectAskMode_Click(object sender, RoutedEventArgs e)
    {
        SetAgentMode(false);
    }

    /// <summary>
    /// 切到 Agent：允许写操作与自动批准。
    /// </summary>
    private void SelectAgentMode_Click(object sender, RoutedEventArgs e)
    {
        SetAgentMode(true);
    }

    /// <summary>
    /// 更新模式按钮文案，并刷新 dry-run、自动批准是否可点。
    /// </summary>
    private void SetAgentMode(bool agent)
    {
        agentMode = agent;
        if (ModeButton != null)
        {
            ModeButton.Content = agent ? "Agent" : "Ask";
        }
        if (AskModeItem != null)
        {
            AskModeItem.IsChecked = !agent;
        }
        if (AgentModeItem != null)
        {
            AgentModeItem.IsChecked = agent;
        }
        UpdateModeControls();
    }

    /// <summary>
    /// Ask 模式没有写操作，禁用 dry-run / 自动批准。
    /// </summary>
    private void UpdateModeControls()
    {
        bool agent = IsAgentMode();
        if (DryRunBox != null)
        {
            DryRunBox.IsEnabled = agent;
        }
        if (AutoApproveBox != null)
        {
            AutoApproveBox.IsEnabled = agent;
        }
    }

    /// <summary>
    /// 当前是否为 Agent 模式。
    /// </summary>
    private bool IsAgentMode()
    {
        return agentMode;
    }

    /// <summary>
    /// Enter 发送当前输入；Shift+Enter 换行。空内容不发送。
    /// </summary>
    private void PromptBox_PreviewKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter)
        {
            return;
        }
        var shift = InputKeyboardSource.GetKeyStateForCurrentThread(VirtualKey.Shift);
        if ((shift & Windows.UI.Core.CoreVirtualKeyStates.Down) == Windows.UI.Core.CoreVirtualKeyStates.Down)
        {
            return;
        }
        e.Handled = true;
        Send_Click(sender, e);
    }

    /// <summary>
    /// 发送任务：StartRun → 订阅 SSE → 按事件更新对话/日志/批准框。
    /// </summary>
    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        if (running || client == null)
        {
            return;
        }
        string workspace = WorkspaceBox.Text.Trim();
        string prompt = PromptBox.Text.Trim();
        if (workspace.Length == 0)
        {
            SetStatus("请先选择工作区");
            return;
        }
        if (prompt.Length == 0)
        {
            SetStatus("请输入任务");
            return;
        }

        PromptBox.Text = "";
        running = true;
        SendButton.IsEnabled = false;
        StopButton.IsEnabled = true;
        PromptBox.IsEnabled = false;
        runCts = new CancellationTokenSource();
        currentRunId = null;
        Append(TranscriptBox, TranscriptScroll, "\n你：\n" + prompt + "\n\n助手：\n");
        try
        {
            currentRunId = await client.StartRunAsync(new RunRequest
            {
                Workspace = workspace,
                Prompt = prompt,
                Mode = IsAgentMode() ? "AGENT" : "ASK",
                DryRun = DryRunBox.IsChecked == true,
                AutoApprove = AutoApproveBox.IsChecked == true,
            }, runCts.Token);
            SetStatus("运行中 " + currentRunId);
            await foreach (JsonElement evt in client.StreamEventsAsync(currentRunId, runCts.Token))
            {
                await HandleEventAsync(currentRunId, evt, runCts.Token);
            }
        }
        catch (OperationCanceledException)
        {
            SetStatus("已取消");
        }
        catch (Exception ex)
        {
            Append(TranscriptBox, TranscriptScroll, "\n[错误] " + ex.Message + "\n");
            SetStatus("失败");
        }
        finally
        {
            running = false;
            currentRunId = null;
            runCts?.Dispose();
            runCts = null;
            SendButton.IsEnabled = client != null;
            StopButton.IsEnabled = false;
            PromptBox.IsEnabled = true;
        }
    }

    /// <summary>
    /// 处理一条 SSE：token 追加到对话；tool 写日志；approval 弹框；done 收尾。
    /// </summary>
    private async Task HandleEventAsync(string runId, JsonElement evt, CancellationToken cancellationToken)
    {
        string type = evt.TryGetProperty("type", out var typeNode) ? typeNode.GetString() ?? "" : "";
        switch (type)
        {
            case "token":
                if (evt.TryGetProperty("text", out var text))
                {
                    TranscriptBox.Text += text.GetString();
                    ScrollToEnd(TranscriptScroll);
                }
                break;
            case "tool":
                string name = evt.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                string status = evt.TryGetProperty("status", out var s) ? s.GetString() ?? "" : "";
                int chars = evt.TryGetProperty("chars", out var c) && c.ValueKind == JsonValueKind.Number ? c.GetInt32() : 0;
                Append(LogBox, LogScroll, $"{name} {status} ({chars} chars)\n");
                break;
            case "approval":
                string requestId = evt.GetProperty("requestId").GetString() ?? "";
                string toolName = evt.TryGetProperty("toolName", out var tn) ? tn.GetString() ?? "" : "";
                string arguments = evt.TryGetProperty("arguments", out var a) ? a.GetString() ?? "" : "";
                bool approved = await ConfirmApprovalAsync(toolName, arguments);
                if (client != null)
                {
                    await client.ApproveAsync(runId, requestId, approved, cancellationToken);
                }
                Append(LogBox, LogScroll, (approved ? "已批准 " : "已拒绝 ") + toolName + "\n");
                break;
            case "done":
                string doneStatus = evt.TryGetProperty("status", out var ds) ? ds.GetString() ?? "" : "";
                string? doneText = evt.TryGetProperty("text", out var dt) && dt.ValueKind == JsonValueKind.String ? dt.GetString() : null;
                string? error = evt.TryGetProperty("error", out var de) && de.ValueKind == JsonValueKind.String ? de.GetString() : null;
                // 流式 token 已经打过最终文本时不再重复追加。
                if (!string.IsNullOrWhiteSpace(doneText) && (TranscriptBox.Text.Length == 0 || !TranscriptBox.Text.EndsWith(doneText, StringComparison.Ordinal)))
                {
                    Append(TranscriptBox, TranscriptScroll, "\n" + doneText + "\n");
                }
                if (!string.IsNullOrWhiteSpace(error))
                {
                    Append(TranscriptBox, TranscriptScroll, "\n[失败] " + error + "\n");
                }
                if (evt.TryGetProperty("checkpoint", out var cp) && cp.ValueKind == JsonValueKind.Object)
                {
                    string id = cp.TryGetProperty("checkpointId", out var cid) ? cid.GetString() ?? "" : "";
                    Append(LogBox, LogScroll, "checkpoint 保留：" + id + "\n");
                }
                SetStatus("结束：" + doneStatus);
                break;
        }
    }

    /// <summary>
    /// 变更类工具需要确认时弹出对话框；主按钮=批准。
    /// </summary>
    private async Task<bool> ConfirmApprovalAsync(string toolName, string arguments)
    {
        var dialog = new ContentDialog
        {
            Title = "批准执行 " + toolName + " ?",
            Content = new ScrollViewer
            {
                Content = new TextBlock
                {
                    Text = arguments,
                    TextWrapping = TextWrapping.Wrap,
                    IsTextSelectionEnabled = true,
                },
                MaxHeight = 280,
            },
            PrimaryButtonText = "批准",
            CloseButtonText = "拒绝",
            XamlRoot = Content.XamlRoot,
        };
        ContentDialogResult result = await dialog.ShowAsync();
        return result == ContentDialogResult.Primary;
    }

    /// <summary>
    /// 通知服务端取消当前 run，并取消本机 SSE 读取。
    /// </summary>
    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        string? runId = currentRunId;
        try
        {
            if (client != null && runId != null)
            {
                await client.CancelAsync(runId, CancellationToken.None);
            }
        }
        catch (Exception ex)
        {
            Append(LogBox, LogScroll, "取消失败：" + ex.Message + "\n");
        }
        runCts?.Cancel();
    }

    /// <summary>
    /// 读取全局配置填表，保存时只提交非空 API Key，避免在界面回显完整密钥。
    /// 不依赖当前工作区，所有仓库共用同一份设置。
    /// </summary>
    private async void Settings_Click(object sender, RoutedEventArgs e)
    {
        if (client == null)
        {
            return;
        }

        string baseUrl = "https://api.openai.com/v1";
        string model = "gpt-4o-mini";
        bool hasApiKey = false;
        string apiKeyHint = "";
        try
        {
            JsonElement settings = await client.GetSettingsAsync(CancellationToken.None);
            if (settings.TryGetProperty("baseUrl", out var bu) && bu.GetString() is { Length: > 0 } b)
            {
                baseUrl = b;
            }
            if (settings.TryGetProperty("model", out var mo) && mo.GetString() is { Length: > 0 } m)
            {
                model = m;
            }
            hasApiKey = settings.TryGetProperty("hasApiKey", out var hak) && hak.ValueKind == JsonValueKind.True;
            if (settings.TryGetProperty("apiKeyHint", out var hint) && hint.GetString() is { Length: > 0 } h)
            {
                apiKeyHint = h;
            }
        }
        catch (Exception ex)
        {
            Append(LogBox, LogScroll, "读取设置失败：" + ex.Message + "\n");
        }

        var baseUrlBox = new TextBox { Header = "Base URL", Text = baseUrl };
        var apiKeyBox = new PasswordBox
        {
            Header = hasApiKey
                ? (string.IsNullOrEmpty(apiKeyHint)
                    ? "API Key（已设置，留空则保持原值）"
                    : "API Key（已设置 …" + apiKeyHint + "，留空则保持原值）")
                : "API Key（尚未设置）",
            PlaceholderText = hasApiKey ? "已保存，留空不修改" : "输入 API Key",
        };
        var modelBox = new TextBox { Header = "Model", Text = model };
        var panel = new StackPanel { Spacing = 8 };
        panel.Children.Add(new TextBlock
        {
            Text = "对所有工作区生效，保存在用户目录 .m-harness",
            Opacity = 0.72,
            TextWrapping = TextWrapping.Wrap,
        });
        panel.Children.Add(baseUrlBox);
        panel.Children.Add(apiKeyBox);
        panel.Children.Add(modelBox);

        var dialog = new ContentDialog
        {
            Title = "全局模型设置",
            Content = panel,
            PrimaryButtonText = "保存",
            CloseButtonText = "取消",
            XamlRoot = Content.XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }
        try
        {
            string? apiKey = string.IsNullOrWhiteSpace(apiKeyBox.Password) ? null : apiKeyBox.Password;
            await client.SaveSettingsAsync(baseUrlBox.Text, apiKey, modelBox.Text, CancellationToken.None);
            SetStatus("设置已保存到全局配置");
        }
        catch (Exception ex)
        {
            SetStatus("保存失败：" + ex.Message);
        }
    }

    /// <summary>
    /// 确认后把工作区回滚到最近 checkpoint。
    /// </summary>
    private async void Rollback_Click(object sender, RoutedEventArgs e)
    {
        if (client == null)
        {
            return;
        }
        string workspace = WorkspaceBox.Text.Trim();
        if (workspace.Length == 0)
        {
            SetStatus("请先选择工作区");
            return;
        }
        var confirm = new ContentDialog
        {
            Title = "回滚工作区",
            Content = "恢复到最近一次 checkpoint？未提交的改动会被覆盖。",
            PrimaryButtonText = "回滚",
            CloseButtonText = "取消",
            XamlRoot = Content.XamlRoot,
        };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }
        try
        {
            await client.RollbackAsync(workspace, CancellationToken.None);
            SetStatus("已回滚");
            Append(LogBox, LogScroll, "已回滚到最近 checkpoint\n");
        }
        catch (Exception ex)
        {
            SetStatus("回滚失败：" + ex.Message);
        }
    }

    /// <summary>
    /// 选择工作区后查询是否已有 checkpoint，写到状态栏。
    /// </summary>
    private async Task RefreshCheckpointAsync(string workspace)
    {
        if (client == null)
        {
            SetStatus("工作区：" + workspace);
            return;
        }
        try
        {
            JsonElement json = await client.GetCheckpointAsync(workspace, CancellationToken.None);
            bool hasCheckpoint = json.TryGetProperty("hasCheckpoint", out var hc) && hc.ValueKind == JsonValueKind.True;
            if (json.TryGetProperty("checkpoint", out var cp) && cp.ValueKind == JsonValueKind.Object)
            {
                string id = cp.TryGetProperty("checkpointId", out var cid) ? cid.GetString() ?? "" : "";
                SetStatus("工作区：" + workspace + "  checkpoint=" + id);
            }
            else if (hasCheckpoint)
            {
                SetStatus("工作区：" + workspace + "  checkpoint 已存在");
            }
            else
            {
                SetStatus("工作区：" + workspace + "  没有 checkpoint");
            }
        }
        catch (Exception ex)
        {
            SetStatus("工作区：" + workspace + "  (" + ex.Message + ")");
        }
    }

    /// <summary>
    /// 更新窗口底部状态栏。
    /// </summary>
    private void SetStatus(string text)
    {
        StatusText.Text = text;
    }

    /// <summary>
    /// 追加文本并滚到最底，用于对话区和工具日志。
    /// </summary>
    private static void Append(TextBox box, ScrollViewer scroll, string text)
    {
        box.Text += text;
        ScrollToEnd(scroll);
    }

    /// <summary>
    /// 把滚动条拉到最底，用于流式 token 追加时始终看到最新内容。
    /// </summary>
    private static void ScrollToEnd(ScrollViewer scroll)
    {
        scroll.UpdateLayout();
        scroll.ChangeView(null, scroll.ScrollableHeight, null);
    }
}
