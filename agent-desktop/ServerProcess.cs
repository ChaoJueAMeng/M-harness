using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace MHarness.Desktop;

/// <summary>
/// 拉起本机 <c>m-harness-server.jar</c>，读第一行 JSON 拿到 port/token，窗口关闭时杀掉整棵进程树。
/// </summary>
internal sealed class ServerProcess : IDisposable
{
    private static readonly object JavaCacheLock = new();
    private static string? cachedJava;
    private static DateTimeOffset cachedJavaAt;
    private static readonly TimeSpan JavaCacheTtl = TimeSpan.FromMinutes(10);

    private readonly Process process;
    private bool disposed;

    public int Port { get; }
    public string Token { get; }
    public bool HasExited
    {
        get
        {
            try
            {
                return process.HasExited;
            }
            catch
            {
                return true;
            }
        }
    }

    /// <summary>
    /// Java 子进程意外退出时触发（线程池线程）。主动 <see cref="Dispose"/> 结束进程不会触发。
    /// </summary>
    public event Action<int?>? Exited;

    private ServerProcess(Process process, int port, string token)
    {
        this.process = process;
        Port = port;
        Token = token;
        process.EnableRaisingEvents = true;
        process.Exited += OnProcessExited;
    }

    private void OnProcessExited(object? sender, EventArgs e)
    {
        if (disposed)
        {
            return;
        }
        int? code = null;
        try
        {
            code = process.ExitCode;
        }
        catch
        {
            // ignore
        }
        Exited?.Invoke(code);
    }

    /// <summary>
    /// 查找 JDK 21 与 server jar，以 <c>--port 0</c> 启动并等待就绪 JSON。
    /// 超时或非 JSON 输出会杀掉子进程并抛出带 java/jar/stderr 的说明。
    /// </summary>
    public static ServerProcess Start()
    {
        string jar = FindJar();
        string java = FindJava();
        var stderr = new StringBuilder();
        var start = new ProcessStartInfo
        {
            FileName = java,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = AppContext.BaseDirectory,
        };
        start.ArgumentList.Add("-jar");
        start.ArgumentList.Add(jar);
        start.ArgumentList.Add("--bind");
        start.ArgumentList.Add("127.0.0.1");
        start.ArgumentList.Add("--port");
        start.ArgumentList.Add("0");

        // 把 JAVA_HOME 指到选用的 JDK，避免子进程又捡到 PATH 上的 Java 8。
        string? home = Directory.GetParent(Path.GetDirectoryName(java)!)?.FullName;
        if (!string.IsNullOrWhiteSpace(home))
        {
            start.Environment["JAVA_HOME"] = home;
        }

        var process = Process.Start(start) ?? throw new InvalidOperationException("无法启动 Java 服务进程。");
        process.ErrorDataReceived += (_, e) =>
        {
            if (!string.IsNullOrEmpty(e.Data))
            {
                lock (stderr)
                {
                    stderr.AppendLine(e.Data);
                }
            }
        };
        process.BeginErrorReadLine();

        string? line = ReadReadyLine(process, TimeSpan.FromSeconds(20));
        if (string.IsNullOrWhiteSpace(line) || !line.TrimStart().StartsWith('{'))
        {
            string log = Snapshot(stderr);
            int? code = process.HasExited ? process.ExitCode : null;
            try
            {
                if (!process.HasExited)
                {
                    process.Kill(entireProcessTree: true);
                }
            }
            catch
            {
                // ignore
            }
            throw new InvalidOperationException(BuildStartFailure(java, jar, line, log, code));
        }

        using var doc = JsonDocument.Parse(line);
        int port = doc.RootElement.GetProperty("port").GetInt32();
        string token = doc.RootElement.GetProperty("token").GetString()
            ?? throw new InvalidOperationException("启动信息缺少 token");
        return new ServerProcess(process, port, token);
    }

    /// <summary>
    /// 结束 Java 子进程及其子孙（HTTP 服务、Agent 线程）。
    /// </summary>
    public void Dispose()
    {
        disposed = true;
        process.Exited -= OnProcessExited;
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }
        }
        catch
        {
            // ignore
        }
        process.Dispose();
    }

    /// <summary>
    /// 查找 jar：环境变量 → exe 旁 → 向上最多 8 层的 agent-server/target → 当前目录。
    /// </summary>
    internal static string FindJar()
    {
        string? env = Environment.GetEnvironmentVariable("M_HARNESS_SERVER_JAR");
        if (!string.IsNullOrWhiteSpace(env) && File.Exists(env))
        {
            return Path.GetFullPath(env);
        }

        string nextToExe = Path.Combine(AppContext.BaseDirectory, "m-harness-server.jar");
        if (File.Exists(nextToExe))
        {
            return nextToExe;
        }

        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        for (int i = 0; i < 8 && dir != null; i++)
        {
            string candidate = Path.Combine(dir.FullName, "agent-server", "target", "m-harness-server.jar");
            if (File.Exists(candidate))
            {
                return candidate;
            }
            dir = dir.Parent;
        }

        string cwd = Path.GetFullPath(Path.Combine(Environment.CurrentDirectory, "agent-server", "target", "m-harness-server.jar"));
        if (File.Exists(cwd))
        {
            return cwd;
        }

        throw new FileNotFoundException(
            "找不到 m-harness-server.jar。安装包应把它放在 exe 旁边；从源码运行请先执行 .\\mvnw.cmd -pl agent-server -am package，或设置 M_HARNESS_SERVER_JAR。");
    }

    /// <summary>
    /// 找 JDK 21+：安装包自带的 <c>jre</c> → JAVA_HOME（进程/用户/机器）→ %USERPROFILE%\.jdks → PATH。
    /// PATH 上的 Java 8 会被 IsJava21OrNewer 滤掉。
    /// </summary>
    internal static string FindJava()
    {
        lock (JavaCacheLock)
        {
            if (cachedJava != null
                && DateTimeOffset.UtcNow - cachedJavaAt < JavaCacheTtl
                && File.Exists(cachedJava))
            {
                return cachedJava;
            }
        }
        var candidates = new List<string>();
        string bundled = Path.Combine(AppContext.BaseDirectory, "jre", "bin", "java.exe");
        if (File.Exists(bundled))
        {
            candidates.Add(bundled);
        }

        AddJavaHome(candidates, Environment.GetEnvironmentVariable("JAVA_HOME"));
        AddJavaHome(candidates, Environment.GetEnvironmentVariable("JAVA_HOME", EnvironmentVariableTarget.User));
        AddJavaHome(candidates, Environment.GetEnvironmentVariable("JAVA_HOME", EnvironmentVariableTarget.Machine));

        string jdks = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".jdks");
        if (Directory.Exists(jdks))
        {
            foreach (string dir in Directory.GetDirectories(jdks).OrderByDescending(d => d, StringComparer.OrdinalIgnoreCase))
            {
                string exe = Path.Combine(dir, "bin", "java.exe");
                if (File.Exists(exe))
                {
                    candidates.Add(exe);
                }
            }
        }

        string? pathJava = FindOnPath("java.exe") ?? FindOnPath("java");
        if (pathJava != null)
        {
            candidates.Add(pathJava);
        }

        string? chosen = candidates.Distinct(StringComparer.OrdinalIgnoreCase).FirstOrDefault(IsJava21OrNewer);
        if (chosen != null)
        {
            lock (JavaCacheLock)
            {
                cachedJava = chosen;
                cachedJavaAt = DateTimeOffset.UtcNow;
            }
            return chosen;
        }

        throw new InvalidOperationException(
            "找不到 JDK 21。安装包应自带 jre 目录；从源码运行时请安装 JDK 21，或设置 JAVA_HOME。" +
            Environment.NewLine +
            @"本机 IntelliJ JDK 一般在 %USERPROFILE%\.jdks\ms-21.0.12");
    }

    /// <summary>
    /// 若 JAVA_HOME/bin/java.exe 存在则加入候选列表。
    /// </summary>
    private static void AddJavaHome(List<string> candidates, string? home)
    {
        if (string.IsNullOrWhiteSpace(home))
        {
            return;
        }
        string exe = Path.Combine(home, "bin", "java.exe");
        if (File.Exists(exe))
        {
            candidates.Add(exe);
        }
    }

    /// <summary>
    /// 用 where.exe 在 PATH 上找 java，返回第一个真实存在的路径。
    /// </summary>
    private static string? FindOnPath(string fileName)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "where.exe",
                ArgumentList = { fileName },
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var process = Process.Start(psi);
            if (process == null)
            {
                return null;
            }
            string output = process.StandardOutput.ReadToEnd();
            process.WaitForExit(3000);
            foreach (string line in output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries))
            {
                if (File.Exists(line.Trim()))
                {
                    return line.Trim();
                }
            }
        }
        catch
        {
            // ignore
        }
        return null;
    }

    /// <summary>
    /// 跑 <c>java -version</c>，主版本号 ≥ 21 才算可用。
    /// </summary>
    private static bool IsJava21OrNewer(string javaExe)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = javaExe,
                ArgumentList = { "-version" },
                RedirectStandardError = true,
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            using var process = Process.Start(psi);
            if (process == null)
            {
                return false;
            }
            string text = process.StandardError.ReadToEnd() + process.StandardOutput.ReadToEnd();
            process.WaitForExit(5000);
            Match match = Regex.Match(text, @"version ""(\d+)");
            if (!match.Success)
            {
                return false;
            }
            int major = int.Parse(match.Groups[1].Value);
            return major >= 21;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// 读 stdout 直到出现以 <c>{</c> 开头的就绪行；超时返回 null。
    /// </summary>
    private static string? ReadReadyLine(Process process, TimeSpan timeout)
    {
        var read = Task.Run(() =>
        {
            while (true)
            {
                string? line = process.StandardOutput.ReadLine();
                if (line is null)
                {
                    return null;
                }
                if (line.TrimStart().StartsWith('{'))
                {
                    return line;
                }
            }
        });
        return read.Wait(timeout) ? read.Result : null;
    }

    /// <summary>
    /// 线程安全地取出已捕获的 stderr，用于启动失败提示。
    /// </summary>
    private static string Snapshot(StringBuilder stderr)
    {
        lock (stderr)
        {
            return stderr.ToString().Trim();
        }
    }

    /// <summary>
    /// 拼出「服务没有返回 port/token」的完整错误，带上 java 路径、jar、退出码和 stderr。
    /// </summary>
    private static string BuildStartFailure(string java, string jar, string? line, string log, int? exitCode)
    {
        var text = new StringBuilder();
        text.AppendLine("Java 服务没有返回 port/token。");
        text.AppendLine("java: " + java);
        text.AppendLine("jar: " + jar);
        if (exitCode is int code)
        {
            text.AppendLine("退出码: " + code);
        }
        if (!string.IsNullOrWhiteSpace(line))
        {
            text.AppendLine("stdout: " + line);
        }
        if (!string.IsNullOrWhiteSpace(log))
        {
            text.AppendLine(log);
        }
        else
        {
            text.Append("请确认已安装 JDK 21，并已打包 agent-server。");
        }
        return text.ToString();
    }
}
