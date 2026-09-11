package com.mharness.agent;

import com.mharness.checkpoint.Checkpoint;
import com.mharness.checkpoint.CheckpointService;
import com.mharness.context.ContextPacker;
import com.mharness.llm.ChatClient;
import com.mharness.llm.ChatTurn;
import com.mharness.llm.LlmResponse;
import com.mharness.llm.LlmToolCall;
import com.mharness.permission.PermissionDecision;
import com.mharness.permission.PermissionMode;
import com.mharness.permission.PermissionPolicy;
import com.mharness.tool.AgentTool;
import com.mharness.tool.ToolRegistry;
import com.mharness.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.List;

public final class AgentLoop {
    private final ChatClient chatClient;
    private final ToolRegistry registry;
    private final PermissionPolicy policy;
    private final ContextPacker packer;
    private final CheckpointService checkpoints;
    private final AgentLimits limits;

    public AgentLoop(
            ChatClient chatClient,
            ToolRegistry registry,
            PermissionPolicy policy,
            ContextPacker packer,
            CheckpointService checkpoints,
            AgentLimits limits
    ) {
        this.chatClient = chatClient;
        this.registry = registry;
        this.policy = policy;
        this.packer = packer;
        this.checkpoints = checkpoints;
        this.limits = limits;
    }

    public AgentOutcome run(String userTask) {
        AgentState state = new AgentState();
        state.add(ChatTurn.user(userTask));
        Checkpoint checkpoint = null;
        boolean mutatingRun = policy.mode() == PermissionMode.AGENT && !policy.dryRun();
        if (mutatingRun) {
            checkpoint = checkpoints.create();
            state.setCheckpoint(checkpoint);
        }
        try {
            List<ToolSpecification> specs = new ArrayList<>();
            for (AgentTool tool : registry.all()) {
                specs.add(tool.specification());
            }
            for (int step = 0; step < limits.maxSteps(); step++) {
                List<ChatTurn> packed = packer.pack(state.turns());
                LlmResponse response = chatClient.chat(packed, specs);
                if (!response.hasToolCalls()) {
                    state.addAssistant(response.text(), List.of());
                    if (checkpoint != null) {
                        checkpoints.delete(checkpoint);
                    }
                    return AgentOutcome.completed(response.text());
                }
                state.addAssistant(response.text(), response.toolCalls());
                for (LlmToolCall call : response.toolCalls()) {
                    ToolResult result = executeOne(call);
                    String payload = result.toJson();
                    String status = result.isDryRun() ? "DRY_RUN" : (result.success() ? "ok" : result.error());
                    System.err.printf("[m-harness] %s %s (%d chars)%n",
                            call.name(),
                            status,
                            payload.length());
                    state.add(ChatTurn.tool(call.id(), call.name(), payload));
                }
            }
            return AgentOutcome.maxSteps("达到步数上限，已停止。", checkpoint);
        } catch (Exception e) {
            if (checkpoint != null) {
                try {
                    checkpoints.rollback(checkpoint);
                } catch (Exception ignored) {
                    // keep original error
                }
            }
            return AgentOutcome.failed(e.getMessage(), checkpoint);
        }
    }

    ToolResult executeOne(LlmToolCall call) {
        AgentTool tool = registry.get(call.name());
        if (tool == null) {
            return ToolResult.error("UNKNOWN_TOOL", "未知工具: " + call.name());
        }
        PermissionDecision decision = policy.evaluate(tool, call.arguments());
        return switch (decision) {
            case HARD_DENY -> ToolResult.error("HARD_DENY", "命令被硬性拒绝: " + call.arguments());
            case DENY_ASK_MODE -> ToolResult.error("PERMISSION_DENIED", "Ask 模式不能执行 " + tool.name());
            case DENIED_BY_USER -> ToolResult.error("USER_DENIED", "用户拒绝执行 " + tool.name());
            case DRY_RUN -> ToolResult.dryRun("Would execute " + tool.name() + " " + call.arguments());
            case ALLOW -> executeSafely(tool, call.arguments());
        };
    }

    private static ToolResult executeSafely(AgentTool tool, String arguments) {
        try {
            return tool.execute(arguments);
        } catch (Exception e) {
            return ToolResult.error("TOOL_ERROR", e.getMessage());
        }
    }
}
