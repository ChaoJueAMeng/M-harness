package com.mharness.tool;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * Agent 可调用的一项工具：名字、给模型看的 JSON schema、以及根据参数真正执行。
 */
public interface AgentTool {
    /** 工具名，须与模型 function call 的 name 一致，例如 {@code read_file}。 */
    String name();

    /** LangChain4j 工具描述，发给模型让它知道参数长什么样。 */
    ToolSpecification specification();

    /**
     * 执行工具。{@code arguments} 是模型给出的 JSON 字符串。
     * 业务错误应返回 {@link ToolResult} 而不是抛异常；未预期的异常由 AgentLoop 捕获。
     */
    ToolResult execute(String arguments) throws Exception;
}
