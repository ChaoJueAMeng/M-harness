package com.mharness.agent;

import com.mharness.checkpoint.CheckpointService;
import com.mharness.context.ContextPacker;
import com.mharness.llm.ChatClient;
import com.mharness.llm.OpenAiCompatibleChatClient;
import com.mharness.llm.TokenListener;
import com.mharness.permission.ApprovalService;
import com.mharness.permission.PermissionMode;
import com.mharness.permission.PermissionPolicy;
import com.mharness.tool.GlobTool;
import com.mharness.tool.GrepTool;
import com.mharness.tool.ReadFileTool;
import com.mharness.tool.RunTerminalTool;
import com.mharness.tool.SearchReplaceTool;
import com.mharness.tool.ToolRegistry;
import com.mharness.tool.WriteFileTool;
import com.mharness.workspace.WorkspaceGuard;

import java.nio.file.Path;
import java.util.List;

public final class AgentRuntime {
    private final WorkspaceGuard guard;
    private final ToolRegistry registry;
    private final CheckpointService checkpoints;
    private final AgentLimits limits;

    public AgentRuntime(Path workspace) {
        this.guard = new WorkspaceGuard(workspace);
        this.registry = new ToolRegistry(List.of(
                new GlobTool(guard),
                new GrepTool(guard),
                new ReadFileTool(guard),
                new SearchReplaceTool(guard),
                new WriteFileTool(guard),
                new RunTerminalTool(guard)
        ));
        this.checkpoints = new CheckpointService(guard);
        this.limits = AgentLimits.defaults();
    }

    public WorkspaceGuard guard() {
        return guard;
    }

    public CheckpointService checkpoints() {
        return checkpoints;
    }

    public AgentLoop createLoop(
            ChatClient chatClient,
            PermissionMode mode,
            boolean dryRun,
            boolean autoApprove,
            ApprovalService approvalService
    ) {
        PermissionPolicy policy = new PermissionPolicy(mode, dryRun, autoApprove, approvalService);
        ContextPacker packer = new ContextPacker(guard, limits, mode, dryRun);
        return new AgentLoop(chatClient, registry, policy, packer, checkpoints, limits);
    }

    public AgentLoop createLoop(
            String baseUrl,
            String apiKey,
            String model,
            PermissionMode mode,
            boolean dryRun,
            boolean autoApprove,
            ApprovalService approvalService,
            TokenListener listener
    ) {
        return createLoop(
                new OpenAiCompatibleChatClient(baseUrl, apiKey, model, listener),
                mode,
                dryRun,
                autoApprove,
                approvalService
        );
    }
}
