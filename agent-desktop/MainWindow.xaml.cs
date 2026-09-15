using System.Text.Json;
using Microsoft.UI;
using Microsoft.UI.Input;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Input;
using Windows.Storage.Pickers;
using Windows.System;
using WinRT.Interop;

namespace MHarness.Desktop;

/// <summary>
/// 主窗口：左侧本地会话历史，中间对话；新建对话时选择工作区目录。
/// </summary>
public sealed partial class MainWindow : Window
{
    private readonly ConversationStore store;
    private ServerProcess? server;
    private AgentApiClient? client;
    private CancellationTokenSource? runCts;
    private readonly CancellationTokenSource lifetimeCts = new();
    private string? currentRunId;
    private bool running;
    private bool agentMode;
    private bool toolLogOpen;
    private bool suppressSelection;
    private Conversation? current;
    private MarkdownBlockHost? streamingMarkdown;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? markdownTimer;
    private CollectionViewSource conversationViewSource = null!;
    private bool sidebarReady;

    public MainWindow()
    {
        try
        {
            store = ConversationStore.Open();
            InitializeComponent();
            conversationViewSource = (CollectionViewSource)((FrameworkElement)Content).Resources["GroupedConversations"];
            Title = "M Bot";
            MaximizeOnLaunch();
            string icon = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
            if (File.Exists(icon))
            {
                AppWindow.SetIcon(icon);
            }
            ApplyWin11TitleBar();
            Closed += MainWindow_Closed;
            Activated += MainWindow_Activated;
            UpdateModeControls();
            if (Content is FrameworkElement root)
            {
                root.Loaded += MainWindow_Loaded;
            }
            else
            {
                sidebarReady = true;
                RestoreConversations();
                ApplyConversationLayout();
                UpdateSendEnabled();
            }
        }
        catch (Exception ex)
        {
            CrashLog.Write("MainWindow.ctor", ex);
            throw;
        }
    }

    private void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement root)
        {
            root.Loaded -= MainWindow_Loaded;
        }
        try
        {
            sidebarReady = true;
            RestoreConversations();
            ApplyConversationLayout();
            UpdateSendEnabled();
        }
        catch (Exception ex)
        {
            CrashLog.Write("MainWindow.Loaded", ex);
            SetStatus("恢复会话失败：" + ex.Message);
        }
    }

    /// <summary>
    /// 启动时最大化到当前显示器工作区，保留标题栏，方便还原成普通窗口。
    /// </summary>
    private void MaximizeOnLaunch()
    {
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.Maximize();
        }
    }

    /// <summary>
    /// 用 WinUI TitleBar + Mica 替换系统默认标题栏，让顶栏和内容区是同一块 Win11 表面。
    /// </summary>
    private void ApplyWin11TitleBar()
    {
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
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
        MaximizeOnLaunch();
        await StartServerAsync();
        if (current != null)
        {
            await RefreshCheckpointAsync(current.Workspace);
        }
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
            SetStatus("启动失败：" + ex.Message);
            client = null;
        }
        UpdateSendEnabled();
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
        try
        {
            lifetimeCts.Cancel();
        }
        catch
        {
            // ignore
        }
        if (current != null)
        {
            try
            {
                store.Save(current);
                store.SetLastOpened(current.Id);
            }
            catch
            {
                // ignore
            }
        }
        client?.Dispose();
        server?.Dispose();
    }

    /// <summary>
    /// 扫描本地会话并恢复上次打开的对话。
    /// </summary>
    private void RestoreConversations()
    {
        RefreshSidebar();
        Conversation? last = store.Find(store.LastOpenedId);
        if (last != null)
        {
            ShowConversation(last, selectInList: true);
        }
    }

    /// <summary>
    /// 新建对话：先选工作区目录，取消则不创建。
    /// </summary>
    private async void NewChat_Click(object sender, RoutedEventArgs e)
    {
        if (running)
        {
            SetStatus("请等待当前任务结束再新建对话");
            return;
        }
        string? path = await PickWorkspacePathAsync();
        if (path == null)
        {
            return;
        }
        await OpenNewConversationAsync(path);
    }

    /// <summary>
    /// 在指定工作区新建对话：分组头按钮 Tag 是 ConversationGroup，右键菜单 Tag 是路径。
    /// </summary>
    private async void AddWorkspaceChat_Click(object sender, RoutedEventArgs e)
    {
        if (running)
        {
            SetStatus("请等待当前任务结束再新建对话");
            return;
        }
        string? path = WorkspaceFromSource((sender as FrameworkElement)?.Tag)
            ?? WorkspaceFromGroupHeader(sender)
            ?? current?.Workspace;
        if (string.IsNullOrWhiteSpace(path))
        {
            SetStatus("无法识别该工作区，请用顶部「新建对话」选择目录");
            return;
        }
        await OpenNewConversationAsync(path);
    }

    /// <summary>
    /// 右键删除侧栏对话：确认后删本地文件；若删的是当前对话则切到同工作区下一条，没有则清空主区。
    /// </summary>
    private async void DeleteConversation_Click(object sender, RoutedEventArgs e)
    {
        Conversation? conversation = ConversationFromMenu(sender);
        if (conversation == null)
        {
            SetStatus("无法识别要删除的对话");
            return;
        }
        if (running)
        {
            SetStatus("任务进行中，不能删除对话");
            return;
        }
        var confirm = new ContentDialog
        {
            Title = "删除对话",
            Content = "删除「" + conversation.Title + "」？此操作不可恢复。",
            PrimaryButtonText = "删除",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = Content.XamlRoot,
        };
        if (await confirm.ShowAsync() != ContentDialogResult.Primary)
        {
            return;
        }
        bool wasCurrent = current != null && current.Id == conversation.Id;
        if (!store.Delete(conversation.Id))
        {
            SetStatus("对话不存在或已被删除");
            RefreshSidebar();
            return;
        }
        if (wasCurrent)
        {
            Conversation? next = NextConversationAfterDelete(conversation);
            if (next == null)
            {
                ClearConversationView();
                RefreshSidebar();
            }
            else
            {
                RefreshSidebar();
                ShowConversation(next, selectInList: true);
                _ = RefreshCheckpointAsync(next.Workspace);
            }
        }
        else
        {
            RefreshSidebar();
        }
        SetStatus("已删除对话");
    }

    private Conversation? ConversationFromMenu(object sender)
    {
        if (sender is not FrameworkElement element)
        {
            return null;
        }
        if (element.Tag is Conversation tagged)
        {
            return store.Find(tagged.Id) ?? tagged;
        }
        if (element.Tag is string id)
        {
            return store.Find(id);
        }
        return null;
    }

    private Conversation? NextConversationAfterDelete(Conversation deleted)
    {
        Conversation? sameWorkspace = store.All
            .Where(item => string.Equals(item.Workspace, deleted.Workspace, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(item => item.UpdatedAt)
            .FirstOrDefault();
        if (sameWorkspace != null)
        {
            return sameWorkspace;
        }
        return store.All.OrderByDescending(item => item.UpdatedAt).FirstOrDefault();
    }

    private void ClearConversationView()
    {
        current = null;
        streamingMarkdown = null;
        markdownTimer?.Stop();
        MessagePanel.Children.Clear();
        LogBox.Text = "";
        ApplyConversationLayout();
        UpdateSendEnabled();
    }

    private async Task OpenNewConversationAsync(string path)
    {
        var conversation = new Conversation
        {
            Title = "新对话",
            Workspace = path,
        };
        store.Save(conversation);
        store.SetLastOpened(conversation.Id);
        RefreshSidebar();
        ShowConversation(conversation, selectInList: true);
        PromptBox.Focus(FocusState.Programmatic);
        await RefreshCheckpointAsync(path);
    }

    /// <summary>
    /// 从分组头或菜单项解析工作区路径。
    /// </summary>
    private static string? WorkspaceFromGroupHeader(object sender)
    {
        return sender is FrameworkElement element ? WorkspaceFromSource(element.DataContext) : null;
    }

    private static string? WorkspaceFromSource(object? source)
    {
        if (TryWorkspace(source, out string? workspace))
        {
            return workspace;
        }
        if (source is ICollectionViewGroup viewGroup && TryWorkspace(viewGroup.Group, out workspace))
        {
            return workspace;
        }
        return null;
    }

    private static bool TryWorkspace(object? source, out string? workspace)
    {
        if (source is string path && !string.IsNullOrWhiteSpace(path))
        {
            workspace = path;
            return true;
        }
        if (source is ConversationGroup group && !string.IsNullOrWhiteSpace(group.Workspace))
        {
            workspace = group.Workspace;
            return true;
        }
        if (source is IEnumerable<Conversation> conversations)
        {
            string? first = conversations.FirstOrDefault()?.Workspace;
            if (!string.IsNullOrWhiteSpace(first))
            {
                workspace = first;
                return true;
            }
        }
        workspace = null;
        return false;
    }

    private async Task<string?> PickWorkspacePathAsync()
    {
        var picker = new FolderPicker();
        picker.SuggestedStartLocation = PickerLocationId.ComputerFolder;
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));
        var folder = await picker.PickSingleFolderAsync();
        return folder?.Path;
    }

    private void FilterBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (!sidebarReady)
        {
            return;
        }
        RefreshSidebar();
    }

    private void ConversationList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (suppressSelection)
        {
            return;
        }
        if (ConversationList.SelectedItem is not Conversation selected)
        {
            return;
        }
        if (current != null && current.Id == selected.Id)
        {
            return;
        }
        if (running)
        {
            SetStatus("任务进行中，不能切换对话");
            suppressSelection = true;
            try
            {
                ConversationList.SelectedItem = FindInSidebar(current?.Id);
            }
            finally
            {
                suppressSelection = false;
            }
            return;
        }
        ShowConversation(selected, selectInList: false);
        _ = RefreshCheckpointAsync(selected.Workspace);
    }

    /// <summary>
    /// 把内存中的会话按工作区交给 CollectionViewSource；只在 Loaded 之后写 Source。
    /// </summary>
    private void RefreshSidebar()
    {
        if (!sidebarReady)
        {
            return;
        }
        suppressSelection = true;
        try
        {
            List<ConversationGroup> groups = ConversationStore.Group(store.All, FilterBox.Text);
            conversationViewSource.Source = groups;
            ConversationList.SelectedItem = FindInGroups(groups, current?.Id);
        }
        catch (Exception ex)
        {
            CrashLog.Write("RefreshSidebar", ex);
            SetStatus("侧栏刷新失败：" + ex.Message);
        }
        finally
        {
            suppressSelection = false;
        }
    }

    private Conversation? FindInSidebar(string? id)
    {
        if (conversationViewSource.Source is IEnumerable<ConversationGroup> groups)
        {
            return FindInGroups(groups, id);
        }
        return store.Find(id);
    }

    private static Conversation? FindInGroups(IEnumerable<ConversationGroup> groups, string? id)
    {
        if (string.IsNullOrWhiteSpace(id))
        {
            return null;
        }
        foreach (ConversationGroup group in groups)
        {
            foreach (Conversation item in group.Items)
            {
                if (item.Id == id)
                {
                    return item;
                }
            }
        }
        return null;
    }

    /// <summary>
    /// 切到指定会话：恢复消息、工具日志和工作区胶囊。
    /// </summary>
    private void ShowConversation(Conversation conversation, bool selectInList)
    {
        current = conversation;
        store.SetLastOpened(conversation.Id);
        WorkspaceNameText.Text = conversation.WorkspaceName;
        ToolTipService.SetToolTip(WorkspaceChip, conversation.Workspace);
        LogBox.Text = conversation.ToolLog ?? "";
        RenderMessages(conversation);
        ApplyConversationLayout();
        UpdateSendEnabled();
        if (selectInList)
        {
            suppressSelection = true;
            try
            {
                ConversationList.SelectedItem = FindInSidebar(conversation.Id);
            }
            finally
            {
                suppressSelection = false;
            }
        }
    }

    private void ApplyConversationLayout()
    {
        bool none = current == null;
        bool empty = current != null && current.Messages.Count == 0;
        EmptyState.Visibility = none || empty ? Visibility.Visible : Visibility.Collapsed;
        ChatState.Visibility = !none && !empty ? Visibility.Visible : Visibility.Collapsed;
        PromptBox.PlaceholderText = empty
            ? "输入任务，Enter 发送，Shift+Enter 换行"
            : "输入后续任务，Enter 发送";
        EmptyHint.Text = none
            ? "点击左侧「新建对话」，选择一个工作区目录"
            : "输入任务开始对话";
        if (none)
        {
            WorkspaceNameText.Text = "未选择对话";
            ToolTipService.SetToolTip(WorkspaceChip, null);
        }
    }

    private void RenderMessages(Conversation conversation)
    {
        MessagePanel.Children.Clear();
        markdownTimer?.Stop();
        streamingMarkdown = null;
        MarkdownBlockHost? lastAssistant = null;
        foreach (ChatMessage message in conversation.Messages)
        {
            FrameworkElement body = AddMessageBlock(message.Role, message.Content);
            if (body is MarkdownBlockHost host)
            {
                lastAssistant = host;
            }
        }
        streamingMarkdown = lastAssistant;
        ScrollToEnd(TranscriptScroll);
    }

    private FrameworkElement AddMessageBlock(string role, string content)
    {
        var panel = new StackPanel { Spacing = 4 };
        panel.Children.Add(new TextBlock
        {
            Text = role == "assistant" ? "助手" : "你",
            Opacity = 0.55,
            FontSize = 12,
        });
        FrameworkElement body;
        if (role == "assistant")
        {
            var host = new MarkdownBlockHost();
            host.SetMarkdown(content ?? "");
            body = host;
        }
        else
        {
            body = new TextBlock
            {
                Text = content ?? "",
                TextWrapping = TextWrapping.Wrap,
                IsTextSelectionEnabled = true,
            };
        }
        panel.Children.Add(body);
        MessagePanel.Children.Add(panel);
        return body;
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

    private void ToggleToolLog_Click(object sender, RoutedEventArgs e)
    {
        toolLogOpen = !toolLogOpen;
        ToolLogColumn.Width = toolLogOpen ? new GridLength(280) : new GridLength(0);
        ToolLogPane.Visibility = toolLogOpen ? Visibility.Visible : Visibility.Collapsed;
        ToolLogButton.Content = toolLogOpen ? "收起日志" : "工具日志";
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
    /// 发送任务：把本会话已有 user/assistant 作为 history，StartRun → 订阅 SSE。
    /// </summary>
    private async void Send_Click(object sender, RoutedEventArgs e)
    {
        if (running || client == null || current == null)
        {
            if (current == null)
            {
                SetStatus("请先新建对话并选择工作区");
            }
            return;
        }
        string workspace = current.Workspace.Trim();
        string prompt = ReadPrompt();
        if (workspace.Length == 0 || !Directory.Exists(workspace))
        {
            SetStatus("工作区目录不存在，请新建对话并重新选择");
            return;
        }
        if (prompt.Length == 0)
        {
            SetStatus("请输入任务");
            return;
        }

        List<HistoryTurn> history = current.Messages
            .Where(message => message.Role is "user" or "assistant")
            .Select(message => new HistoryTurn { Role = message.Role, Content = message.Content ?? "" })
            .ToList();
        bool firstMessage = history.Count == 0;
        if (firstMessage || current.Title == "新对话")
        {
            current.Title = Conversation.MakeTitle(prompt);
        }
        current.Messages.Add(new ChatMessage { Role = "user", Content = prompt });
        var assistant = new ChatMessage { Role = "assistant", Content = "" };
        current.Messages.Add(assistant);
        ClearPrompt();
        RenderMessages(current);
        ApplyConversationLayout();
        store.Save(current);
        RefreshSidebar();
        if (firstMessage)
        {
            _ = GenerateConversationTitleAsync(current, prompt);
        }

        running = true;
        UpdateRunControls();
        runCts = new CancellationTokenSource();
        currentRunId = null;
        try
        {
            currentRunId = await client.StartRunAsync(new RunRequest
            {
                Workspace = workspace,
                Prompt = prompt,
                Mode = IsAgentMode() ? "AGENT" : "ASK",
                DryRun = DryRunBox.IsChecked == true,
                AutoApprove = AutoApproveBox.IsChecked == true,
                History = history,
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
            AppendAssistant("\n[错误] " + ex.Message + "\n");
            SetStatus("失败");
        }
        finally
        {
            running = false;
            currentRunId = null;
            runCts?.Dispose();
            runCts = null;
            UpdateRunControls();
            FlushMarkdownRefresh();
            if (current != null)
            {
                store.Save(current);
                RefreshSidebar();
            }
        }
    }

    /// <summary>
    /// 新建对话的第一条消息发出后，额外请求模型生成侧栏标题；失败时保留截断占位。
    /// </summary>
    private async Task GenerateConversationTitleAsync(Conversation conversation, string prompt)
    {
        AgentApiClient? api = client;
        if (api == null)
        {
            return;
        }
        try
        {
            string title = await api.GenerateTitleAsync(prompt, lifetimeCts.Token);
            title = Conversation.MakeTitle(title);
            if (title.Length == 0 || title == "新对话")
            {
                return;
            }
            DispatcherQueue.TryEnqueue(() =>
            {
                if (store.Find(conversation.Id) == null)
                {
                    return;
                }
                conversation.Title = title;
                store.Save(conversation);
                RefreshSidebar();
            });
        }
        catch (OperationCanceledException)
        {
            // 窗口关闭或超时，保留本地占位标题
        }
        catch (Exception)
        {
            // 生成失败不影响主对话
        }
    }

    private string ReadPrompt()
    {
        return PromptBox.Text.Trim();
    }

    private void ClearPrompt()
    {
        PromptBox.Text = "";
    }

    private void UpdateRunControls()
    {
        NewChatButton.IsEnabled = !running;
        ConversationList.IsEnabled = !running;
        FilterBox.IsEnabled = !running;
        StopButton.IsEnabled = running;
        PromptBox.IsEnabled = !running;
        UpdateSendEnabled();
    }

    private void UpdateSendEnabled()
    {
        SendButton.IsEnabled = client != null && !running && current != null;
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
                    AppendAssistant(text.GetString() ?? "");
                }
                break;
            case "tool":
                string name = evt.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                string status = evt.TryGetProperty("status", out var s) ? s.GetString() ?? "" : "";
                int chars = evt.TryGetProperty("chars", out var c) && c.ValueKind == JsonValueKind.Number ? c.GetInt32() : 0;
                AppendLog($"{name} {status} ({chars} chars)\n");
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
                AppendLog((approved ? "已批准 " : "已拒绝 ") + toolName + "\n");
                break;
            case "done":
                string doneStatus = evt.TryGetProperty("status", out var ds) ? ds.GetString() ?? "" : "";
                string? doneText = evt.TryGetProperty("text", out var dt) && dt.ValueKind == JsonValueKind.String ? dt.GetString() : null;
                string? error = evt.TryGetProperty("error", out var de) && de.ValueKind == JsonValueKind.String ? de.GetString() : null;
                string existing = LastAssistantText();
                if (!string.IsNullOrWhiteSpace(doneText)
                    && (existing.Length == 0 || !existing.EndsWith(doneText, StringComparison.Ordinal)))
                {
                    if (existing.Length == 0)
                    {
                        SetLastAssistant(doneText);
                    }
                    else
                    {
                        AppendAssistant("\n" + doneText + "\n");
                    }
                }
                if (!string.IsNullOrWhiteSpace(error))
                {
                    AppendAssistant("\n[失败] " + error + "\n");
                }
                if (evt.TryGetProperty("checkpoint", out var cp) && cp.ValueKind == JsonValueKind.Object)
                {
                    string id = cp.TryGetProperty("checkpointId", out var cid) ? cid.GetString() ?? "" : "";
                    AppendLog("checkpoint 保留：" + id + "\n");
                }
                SetStatus("结束：" + doneStatus);
                break;
        }
    }

    private string LastAssistantText()
    {
        ChatMessage? last = current?.Messages.LastOrDefault(message => message.Role == "assistant");
        return last?.Content ?? "";
    }

    private void SetLastAssistant(string text)
    {
        ChatMessage? last = current?.Messages.LastOrDefault(message => message.Role == "assistant");
        if (last != null)
        {
            last.Content = text;
        }
        FlushMarkdownRefresh();
    }

    private void AppendAssistant(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return;
        }
        ChatMessage? last = current?.Messages.LastOrDefault(message => message.Role == "assistant");
        if (last != null)
        {
            last.Content += text;
        }
        QueueMarkdownRefresh();
    }

    private Microsoft.UI.Dispatching.DispatcherQueueTimer MarkdownTimer
    {
        get
        {
            if (markdownTimer == null)
            {
                markdownTimer = DispatcherQueue.CreateTimer();
                markdownTimer.IsRepeating = false;
                markdownTimer.Interval = TimeSpan.FromMilliseconds(80);
                markdownTimer.Tick += (_, _) => ApplyStreamingMarkdown();
            }
            return markdownTimer;
        }
    }

    private void QueueMarkdownRefresh()
    {
        Microsoft.UI.Dispatching.DispatcherQueueTimer timer = MarkdownTimer;
        timer.Stop();
        timer.Start();
    }

    private void FlushMarkdownRefresh()
    {
        markdownTimer?.Stop();
        ApplyStreamingMarkdown();
    }

    private void ApplyStreamingMarkdown()
    {
        if (streamingMarkdown != null)
        {
            streamingMarkdown.SetMarkdown(LastAssistantText());
        }
        ScrollToEnd(TranscriptScroll);
    }

    private void AppendLog(string text)
    {
        LogBox.Text += text;
        if (current != null)
        {
            current.ToolLog += text;
        }
        ScrollToEnd(LogScroll);
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
            AppendLog("取消失败：" + ex.Message + "\n");
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
            SetStatus("本地服务尚未就绪");
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
            AppendLog("读取设置失败：" + ex.Message + "\n");
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
    /// 确认后把当前会话工作区回滚到最近 checkpoint。
    /// </summary>
    private async void Rollback_Click(object sender, RoutedEventArgs e)
    {
        if (client == null)
        {
            return;
        }
        string workspace = current?.Workspace.Trim() ?? "";
        if (workspace.Length == 0)
        {
            SetStatus("请先新建对话并选择工作区");
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
            AppendLog("已回滚到最近 checkpoint\n");
            if (current != null)
            {
                store.Save(current);
            }
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
        if (string.IsNullOrWhiteSpace(workspace))
        {
            return;
        }
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
    /// 把滚动条拉到最底，用于流式 token 追加时始终看到最新内容。
    /// </summary>
    private static void ScrollToEnd(ScrollViewer? scroll)
    {
        if (scroll == null)
        {
            return;
        }
        scroll.UpdateLayout();
        scroll.ChangeView(null, scroll.ScrollableHeight, null);
    }
}
