using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace MHarness.Desktop;

/// <summary>
/// 本机 Java HTTP 服务的客户端。所有请求带启动时下发的 Bearer token，只打 127.0.0.1。
/// </summary>
internal sealed class AgentApiClient : IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    private readonly HttpClient http;

    /// <summary>
    /// SSE 可能长时间无数据，因此超时设为 Infinite，取消靠 CancellationToken。
    /// </summary>
    public AgentApiClient(int port, string token)
    {
        http = new HttpClient
        {
            BaseAddress = new Uri($"http://127.0.0.1:{port}/"),
            Timeout = Timeout.InfiniteTimeSpan,
        };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
    }

    /// <summary>
    /// POST /v1/run，返回服务端分配的 runId。
    /// </summary>
    public async Task<string> StartRunAsync(RunRequest request, CancellationToken cancellationToken)
    {
        string body = await SendJsonAsync(HttpMethod.Post, "v1/run", request, cancellationToken);
        using var doc = JsonDocument.Parse(body);
        if (!doc.RootElement.TryGetProperty("runId", out var id))
        {
            throw new InvalidOperationException("服务未返回 runId");
        }
        return id.GetString() ?? throw new InvalidOperationException("runId 为空");
    }

    /// <summary>
    /// GET /v1/run/{id}/events，逐条 yield SSE 的 JSON 对象（token / tool / approval / done）。
    /// </summary>
    public async IAsyncEnumerable<JsonElement> StreamEventsAsync(
        string runId,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, $"v1/run/{runId}/events");
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        await EnsureSuccess(response);
        using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        while (!cancellationToken.IsCancellationRequested)
        {
            string? line = await reader.ReadLineAsync(cancellationToken);
            if (line is null)
            {
                yield break;
            }
            if (!line.StartsWith("data:", StringComparison.Ordinal))
            {
                continue;
            }
            string payload = line[5..].Trim();
            if (payload.Length == 0)
            {
                continue;
            }
            using var doc = JsonDocument.Parse(payload);
            yield return doc.RootElement.Clone();
        }
    }

    /// <summary>
    /// 把用户在对话框里点的批准/拒绝回传给正在阻塞的 Agent 线程。
    /// </summary>
    public async Task ApproveAsync(string runId, string requestId, bool approved, CancellationToken cancellationToken) =>
        await SendJsonAsync(HttpMethod.Post, $"v1/run/{runId}/approval", new { requestId, approved }, cancellationToken);

    /// <summary>
    /// 取消正在运行的任务，并拒绝所有挂起的批准。
    /// </summary>
    public async Task CancelAsync(string runId, CancellationToken cancellationToken) =>
        await SendJsonAsync(HttpMethod.Post, $"v1/run/{runId}/cancel", new { }, cancellationToken);

    /// <summary>
    /// 查询工作区当前 checkpoint 元数据（可能没有）。
    /// </summary>
    public async Task<JsonElement> GetCheckpointAsync(string workspace, CancellationToken cancellationToken)
    {
        using var response = await http.GetAsync("v1/checkpoint?workspace=" + Uri.EscapeDataString(workspace), cancellationToken);
        await EnsureSuccess(response);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        return doc.RootElement.Clone();
    }

    /// <summary>
    /// 把工作区强制恢复到最近一次 checkpoint。
    /// </summary>
    public async Task RollbackAsync(string workspace, CancellationToken cancellationToken) =>
        await SendJsonAsync(HttpMethod.Post, "v1/rollback", new { workspace }, cancellationToken);

    /// <summary>
    /// 读取全局模型设置；API Key 只返回是否已设置和后四位提示。
    /// </summary>
    public async Task<JsonElement> GetSettingsAsync(CancellationToken cancellationToken)
    {
        using var response = await http.GetAsync("v1/settings", cancellationToken);
        await EnsureSuccess(response);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        return doc.RootElement.Clone();
    }

    /// <summary>
    /// 写入全局配置（~/.m-harness/.env）。apiKey 为 null 时服务端不会覆盖已有密钥。
    /// </summary>
    public async Task SaveSettingsAsync(
        string? baseUrl,
        string? apiKey,
        string? model,
        CancellationToken cancellationToken) =>
        await SendJsonAsync(HttpMethod.Put, "v1/settings", new { baseUrl, apiKey, model }, cancellationToken);

    /// <summary>
    /// GET /health，服务已就绪时返回 true。
    /// </summary>
    public async Task<bool> HealthAsync(CancellationToken cancellationToken)
    {
        using var response = await http.GetAsync("health", cancellationToken);
        return response.IsSuccessStatusCode;
    }

    public void Dispose() => http.Dispose();

    /// <summary>
    /// 发送 JSON 请求体并返回响应字符串；非 2xx 走 <see cref="EnsureSuccess"/>。
    /// </summary>
    private async Task<string> SendJsonAsync(HttpMethod method, string path, object body, CancellationToken cancellationToken)
    {
        string json = JsonSerializer.Serialize(body, JsonOptions);
        using var request = new HttpRequestMessage(method, path)
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json"),
        };
        using HttpResponseMessage response = await http.SendAsync(request, cancellationToken);
        await EnsureSuccess(response);
        return await response.Content.ReadAsStringAsync(cancellationToken);
    }

    /// <summary>
    /// 非 2xx 时尽量抽出 JSON 的 error 字段再抛 HttpRequestException。
    /// </summary>
    private static async Task EnsureSuccess(HttpResponseMessage response)
    {
        if (response.IsSuccessStatusCode)
        {
            return;
        }
        string body = await response.Content.ReadAsStringAsync();
        string message = body;
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.TryGetProperty("error", out var error))
            {
                message = error.GetString() ?? body;
            }
        }
        catch (JsonException)
        {
            // keep raw body
        }
        throw new HttpRequestException($"{(int)response.StatusCode} {response.ReasonPhrase}: {message}");
    }
}

/// <summary>
/// POST /v1/run 的请求体。Mode 为 ASK 或 AGENT。
/// </summary>
internal sealed class RunRequest
{
    public string Workspace { get; init; } = "";
    public string Prompt { get; init; } = "";
    public string Mode { get; init; } = "ASK";
    public bool DryRun { get; init; }
    public bool AutoApprove { get; init; }
}
