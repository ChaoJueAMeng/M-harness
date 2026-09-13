package com.mharness.llm;

/**
 * 模型发出的一次工具调用。
 *
 * @param id        调用 id，工具结果必须用同一 id 回写
 * @param name      工具名
 * @param arguments JSON 参数字符串
 */
public record LlmToolCall(String id, String name, String arguments) {
}
