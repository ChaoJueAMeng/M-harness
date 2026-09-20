package com.mharness.llm;

import java.util.List;

/**
 * 模型一轮回复：自然语言 text，以及（可选）要执行的工具调用列表。
 */
public record LlmResponse(String text, List<LlmToolCall> toolCalls, Integer inputTokens, Integer outputTokens) {
    public LlmResponse(String text, List<LlmToolCall> toolCalls) {
        this(text, toolCalls, null, null);
    }

    /** 本轮是否要求执行工具；没有则 AgentLoop 视为任务结束。 */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
