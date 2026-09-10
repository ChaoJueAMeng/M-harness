package com.mharness.llm;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

public final class ScriptedChatClient implements ChatClient {
    private final Queue<LlmResponse> responses = new ArrayDeque<>();

    public ScriptedChatClient(LlmResponse... scripted) {
        responses.addAll(List.of(scripted));
    }

    @Override
    public LlmResponse chat(List<ChatTurn> turns, List<ToolSpecification> tools) {
        LlmResponse next = responses.poll();
        if (next == null) {
            return new LlmResponse("done", List.of());
        }
        return next;
    }
}
