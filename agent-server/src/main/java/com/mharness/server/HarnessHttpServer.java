package com.mharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mharness.agent.AgentLoop;
import com.mharness.agent.AgentOutcome;
import com.mharness.agent.AgentRuntime;
import com.mharness.checkpoint.Checkpoint;
import com.mharness.checkpoint.CheckpointException;
import com.mharness.config.HarnessConfig;
import com.mharness.llm.ChatClient;
import com.mharness.llm.ChatTurn;
import com.mharness.llm.OpenAiCompatibleChatClient;
import com.mharness.llm.TitleGenerator;
import com.mharness.permission.ApprovalService;
import com.mharness.permission.AutoApprovalService;
import com.mharness.permission.PermissionMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 本机 HTTP API：启动/取消 Agent、SSE 推事件、人工批准、checkpoint / 设置 / 生成标题。
 * 同一时刻只允许一个 ActiveRun；标题请求不占用该锁。请求必须带 Bearer token。
 */
public final class HarnessHttpServer implements AutoCloseable {
    private final HttpServer http;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();
    /** 处理 HTTP 请求（含 SSE 长连接）。 */
    private final ExecutorService httpExecutor = Executors.newCachedThreadPool(r -> daemon("m-harness-http", r));
    /** Agent 循环单线程，避免两个任务同时改同一工作区。 */
    private final ExecutorService agentExecutor = Executors.newSingleThreadExecutor(r -> daemon("m-harness-agent", r));
    private final Object lock = new Object();
    private ActiveRun active;
    /** 标题接口用的 ChatClient；测试可替换，避免打真实模型。 */
    private volatile Function<HarnessConfig, ChatClient> titleClients = config ->
            new OpenAiCompatibleChatClient(config.baseUrl(), config.apiKey(), config.model(), ignored -> {});

    private HarnessHttpServer(HttpServer http, String token) {
        this.http = http;
        this.token = token;
    }

    /**
     * 在 bind:port 上启动服务。port=0 由系统分配空闲端口。
     */
    public static HarnessHttpServer start(String bind, int port, String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 不能为空");
        }
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        HarnessHttpServer server = new HarnessHttpServer(http, token);
        http.createContext("/", server::handle);
        http.setExecutor(server.httpExecutor);
        http.start();
        return server;
    }

    /** 实际监听端口（port=0 时在 start 之后才知道）。 */
    public int port() {
        return http.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    /** 取消进行中的任务、拒绝挂起的批准，再停 HTTP 与线程池。 */
    @Override
    public void close() {
        synchronized (lock) {
            if (active != null) {
                active.loop.cancel();
                active.approvals.denyAll();
            }
        }
        http.stop(0);
        agentExecutor.shutdownNow();
        httpExecutor.shutdownNow();
        try {
            agentExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 统一入口：鉴权后按 method+path 分发。 */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                sendJson(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/health".equals(path)) {
                sendJson(exchange, 200, Map.of("ok", true));
                return;
            }
            if ("POST".equals(method) && "/v1/run".equals(path)) {
                startRun(exchange);
                return;
            }
            if ("GET".equals(method) && path.startsWith("/v1/run/") && path.endsWith("/events")) {
                streamEvents(exchange, slice(path, "/v1/run/", "/events"));
                return;
            }
            if ("POST".equals(method) && path.startsWith("/v1/run/") && path.endsWith("/approval")) {
                approve(exchange, slice(path, "/v1/run/", "/approval"));
                return;
            }
            if ("POST".equals(method) && path.startsWith("/v1/run/") && path.endsWith("/cancel")) {
                cancel(exchange, slice(path, "/v1/run/", "/cancel"));
                return;
            }
            if ("GET".equals(method) && "/v1/checkpoint".equals(path)) {
                checkpoint(exchange);
                return;
            }
            if ("POST".equals(method) && "/v1/rollback".equals(path)) {
                rollback(exchange);
                return;
            }
            if ("GET".equals(method) && "/v1/settings".equals(path)) {
                getSettings(exchange);
                return;
            }
            if ("PUT".equals(method) && "/v1/settings".equals(path)) {
                putSettings(exchange);
                return;
            }
            if ("POST".equals(method) && "/v1/title".equals(path)) {
                generateTitle(exchange);
                return;
            }
            sendJson(exchange, 404, Map.of("error", "not found"));
        } catch (Exception e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /**
     * POST /v1/run：校验 workspace/prompt/mode，组装 AgentLoop 并异步执行。
     * 已有未完成任务时返回 409。成功返回 {@code runId}，客户端再用 SSE 拉事件。
     */
    private void startRun(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String workspaceValue = text(body, "workspace");
        String prompt = text(body, "prompt");
        if (prompt == null || prompt.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "缺少 prompt"));
            return;
        }
        Path workspace;
        try {
            workspace = requireWorkspace(workspaceValue);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
            return;
        }
        PermissionMode mode;
        try {
            mode = parseMode(text(body, "mode"));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
            return;
        }
        boolean dryRun = body.path("dryRun").asBoolean(false);
        boolean autoApprove = body.path("autoApprove").asBoolean(false);
        HarnessConfig config = HarnessConfig.load(workspace);
        try {
            config.requireApiKey();
        } catch (IllegalStateException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
            return;
        }

        EventLog events = new EventLog();
        HttpApprovalService httpApprovals = new HttpApprovalService(events, mapper);
        ApprovalService approvals = autoApprove ? new AutoApprovalService(true) : httpApprovals;
        AgentRuntime runtime = new AgentRuntime(workspace);
        AgentLoop loop = runtime.createLoop(
                config.baseUrl(),
                config.apiKey(),
                config.model(),
                mode,
                dryRun,
                autoApprove,
                approvals,
                token -> emit(events, tokenEvent(token)),
                (toolName, status, chars) -> emit(events, toolEvent(toolName, status, chars))
        );
        String runId = UUID.randomUUID().toString();
        ActiveRun run = new ActiveRun(runId, loop, httpApprovals, events);
        synchronized (lock) {
            if (active != null && !active.finished) {
                sendJson(exchange, 409, Map.of("error", "已有任务在运行"));
                return;
            }
            active = run;
        }
        List<ChatTurn> history = parseHistory(body);
        agentExecutor.execute(() -> executeRun(run, prompt, history));
        sendJson(exchange, 200, Map.of("runId", runId));
    }

    /** 在 agent 线程里跑循环，把结局写成 SSE {@code done} 事件。 */
    private void executeRun(ActiveRun run, String prompt, List<ChatTurn> history) {
        try {
            AgentOutcome outcome = run.loop.run(prompt, history);
            emit(run.events, doneEvent(outcome));
        } catch (Exception e) {
            emit(run.events, failedEvent(e.getMessage()));
        } finally {
            run.finished = true;
            run.events.finish();
        }
    }

    /** GET /v1/run/{id}/events：SSE 长连接，重放已有帧并等待后续事件。 */
    private void streamEvents(HttpExchange exchange, String runId) throws IOException {
        ActiveRun run = requireRun(runId);
        if (run == null) {
            sendJson(exchange, 404, Map.of("error", "未知 runId"));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            run.events.stream(os);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // client disconnected
        }
    }

    /** POST /v1/run/{id}/approval：完成一次挂起的人工确认。 */
    private void approve(HttpExchange exchange, String runId) throws IOException {
        ActiveRun run = requireRun(runId);
        if (run == null || run.finished) {
            sendJson(exchange, 404, Map.of("error", "未知 runId"));
            return;
        }
        JsonNode body = readJson(exchange);
        String requestId = text(body, "requestId");
        if (requestId == null || requestId.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "缺少 requestId"));
            return;
        }
        boolean approved = body.path("approved").asBoolean(false);
        if (!run.approvals.complete(requestId, approved)) {
            sendJson(exchange, 404, Map.of("error", "未知 requestId"));
            return;
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    /** POST /v1/run/{id}/cancel：取消循环并拒绝所有待批准请求。 */
    private void cancel(HttpExchange exchange, String runId) throws IOException {
        ActiveRun run = requireRun(runId);
        if (run == null) {
            sendJson(exchange, 404, Map.of("error", "未知 runId"));
            return;
        }
        run.loop.cancel();
        run.approvals.denyAll();
        sendJson(exchange, 200, Map.of("ok", true));
    }

    /** GET /v1/checkpoint?workspace=：返回当前快照元数据。 */
    private void checkpoint(HttpExchange exchange) throws IOException {
        Path workspace;
        try {
            workspace = requireWorkspace(query(exchange.getRequestURI(), "workspace"));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
            return;
        }
        try {
            Checkpoint current = new AgentRuntime(workspace).checkpoints().current();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("checkpoint", current == null ? null : checkpointNode(current));
            response.put("hasCheckpoint", current != null);
            sendJson(exchange, 200, response);
        } catch (CheckpointException e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    /** POST /v1/rollback：把指定工作区恢复到最近 checkpoint。 */
    private void rollback(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        Path workspace;
        try {
            workspace = requireWorkspace(text(body, "workspace"));
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
            return;
        }
        try {
            AgentRuntime runtime = new AgentRuntime(workspace);
            Checkpoint current = runtime.checkpoints().current();
            runtime.checkpoints().rollback(current);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("checkpointId", current == null ? null : current.checkpointId());
            sendJson(exchange, 200, response);
        } catch (CheckpointException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
        }
    }

    /** GET /v1/settings：返回全局 baseUrl/model 和 API Key 是否已设置（不回显完整 Key）。 */
    private void getSettings(HttpExchange exchange) throws IOException {
        HarnessConfig config = HarnessConfig.load(null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("baseUrl", config.baseUrl());
        response.put("model", config.model());
        response.put("hasApiKey", config.hasApiKey());
        response.put("apiKeyHint", config.apiKeyHint());
        sendJson(exchange, 200, response);
    }

    /** PUT /v1/settings：把非空字段写入全局 ~/.m-harness/.env，所有工作区共用。 */
    private void putSettings(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        HarnessConfig.save(text(body, "baseUrl"), text(body, "apiKey"), text(body, "model"));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    /**
     * POST /v1/title：用第一条用户消息生成短标题。不占用 ActiveRun，可与主任务并行。
     */
    private void generateTitle(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String prompt = text(body, "prompt");
        if (prompt == null || prompt.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "缺少 prompt"));
            return;
        }
        HarnessConfig config = HarnessConfig.load(null);
        try {
            config.requireApiKey();
        } catch (IllegalStateException e) {
            sendJson(exchange, 400, Map.of("error", e.getMessage()));
            return;
        }
        try {
            ChatClient chat = titleClients.apply(config);
            String title = new TitleGenerator(chat).generate(prompt);
            sendJson(exchange, 200, Map.of("title", title == null ? "" : title));
        } catch (RuntimeException e) {
            sendJson(exchange, 502, Map.of("error", e.getMessage() == null ? "生成标题失败" : e.getMessage()));
        }
    }

    /** 测试替换标题所用 ChatClient，避免请求真实模型。 */
    void setTitleChatClientFactory(Function<HarnessConfig, ChatClient> factory) {
        this.titleClients = Objects.requireNonNull(factory);
    }

    /** 只认当前这一个 active runId。 */
    private ActiveRun requireRun(String runId) {
        synchronized (lock) {
            if (active != null && active.id.equals(runId)) {
                return active;
            }
            return null;
        }
    }

    /** Bearer token 常量时间比较，避免时序侧信道。 */
    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        byte[] actual = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(raw);
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 把 JSON 对象写成一条 SSE data 帧；序列化失败则丢弃，不影响主循环。 */
    private void emit(EventLog events, ObjectNode node) {
        try {
            events.emitJson(mapper.writeValueAsString(node));
        } catch (Exception ignored) {
            // drop
        }
    }

    private ObjectNode tokenEvent(String tokenText) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "token");
        node.put("text", tokenText == null ? "" : tokenText);
        return node;
    }

    private ObjectNode toolEvent(String toolName, String status, int chars) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "tool");
        node.put("name", toolName == null ? "" : toolName);
        node.put("status", status == null ? "" : status);
        node.put("chars", chars);
        return node;
    }

    private ObjectNode doneEvent(AgentOutcome outcome) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "done");
        node.put("status", outcome.status().name());
        if (outcome.text() == null) {
            node.putNull("text");
        } else {
            node.put("text", outcome.text());
        }
        if (outcome.error() == null) {
            node.putNull("error");
        } else {
            node.put("error", outcome.error());
        }
        node.set("checkpoint", checkpointNode(outcome.checkpoint()));
        return node;
    }

    private ObjectNode failedEvent(String error) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "done");
        node.put("status", AgentOutcome.Status.FAILED.name());
        node.putNull("text");
        node.put("error", error == null ? "unknown" : error);
        node.putNull("checkpoint");
        return node;
    }

    private JsonNode checkpointNode(Checkpoint checkpoint) {
        if (checkpoint == null) {
            return mapper.nullNode();
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("checkpointId", checkpoint.checkpointId());
        node.put("baseCommit", checkpoint.baseCommit());
        node.put("snapshotCommit", checkpoint.snapshotCommit());
        node.put("createdAt", checkpoint.createdAt() == null ? null : checkpoint.createdAt().toString());
        return node;
    }

    /** 工作区必须是已存在的目录。 */
    private static Path requireWorkspace(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            throw new IllegalArgumentException("缺少 workspace");
        }
        Path path = Path.of(workspace).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("工作区不是目录: " + path);
        }
        return path;
    }

    /** 缺省或 AGENT → AGENT；ASK 大小写不敏感；其它值报错。 */
    private static PermissionMode parseMode(String mode) {
        if (mode == null || mode.isBlank() || "AGENT".equalsIgnoreCase(mode)) {
            return PermissionMode.AGENT;
        }
        if ("ASK".equalsIgnoreCase(mode)) {
            return PermissionMode.ASK;
        }
        throw new IllegalArgumentException("未知 mode: " + mode);
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 读取可选 {@code history} 数组。只接受 user/assistant；tool、system、未知 role 或空 content 直接丢掉，不报 400。
     */
    private static List<ChatTurn> parseHistory(JsonNode body) {
        JsonNode history = body.get("history");
        if (history == null || !history.isArray() || history.isEmpty()) {
            return List.of();
        }
        List<ChatTurn> turns = new ArrayList<>();
        for (JsonNode item : history) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String role = text(item, "role");
            String content = text(item, "content");
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("user".equalsIgnoreCase(role)) {
                turns.add(ChatTurn.user(content));
            } else if ("assistant".equalsIgnoreCase(role)) {
                turns.add(ChatTurn.assistant(content, List.of()));
            }
        }
        return turns;
    }

    /** 从 query string 取单个键（已 URL-decode）。 */
    private static String query(URI uri, String key) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            if (key.equals(URLDecoder.decode(name, StandardCharsets.UTF_8))) {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** 从 {@code /v1/run/{id}/events} 这类路径里抽出中间的 id。 */
    private static String slice(String path, String prefix, String suffix) {
        return path.substring(prefix.length(), path.length() - suffix.length());
    }

    private static Thread daemon(String name, Runnable runnable) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** 当前正在跑（或刚结束、仍可拉 SSE）的一次任务。 */
    private static final class ActiveRun {
        final String id;
        final AgentLoop loop;
        final HttpApprovalService approvals;
        final EventLog events;
        volatile boolean finished;

        ActiveRun(String id, AgentLoop loop, HttpApprovalService approvals, EventLog events) {
            this.id = id;
            this.loop = loop;
            this.approvals = approvals;
            this.events = events;
        }
    }
}
