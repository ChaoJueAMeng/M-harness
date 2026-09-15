namespace MHarness.Desktop;

/// <summary>
/// 把启动期异常写到用户目录，WinUI unpackaged 闪退时终端通常没有输出。
/// </summary>
internal static class CrashLog
{
    internal static string Path => System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ".m-harness",
        "desktop-crash.log");

    internal static void Write(string where, Exception ex)
    {
        try
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
            string text = DateTimeOffset.Now.ToString("o")
                + " [" + where + "]" + Environment.NewLine
                + ex + Environment.NewLine + Environment.NewLine;
            File.AppendAllText(Path, text);
        }
        catch
        {
            // ignore
        }
    }
}
