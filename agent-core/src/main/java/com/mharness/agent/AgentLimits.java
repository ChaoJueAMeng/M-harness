package com.mharness.agent;

public record AgentLimits(
        int maxSteps,
        int maxInputTokens,
        int maxToolResultTokens,
        int maxHistoryMessages
) {
    public static AgentLimits defaults() {
        return new AgentLimits(20, 100_000, 8_000, 40);
    }

    public int maxToolResultChars() {
        return maxToolResultTokens * 4;
    }

    public int maxInputChars() {
        return maxInputTokens * 4;
    }
}
