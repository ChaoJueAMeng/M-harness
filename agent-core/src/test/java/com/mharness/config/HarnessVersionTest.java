package com.mharness.config;

import com.mharness.agent.AgentLimits;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessVersionTest {
    @Test
    void currentMatchesVersionFile() throws Exception {
        Path version = Path.of("..", "VERSION");
        if (!Files.isRegularFile(version)) {
            version = Path.of("VERSION");
        }
        assertThat(Files.isRegularFile(version)).isTrue();
        assertThat(Files.readString(version).trim()).isEqualTo(HarnessVersion.CURRENT);
    }

    @Test
    void limitsReadPositiveEnvOverrides() {
        AgentLimits limits = AgentLimits.fromEnv(Map.of(
                AgentLimits.MAX_STEPS, "7",
                AgentLimits.MAX_INPUT_TOKENS, "1000",
                AgentLimits.MAX_TOOL_RESULT_TOKENS, "200",
                AgentLimits.MAX_HISTORY_MESSAGES, "8"
        ));
        assertThat(limits.maxSteps()).isEqualTo(7);
        assertThat(limits.maxInputTokens()).isEqualTo(1000);
        assertThat(limits.maxToolResultTokens()).isEqualTo(200);
        assertThat(limits.maxHistoryMessages()).isEqualTo(8);
        assertThat(AgentLimits.fromEnv(Map.of(AgentLimits.MAX_STEPS, "0")).maxSteps())
                .isEqualTo(AgentLimits.builtin().maxSteps());
    }
}
