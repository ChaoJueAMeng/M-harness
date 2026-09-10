package com.mharness.llm;

import java.util.List;

public record LlmResponse(String text, List<LlmToolCall> toolCalls) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
