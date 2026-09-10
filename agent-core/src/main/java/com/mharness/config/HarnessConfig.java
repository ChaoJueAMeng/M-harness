package com.mharness.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HarnessConfig {
    public static final String BASE_URL = "M_HARNESS_BASE_URL";
    public static final String API_KEY = "M_HARNESS_API_KEY";
    public static final String MODEL = "M_HARNESS_MODEL";

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public HarnessConfig(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public static HarnessConfig load(Path workspace) {
        Map<String, String> env = new LinkedHashMap<>();
        loadDotEnv(workspace.resolve(".env"), env);
        loadDotEnv(Path.of(".env"), env);
        String baseUrl = first(env, BASE_URL, "https://api.openai.com/v1");
        String apiKey = first(env, API_KEY, "");
        String model = first(env, MODEL, "gpt-4o-mini");
        return new HarnessConfig(baseUrl, apiKey, model);
    }

    public void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 " + API_KEY + "。请设置环境变量或在 .env 中配置。");
        }
    }

    private static String first(Map<String, String> env, String key, String defaultValue) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return env.getOrDefault(key, defaultValue);
    }

    static void loadDotEnv(Path file, Map<String, String> into) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                into.putIfAbsent(key, value);
            }
        } catch (IOException ignored) {
            // missing optional env file
        }
    }
}
