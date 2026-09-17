using System.Text.Json.Serialization;

namespace MHarness.Desktop;

/// <summary>
/// 一条对话消息。role 仅为 user / assistant，工具轨迹不进会话正文。
/// </summary>
public sealed class ChatMessage
{
    public string Role { get; set; } = "user";
    public string Content { get; set; } = "";
}

/// <summary>
/// 本地会话。可以尚未绑定工作区；运行时落到临时码本。磁盘存全文，发给模型前由服务端压缩。
/// </summary>
public sealed class Conversation
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "新对话";
    public string Workspace { get; set; } = "";
    public DateTimeOffset CreatedAt { get; set; } = DateTimeOffset.UtcNow;
    public DateTimeOffset UpdatedAt { get; set; } = DateTimeOffset.UtcNow;
    public List<ChatMessage> Messages { get; set; } = new();
    public string ToolLog { get; set; } = "";

    [JsonIgnore]
    public bool IsUnbound => string.IsNullOrWhiteSpace(Workspace);

    [JsonIgnore]
    public string WorkspaceName
    {
        get
        {
            if (IsUnbound)
            {
                return "无工作区";
            }
            return Path.GetFileName(Workspace.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
        }
    }

    [JsonIgnore]
    public string RelativeTime => FormatRelative(UpdatedAt);

    [JsonIgnore]
    public string SidebarSubtitle => WorkspaceName + " · " + RelativeTime;

    /// <summary>
    /// 用第一条用户消息生成侧栏标题，空白折叠后截到 40 字。
    /// </summary>
    public static string MakeTitle(string prompt)
    {
        string title = string.Join(' ', prompt.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        if (title.Length == 0)
        {
            return "新对话";
        }
        return title.Length <= 40 ? title : title[..40];
    }

    internal static string FormatRelative(DateTimeOffset updatedAt)
    {
        TimeSpan delta = DateTimeOffset.UtcNow - updatedAt.ToUniversalTime();
        if (delta.TotalMinutes < 1)
        {
            return "刚刚";
        }
        if (delta.TotalHours < 1)
        {
            return Math.Max(1, (int)delta.TotalMinutes) + " 分钟前";
        }
        if (delta.TotalHours < 24)
        {
            return (int)delta.TotalHours + " 小时前";
        }
        if (delta.TotalDays < 7)
        {
            return (int)delta.TotalDays + " 天前";
        }
        return updatedAt.ToLocalTime().ToString("yyyy-MM-dd");
    }
}

/// <summary>
/// 侧栏分组：Key 为工作区目录名，Workspace 为完整路径；子项放在 Items 上给 CollectionViewSource.ItemsPath 用。
/// </summary>
public sealed class ConversationGroup
{
    public ConversationGroup(string key, string workspace, IEnumerable<Conversation> items)
    {
        Key = key;
        Workspace = workspace;
        Items = items.ToList();
    }

    public string Key { get; }
    public string Workspace { get; }
    public List<Conversation> Items { get; }

    public DateTimeOffset LatestUpdate =>
        Items.Count == 0 ? DateTimeOffset.MinValue : Items.Max(item => item.UpdatedAt);
}
