package com.mharness.agent;

/**
 * Agent 循环与上下文打包的上限。
 * token 相关上限按「约 4 字符 = 1 token」换算成字符，给 ContextPacker 做粗略截断。
 */
public record AgentLimits(
        int maxSteps,
        int maxInputTokens,
        int maxToolResultTokens,
        int maxHistoryMessages
) {
    /** 默认：最多 20 步、约 10 万输入 token、单条工具结果约 8k token、历史最多 40 条。 */
    public static AgentLimits defaults() {
        return new AgentLimits(20, 100_000, 8_000, 40);
    }

    /** 单条工具结果允许保留的最大字符数。 */
    public int maxToolResultChars() {
        return maxToolResultTokens * 4;
    }

    /** 整包发给模型的对话允许的最大字符数。 */
    public int maxInputChars() {
        return maxInputTokens * 4;
    }
}
