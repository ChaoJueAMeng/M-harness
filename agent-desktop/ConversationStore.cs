using System.Text.Json;

namespace MHarness.Desktop;

/// <summary>
/// 把会话读写到用户目录 {@code ~/.m-harness/conversations}。坏文件跳过，写入先 tmp 再替换。
/// </summary>
internal sealed class ConversationStore
{
    private const string ActiveFileName = ".active-id";
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    private readonly string directory;
    private readonly List<Conversation> items = new();

    public ConversationStore(string directory)
    {
        this.directory = directory;
    }

    public IReadOnlyList<Conversation> All => items;

    public string? LastOpenedId { get; private set; }

    /// <summary>
    /// 默认根目录与 Java 全局配置一致：{@code M_HARNESS_CONFIG_DIR} 或 {@code ~/.m-harness/conversations}。
    /// </summary>
    public static string DefaultRoot()
    {
        return Path.Combine(HarnessPaths.ConfigDir(), "conversations");
    }

    public static ConversationStore Open()
    {
        var store = new ConversationStore(DefaultRoot());
        store.Load();
        return store;
    }

    /// <summary>
    /// 扫描 json；单个文件损坏不影响其余会话。
    /// </summary>
    public void Load()
    {
        items.Clear();
        Directory.CreateDirectory(directory);
        foreach (string file in Directory.EnumerateFiles(directory, "*.json"))
        {
            try
            {
                string json = File.ReadAllText(file);
                Conversation? conversation = JsonSerializer.Deserialize<Conversation>(json, JsonOptions);
                if (conversation == null || string.IsNullOrWhiteSpace(conversation.Id))
                {
                    continue;
                }
                conversation.Messages ??= new List<ChatMessage>();
                conversation.ToolLog ??= "";
                conversation.Title = string.IsNullOrWhiteSpace(conversation.Title) ? "新对话" : conversation.Title;
                items.Add(conversation);
            }
            catch
            {
                // skip corrupt file
            }
        }
        string activePath = Path.Combine(directory, ActiveFileName);
        if (File.Exists(activePath))
        {
            try
            {
                LastOpenedId = File.ReadAllText(activePath).Trim();
            }
            catch
            {
                LastOpenedId = null;
            }
        }
    }

    public Conversation? Find(string? id)
    {
        if (string.IsNullOrWhiteSpace(id))
        {
            return null;
        }
        return items.FirstOrDefault(item => item.Id == id);
    }

    /// <summary>
    /// 原子写入会话文件，并把它放进内存列表。
    /// </summary>
    public void Save(Conversation conversation)
    {
        conversation.UpdatedAt = DateTimeOffset.UtcNow;
        conversation.Messages ??= new List<ChatMessage>();
        conversation.ToolLog ??= "";
        Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, conversation.Id + ".json");
        string tmp = path + ".tmp";
        string json = JsonSerializer.Serialize(conversation, JsonOptions);
        File.WriteAllText(tmp, json);
        File.Move(tmp, path, overwrite: true);
        if (!items.Contains(conversation))
        {
            Conversation? existing = Find(conversation.Id);
            if (existing != null)
            {
                items.Remove(existing);
            }
            items.Add(conversation);
        }
    }

    public void SetLastOpened(string? id)
    {
        LastOpenedId = id;
        Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, ActiveFileName);
        if (string.IsNullOrWhiteSpace(id))
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
            return;
        }
        string tmp = path + ".tmp";
        File.WriteAllText(tmp, id);
        File.Move(tmp, path, overwrite: true);
    }

    /// <summary>
    /// 删除会话文件（含残留 tmp）并从内存列表移除；若删的是上次打开的会话则清空标记。
    /// </summary>
    public bool Delete(string? id)
    {
        if (string.IsNullOrWhiteSpace(id))
        {
            return false;
        }
        bool removed = false;
        Conversation? existing = Find(id);
        if (existing != null)
        {
            items.Remove(existing);
            removed = true;
        }
        Directory.CreateDirectory(directory);
        string path = Path.Combine(directory, id + ".json");
        string tmp = path + ".tmp";
        if (File.Exists(tmp))
        {
            File.Delete(tmp);
            removed = true;
        }
        if (File.Exists(path))
        {
            File.Delete(path);
            removed = true;
        }
        if (string.Equals(LastOpenedId, id, StringComparison.Ordinal))
        {
            SetLastOpened(null);
        }
        return removed;
    }

    /// <summary>
    /// 按完整工作区路径分组；未绑定的会话归入「无工作区」。
    /// 组内按更新时间新到旧；组按组内最新会话排序。
    /// </summary>
    public static List<ConversationGroup> Group(IEnumerable<Conversation> all, string? filter)
    {
        IEnumerable<Conversation> query = all;
        if (!string.IsNullOrWhiteSpace(filter))
        {
            string needle = filter.Trim();
            query = query.Where(item =>
                item.Title.Contains(needle, StringComparison.OrdinalIgnoreCase)
                || item.WorkspaceName.Contains(needle, StringComparison.OrdinalIgnoreCase)
                || item.Workspace.Contains(needle, StringComparison.OrdinalIgnoreCase));
        }
        return query
            .GroupBy(item => item.IsUnbound ? "" : item.Workspace, StringComparer.OrdinalIgnoreCase)
            .Select(group =>
            {
                bool unbound = string.IsNullOrWhiteSpace(group.Key);
                string name = unbound ? "无工作区" : group.First().WorkspaceName;
                if (string.IsNullOrWhiteSpace(name))
                {
                    name = "未知工作区";
                }
                return new ConversationGroup(
                    name,
                    unbound ? "" : group.First().Workspace,
                    group.OrderByDescending(item => item.UpdatedAt));
            })
            .OrderByDescending(group => group.LatestUpdate)
            .ToList();
    }
}
