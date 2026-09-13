package com.mharness.llm;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.List;

/**
 * 对话模型客户端。实现可以是真实的 OpenAI 兼容接口，也可以是测试用的脚本化假客户端。
 */
public interface ChatClient {
    /**
     * 发送当前对话和可用工具 schema，返回模型文本和/或工具调用。
     */
    LlmResponse chat(List<ChatTurn> turns, List<ToolSpecification> tools);
}
