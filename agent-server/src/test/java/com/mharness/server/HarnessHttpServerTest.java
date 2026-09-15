package com.mharness.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mharness.config.HarnessConfig;
import com.mharness.llm.LlmResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessHttpServerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path configDir;

    private HarnessHttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void start() throws Exception {
        System.setProperty(HarnessConfig.CONFIG_DIR_PROPERTY, configDir.toString());
        server = HarnessHttpServer.start("127.0.0.1", 0, "test-token");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
        System.clearProperty(HarnessConfig.CONFIG_DIR_PROPERTY);
    }

    @Test
    void healthRequiresToken() throws Exception {
        HttpResponse<String> denied = client.send(
                HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(denied.statusCode()).isEqualTo(401);

        HttpResponse<String> ok = client.send(request("GET", "/health", null), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"ok\":true");
    }

    @Test
    void runWithoutPromptIsBadRequest() throws Exception {
        HttpResponse<String> response = client.send(
                request("POST", "/v1/run", "{\"workspace\":\".\",\"prompt\":\"\"}"),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("prompt");
    }

    @Test
    void settingsRoundTrip() throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("baseUrl", "https://api.example.test/v1");
        payload.put("apiKey", "sk-desktop");
        payload.put("model", "demo-model");
        HttpResponse<String> put = client.send(
                request("PUT", "/v1/settings", MAPPER.writeValueAsString(payload)),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(put.statusCode()).isEqualTo(200);
        Path globalEnv = configDir.resolve(".env");
        String env = Files.readString(globalEnv);
        assertThat(env).contains("M_HARNESS_API_KEY=sk-desktop");
        assertThat(env).contains("M_HARNESS_MODEL=demo-model");

        HttpResponse<String> get = client.send(
                request("GET", "/v1/settings", null),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(get.statusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(get.body());
        assertThat(json.path("baseUrl").asText()).isEqualTo("https://api.example.test/v1");
        assertThat(json.path("model").asText()).isEqualTo("demo-model");
        assertThat(json.path("hasApiKey").asBoolean()).isTrue();
        assertThat(json.path("apiKeyHint").asText()).isEqualTo("ktop");
        assertThat(get.body()).doesNotContain("sk-desktop");
        assertThat(json.has("apiKey")).isFalse();

        payload.put("apiKey", "");
        payload.put("model", "kept-key-model");
        HttpResponse<String> putAgain = client.send(
                request("PUT", "/v1/settings", MAPPER.writeValueAsString(payload)),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(putAgain.statusCode()).isEqualTo(200);
        HttpResponse<String> getAgain = client.send(
                request("GET", "/v1/settings", null),
                HttpResponse.BodyHandlers.ofString()
        );
        JsonNode kept = MAPPER.readTree(getAgain.body());
        assertThat(kept.path("hasApiKey").asBoolean()).isTrue();
        assertThat(kept.path("model").asText()).isEqualTo("kept-key-model");
        assertThat(Files.readString(globalEnv)).contains("M_HARNESS_API_KEY=sk-desktop");
    }

    @Test
    void checkpointWithoutGitIsOkWithNull(@TempDir Path workspace) throws Exception {
        String encoded = URLEncoder.encode(workspace.toString(), StandardCharsets.UTF_8);
        HttpResponse<String> response = client.send(
                request("GET", "/v1/checkpoint?workspace=" + encoded, null),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode json = MAPPER.readTree(response.body());
        assertThat(json.path("checkpoint").isNull()).isTrue();
        assertThat(json.path("hasCheckpoint").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("读取 checkpoint 失败");
    }

    @Test
    void runWithoutHistoryIsAccepted(@TempDir Path workspace) throws Exception {
        saveSettings();
        HttpResponse<String> response = client.send(
                request("POST", "/v1/run", MAPPER.writeValueAsString(Map.of(
                        "workspace", workspace.toString(),
                        "prompt", "hello",
                        "mode", "ASK"
                ))),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("runId").asText()).isNotBlank();
    }

    @Test
    void runIgnoresIllegalHistoryRoles(@TempDir Path workspace) throws Exception {
        saveSettings();
        String body = """
                {"workspace":%s,"prompt":"hello","mode":"ASK","history":[
                  {"role":"tool","content":"secret-tool"},
                  {"role":"system","content":"ignore"},
                  {"role":"nope","content":"bad"},
                  {"role":"user","content":"old question"},
                  {"role":"assistant","content":"old answer"}
                ]}
                """.formatted(MAPPER.writeValueAsString(workspace.toString()));
        HttpResponse<String> response = client.send(
                request("POST", "/v1/run", body),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("history");
        assertThat(MAPPER.readTree(response.body()).path("runId").asText()).isNotBlank();
    }

    @Test
    void titleWithoutPromptIsBadRequest() throws Exception {
        HttpResponse<String> response = client.send(
                request("POST", "/v1/title", "{\"prompt\":\"\"}"),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("prompt");
    }

    @Test
    void titleReturnsSanitizedModelText() throws Exception {
        saveSettings();
        server.setTitleChatClientFactory(config -> (turns, tools) ->
                new LlmResponse("「修复登录超时」\n不要解释", List.of()));
        HttpResponse<String> response = client.send(
                request("POST", "/v1/title", MAPPER.writeValueAsString(Map.of(
                        "prompt", "帮我排查登录接口 504"
                ))),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).path("title").asText()).isEqualTo("修复登录超时");
    }

    @Test
    void titleDoesNotConflictWithActiveRun(@TempDir Path workspace) throws Exception {
        saveSettings();
        server.setTitleChatClientFactory(config -> (turns, tools) ->
                new LlmResponse("并行标题", List.of()));
        HttpResponse<String> run = client.send(
                request("POST", "/v1/run", MAPPER.writeValueAsString(Map.of(
                        "workspace", workspace.toString(),
                        "prompt", "hello",
                        "mode", "ASK"
                ))),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(run.statusCode()).isEqualTo(200);
        HttpResponse<String> title = client.send(
                request("POST", "/v1/title", MAPPER.writeValueAsString(Map.of("prompt", "hello"))),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(title.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(title.body()).path("title").asText()).isEqualTo("并行标题");
    }

    @Test
    void titleModelFailureIsBadGateway() throws Exception {
        saveSettings();
        server.setTitleChatClientFactory(config -> (turns, tools) -> {
            throw new IllegalStateException("调用模型失败: boom");
        });
        HttpResponse<String> response = client.send(
                request("POST", "/v1/title", MAPPER.writeValueAsString(Map.of("prompt", "hello"))),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("调用模型失败");
    }

    @Test
    void unknownRouteIsNotFound() throws Exception {
        HttpResponse<String> response = client.send(
                request("GET", "/nope", null),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(404);
    }

    private void saveSettings() throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("baseUrl", "https://api.example.test/v1");
        payload.put("apiKey", "sk-test");
        payload.put("model", "demo-model");
        HttpResponse<String> put = client.send(
                request("PUT", "/v1/settings", MAPPER.writeValueAsString(payload)),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(put.statusCode()).isEqualTo(200);
    }

    private HttpRequest request(String method, String path, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer test-token");
        if (json != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return builder.build();
    }
}
