package com.mharness.llm;

import java.util.List;

/**
 * 对话中的一轮。助手轮次可携带 toolCalls；工具轮次用 toolId/toolName 把结果对回对应调用。
 */
public record ChatTurn(
        Role role,
        String content,
        String toolId,
        String toolName,
        List<LlmToolCall> toolCalls
) {
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public static ChatTurn system(String content) {
        return new ChatTurn(Role.SYSTEM, content, null, null, List.of());
    }

    public static ChatTurn user(String content) {
        return new ChatTurn(Role.USER, content, null, null, List.of());
    }

    public static ChatTurn assistant(String content, List<LlmToolCall> toolCalls) {
        return new ChatTurn(Role.ASSISTANT, content, null, null, toolCalls == null ? List.of() : toolCalls);
    }

    public static ChatTurn tool(String toolId, String toolName, String content) {
        return new ChatTurn(Role.TOOL, content, toolId, toolName, List.of());
    }
}
