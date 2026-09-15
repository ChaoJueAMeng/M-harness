package com.mharness.llm;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * 测试用 ChatClient：按构造时给定的顺序返回预设 {@link LlmResponse}，用尽后返回纯文本 {@code done}。
 */
public final class ScriptedChatClient implements ChatClient {
    private final Queue<LlmResponse> responses = new ArrayDeque<>();
    private final List<List<ChatTurn>> calls = new ArrayList<>();

    public ScriptedChatClient(LlmResponse... scripted) {
        responses.addAll(List.of(scripted));
    }

    /** 每次 {@link #chat} 收到的轮次快照，供断言多轮 prior 是否进入模型。 */
    public List<List<ChatTurn>> calls() {
        return calls;
    }

    @Override
    public LlmResponse chat(List<ChatTurn> turns, List<ToolSpecification> tools) {
        calls.add(List.copyOf(turns));
        LlmResponse next = responses.poll();
        if (next == null) {
            return new LlmResponse("done", List.of());
        }
        return next;
    }
}
