package com.mharness.agent;

/**
 * Agent 循环与上下文打包的上限。
 * token 相关上限交给 {@link com.mharness.context.TokenEstimator} 做粗略截断。
 * 可用环境变量覆盖：{@code M_HARNESS_MAX_STEPS}、{@code M_HARNESS_MAX_INPUT_TOKENS}、
 * {@code M_HARNESS_MAX_TOOL_RESULT_TOKENS}、{@code M_HARNESS_MAX_HISTORY_MESSAGES}。
 */
public record AgentLimits(
        int maxSteps,
        int maxInputTokens,
        int maxToolResultTokens,
        int maxHistoryMessages
) {
    public static final String MAX_STEPS = "M_HARNESS_MAX_STEPS";
    public static final String MAX_INPUT_TOKENS = "M_HARNESS_MAX_INPUT_TOKENS";
    public static final String MAX_TOOL_RESULT_TOKENS = "M_HARNESS_MAX_TOOL_RESULT_TOKENS";
    public static final String MAX_HISTORY_MESSAGES = "M_HARNESS_MAX_HISTORY_MESSAGES";

    /** 默认：最多 20 步、约 10 万输入 token、单条工具结果约 8k token、历史最多 40 条。 */
    public static AgentLimits defaults() {
        return fromEnv(System.getenv());
    }

    /** 测试或未设置环境变量时的硬编码默认。 */
    public static AgentLimits builtin() {
        return new AgentLimits(20, 100_000, 8_000, 40);
    }

    public static AgentLimits fromEnv(java.util.Map<String, String> env) {
        AgentLimits base = builtin();
        if (env == null || env.isEmpty()) {
            return base;
        }
        return new AgentLimits(
                intOr(env.get(MAX_STEPS), base.maxSteps()),
                intOr(env.get(MAX_INPUT_TOKENS), base.maxInputTokens()),
                intOr(env.get(MAX_TOOL_RESULT_TOKENS), base.maxToolResultTokens()),
                intOr(env.get(MAX_HISTORY_MESSAGES), base.maxHistoryMessages())
        );
    }

    private static int intOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 1 ? fallback : value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
