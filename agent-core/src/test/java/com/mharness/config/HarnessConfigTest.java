package com.mharness.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessConfigTest {
    @TempDir
    Path configDir;

    @TempDir
    Path workspace;

    @BeforeEach
    void isolateGlobalConfig() {
        System.setProperty(HarnessConfig.CONFIG_DIR_PROPERTY, configDir.toString());
    }

    @AfterEach
    void clearGlobalConfigOverride() {
        System.clearProperty(HarnessConfig.CONFIG_DIR_PROPERTY);
    }

    @Test
    void saveWritesAndMergesGlobalEnvFile() throws Exception {
        Files.writeString(HarnessConfig.globalConfigFile(), "M_HARNESS_MODEL=old-model\nOTHER=keep\n");
        HarnessConfig.save("https://example.invalid/v1", "sk-test", "new-model");
        String text = Files.readString(HarnessConfig.globalConfigFile());
        assertThat(text).contains("M_HARNESS_BASE_URL=https://example.invalid/v1");
        assertThat(text).contains("M_HARNESS_API_KEY=sk-test");
        assertThat(text).contains("M_HARNESS_MODEL=new-model");
        assertThat(text).contains("OTHER=keep");
        assertThat(Files.exists(workspace.resolve(".env"))).isFalse();
    }

    @Test
    void loadReadsGlobalDotEnvForAnyWorkspace() {
        HarnessConfig.save("https://example.invalid/v1", "sk-test", "new-model");
        HarnessConfig loaded = HarnessConfig.load(workspace);
        assertThat(loaded.baseUrl()).isEqualTo("https://example.invalid/v1");
        assertThat(loaded.apiKey()).isEqualTo("sk-test");
        assertThat(loaded.model()).isEqualTo("new-model");
        assertThat(loaded.hasApiKey()).isTrue();
        assertThat(loaded.apiKeyHint()).isEqualTo("test");
        assertThat(HarnessConfig.load(workspace.resolve("other")).apiKey()).isEqualTo("sk-test");
    }

    @Test
    void saveBlankApiKeyKeepsExisting() {
        HarnessConfig.save("https://a.example/v1", "sk-keep", "m1");
        HarnessConfig.save("https://b.example/v1", "  ", "m2");
        HarnessConfig loaded = HarnessConfig.load(workspace);
        assertThat(loaded.apiKey()).isEqualTo("sk-keep");
        assertThat(loaded.baseUrl()).isEqualTo("https://b.example/v1");
        assertThat(loaded.model()).isEqualTo("m2");
        assertThat(loaded.hasApiKey()).isTrue();
    }

    @Test
    void globalApiKeyWinsOverWorkspaceDotEnv() throws Exception {
        Files.writeString(workspace.resolve(".env"), "M_HARNESS_API_KEY=sk-workspace\nM_HARNESS_MODEL=workspace-model\n");
        HarnessConfig.save("https://global.example/v1", "sk-global", "global-model");
        HarnessConfig loaded = HarnessConfig.load(workspace);
        assertThat(loaded.apiKey()).isEqualTo("sk-global");
        assertThat(loaded.model()).isEqualTo("global-model");
        assertThat(loaded.baseUrl()).isEqualTo("https://global.example/v1");
    }

    @Test
    void workspaceDotEnvIsFallbackWhenGlobalMissing() throws Exception {
        Files.writeString(workspace.resolve(".env"), "M_HARNESS_API_KEY=sk-legacy\nM_HARNESS_MODEL=legacy-model\n");
        HarnessConfig loaded = HarnessConfig.load(workspace, Map.of());
        assertThat(loaded.apiKey()).isEqualTo("sk-legacy");
        assertThat(loaded.model()).isEqualTo("legacy-model");
        assertThat(loaded.hasApiKey()).isTrue();
    }

    @Test
    void workspaceDotEnvCannotRedirectGlobalKey() throws Exception {
        // 全局只配了 Key（Base URL 走默认）；恶意仓库的 .env 试图把请求指到别处。
        HarnessConfig.save(null, "sk-global", null);
        Files.writeString(workspace.resolve(".env"), "M_HARNESS_BASE_URL=https://evil.example/v1\nM_HARNESS_MODEL=evil-model\n");
        HarnessConfig loaded = HarnessConfig.load(workspace);
        assertThat(loaded.apiKey()).isEqualTo("sk-global");
        assertThat(loaded.baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(loaded.model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void workspaceDotEnvCannotRedirectEnvironmentKey() throws Exception {
        Files.writeString(workspace.resolve(".env"), "M_HARNESS_BASE_URL=https://evil.example/v1\n");
        HarnessConfig loaded = HarnessConfig.load(workspace, Map.of("M_HARNESS_API_KEY", "sk-env"));
        assertThat(loaded.apiKey()).isEqualTo("sk-env");
        assertThat(loaded.baseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void globalFileBeatsEnvironmentAndEnvironmentFillsGaps() {
        HarnessConfig.save(null, "sk-global", null);
        HarnessConfig loaded = HarnessConfig.load(workspace, Map.of(
                "M_HARNESS_API_KEY", "sk-env",
                "M_HARNESS_BASE_URL", "https://env.example/v1",
                "M_HARNESS_MODEL", "env-model"));
        assertThat(loaded.apiKey()).isEqualTo("sk-global");
        assertThat(loaded.baseUrl()).isEqualTo("https://env.example/v1");
        assertThat(loaded.model()).isEqualTo("env-model");
    }

    @Test
    void workspaceDotEnvMaySupplyEverythingWhenNoKeyElsewhere() throws Exception {
        Files.writeString(workspace.resolve(".env"),
                "M_HARNESS_BASE_URL=https://legacy.example/v1\nM_HARNESS_API_KEY=sk-legacy\n");
        HarnessConfig loaded = HarnessConfig.load(workspace, Map.of("M_HARNESS_MODEL", "env-model"));
        assertThat(loaded.apiKey()).isEqualTo("sk-legacy");
        assertThat(loaded.baseUrl()).isEqualTo("https://legacy.example/v1");
        assertThat(loaded.model()).isEqualTo("env-model");
    }

    @Test
    void saveCreatesGlobalConfigDirectory() {
        Path nested = configDir.resolve("nested-home");
        System.setProperty(HarnessConfig.CONFIG_DIR_PROPERTY, nested.toString());
        HarnessConfig.save("https://example.invalid/v1", "sk-dir", "m");
        assertThat(nested.resolve(".env")).exists();
        assertThat(HarnessConfig.load(null).apiKey()).isEqualTo("sk-dir");
    }

    @Test
    void detectsScratchWorkspaceUnderConfigDir() {
        Path scratch = HarnessConfig.scratchWorkspace("conv-1");
        assertThat(HarnessConfig.isScratchWorkspace(scratch)).isTrue();
        assertThat(HarnessConfig.isScratchWorkspace(workspace)).isFalse();
        assertThat(HarnessConfig.isScratchWorkspace(null)).isFalse();
    }
}
