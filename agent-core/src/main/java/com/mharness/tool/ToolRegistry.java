package com.mharness.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按工具名查找 {@link AgentTool}。用 LinkedHashMap 保持注册顺序，发给模型的 schema 顺序稳定。
 */
public final class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(Collection<AgentTool> agentTools) {
        for (AgentTool tool : agentTools) {
            tools.put(tool.name(), tool);
        }
    }

    /** 按名字取工具；未知名字返回 null，由循环转成 UNKNOWN_TOOL。 */
    public AgentTool get(String name) {
        return tools.get(name);
    }

    /** 全部已注册工具的不可变快照。 */
    public List<AgentTool> all() {
        return List.copyOf(tools.values());
    }
}
