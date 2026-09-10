package com.mharness.llm;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

public interface ChatClient {
    LlmResponse chat(List<ChatTurn> turns, List<ToolSpecification> tools);
}
