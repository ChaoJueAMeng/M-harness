namespace MHarness.Desktop;

/// <summary>
/// 与 Java {@code HarnessConfig.globalConfigDir()} 对齐的本机路径：配置、会话、临时码本。
/// </summary>
internal static class HarnessPaths
{
    public static string ConfigDir()
    {
        string? env = Environment.GetEnvironmentVariable("M_HARNESS_CONFIG_DIR");
        if (!string.IsNullOrWhiteSpace(env))
        {
            return env;
        }
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".m-harness");
    }

    public static string ScratchRoot()
    {
        return Path.Combine(ConfigDir(), "scratch");
    }

    public static string ScratchWorkspace(string conversationId)
    {
        return Path.Combine(ScratchRoot(), conversationId);
    }

    /// <summary>
    /// 删除未绑定对话对应的临时码本；失败时忽略，避免挡住删会话。
    /// </summary>
    public static void TryDeleteScratch(string conversationId)
    {
        if (string.IsNullOrWhiteSpace(conversationId))
        {
            return;
        }
        string path = ScratchWorkspace(conversationId);
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, recursive: true);
            }
        }
        catch
        {
            // ignore
        }
    }
}
