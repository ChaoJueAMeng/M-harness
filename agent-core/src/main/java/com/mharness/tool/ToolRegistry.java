package com.mharness.tool;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(Collection<AgentTool> agentTools) {
        for (AgentTool tool : agentTools) {
            tools.put(tool.name(), tool);
        }
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<AgentTool> all() {
        return List.copyOf(tools.values());
    }
}
