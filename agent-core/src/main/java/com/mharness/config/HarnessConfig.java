package com.mharness.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型接口配置：Base URL、API Key、模型名。
 * 加载顺序：全局 {@code ~/.m-harness/.env} → 进程环境变量 → 内置默认值；
 * 工作区 / 当前目录 {@code .env} 仅在前两者都没有 API Key 时作为旧配置回退读取。
 * 保存只写入全局配置，所有工作区共用同一份 API Key。
 */
public final class HarnessConfig {
    public static final String BASE_URL = "M_HARNESS_BASE_URL";
    public static final String API_KEY = "M_HARNESS_API_KEY";
    public static final String MODEL = "M_HARNESS_MODEL";
    public static final String CONFIG_DIR_PROPERTY = "m.harness.config.dir";
    public static final String CONFIG_DIR_ENV = "M_HARNESS_CONFIG_DIR";

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public HarnessConfig(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** OpenAI 兼容接口的根路径，例如 {@code https://api.deepseek.com/v1}。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** API Key；可能为空字符串，调用前应 {@link #requireApiKey()}。 */
    public String apiKey() {
        return apiKey;
    }

    /** 模型名，例如 {@code gpt-4o-mini}。 */
    public String model() {
        return model;
    }

    /** 全局配置目录，默认 {@code ~/.m-harness}。可用系统属性或环境变量改路径。 */
    public static Path globalConfigDir() {
        String override = System.getProperty(CONFIG_DIR_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv(CONFIG_DIR_ENV);
        }
        if (override == null || override.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".m-harness");
        }
        return Path.of(override);
    }

    /** 全局配置文件 {@code .env}。 */
    public static Path globalConfigFile() {
        return globalConfigDir().resolve(".env");
    }

    /** 未绑定工作区时桌面端使用的临时码本根目录 {@code ~/.m-harness/scratch}。 */
    public static Path scratchRoot() {
        return globalConfigDir().resolve("scratch");
    }

    /** 某条对话对应的临时码本目录。 */
    public static Path scratchWorkspace(String conversationId) {
        return scratchRoot().resolve(conversationId);
    }

    /** 工作区是否位于临时码本根目录下（未绑定真实项目）。 */
    public static boolean isScratchWorkspace(Path workspace) {
        if (workspace == null) {
            return false;
        }
        Path root = scratchRoot().toAbsolutePath().normalize();
        Path cwd = workspace.toAbsolutePath().normalize();
        return cwd.startsWith(root);
    }

    /**
     * 组装配置。优先级：全局 {@code ~/.m-harness/.env} → 进程环境变量 → 内置默认值。
     * <p>
     * 工作区 {@code .env} 与当前目录 {@code .env} 只是旧版配置的回退：<b>仅当全局配置和环境变量都没有提供 API Key 时</b>
     * 才会读取。工作区内容是不可信的项目文件，若允许它在用户已有 Key 的情况下改写 {@code M_HARNESS_BASE_URL}，
     * 打开一个恶意仓库就能把用户的 Key 发到攻击者的服务器。
     * {@code workspace} 可为 {@code null}，此时不读工作区文件。
     */
    public static HarnessConfig load(Path workspace) {
        return load(workspace, System.getenv());
    }

    /** 同 {@link #load(Path)}，但环境变量由调用方传入，便于测试覆盖优先级。 */
    static HarnessConfig load(Path workspace, Map<String, String> processEnv) {
        Map<String, String> values = new LinkedHashMap<>();
        loadDotEnv(globalConfigFile(), values);
        for (String key : List.of(BASE_URL, API_KEY, MODEL)) {
            String fromEnv = processEnv == null ? null : processEnv.get(key);
            if (fromEnv != null && !fromEnv.isBlank()) {
                values.putIfAbsent(key, fromEnv);
            }
        }
        if (isBlank(values.get(API_KEY))) {
            if (workspace != null) {
                loadDotEnv(workspace.resolve(".env"), values);
            }
            loadDotEnv(Path.of(".env"), values);
        }
        String baseUrl = valueOr(values, BASE_URL, "https://api.openai.com/v1");
        String apiKey = valueOr(values, API_KEY, "");
        String model = valueOr(values, MODEL, "gpt-4o-mini");
        return new HarnessConfig(baseUrl, apiKey, model);
    }

    /** 没有 API Key 时抛错，避免带着空密钥去调模型。 */
    public void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少 " + API_KEY + "。请在桌面端「设置」中填写，或写入 " + globalConfigFile() + "。");
        }
    }

    /** 是否已配置非空 API Key。 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 仅返回 Key 的后四位，用于界面提示「已设置 …xxxx」。
     * 未设置或过短时返回空串，绝不回显完整密钥。
     */
    public String apiKeyHint() {
        if (!hasApiKey() || apiKey.length() < 4) {
            return "";
        }
        return apiKey.substring(apiKey.length() - 4);
    }

    /**
     * 把非空字段写入全局 {@code ~/.m-harness/.env}：先读出已有键值再覆盖指定项，保留用户其它变量。
     * apiKey 为空表示「不改原值」。
     */
    public static void save(String baseUrl, String apiKey, String model) {
        writeEnvFile(globalConfigFile(), baseUrl, apiKey, model);
    }

    /**
     * 把非空字段写入指定 dotenv 文件，供测试或手动迁移使用。
     * apiKey 为空表示「不改原值」。
     */
    static void writeEnvFile(Path file, String baseUrl, String apiKey, String model) {
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            loadDotEnvOverwrite(file, values);
        }
        putIfPresent(values, BASE_URL, baseUrl);
        putIfPresent(values, API_KEY, apiKey);
        putIfPresent(values, MODEL, model);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入 " + file, e);
        }
    }

    /** 已合并的值非空则用它，否则用默认值。 */
    private static String valueOr(Map<String, String> values, String key, String defaultValue) {
        String value = values.get(key);
        return isBlank(value) ? defaultValue : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 解析 dotenv：跳过空行和注释，去掉成对引号。
     * {@code putIfAbsent} 保证先加载的文件优先生效。
     */
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

    /** 与 {@link #loadDotEnv} 相同格式，但后写覆盖先写，用于保存前读出完整文件。 */
    private static void loadDotEnvOverwrite(Path file, Map<String, String> into) {
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
                into.put(key, value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 " + file, e);
        }
    }

    /** 只在调用方显式传入非空值时覆盖，避免把空 API Key 写进文件。 */
    private static void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }
}
