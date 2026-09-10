package com.mharness.tool;

import dev.langchain4j.agent.tool.ToolSpecification;

public interface AgentTool {
    String name();

    ToolSpecification specification();

    ToolResult execute(String arguments) throws Exception;
}
