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

/**
 * 围绕某个工作区组装 Agent 运行时：路径守卫、工具集、checkpoint 服务，以及按需创建 {@link AgentLoop}。
 * CLI 和 HTTP 服务都通过这里拿到一次任务所需的循环实例。
 */
public final class AgentRuntime {
    private final WorkspaceGuard guard;
    private final ToolRegistry registry;
    private final CheckpointService checkpoints;
    private final AgentLimits limits;

    /**
     * 绑定工作区并注册全部内置工具（glob / grep / 读文件 / 替换 / 新建 / shell）。
     * 所有工具共用同一个 {@link WorkspaceGuard}，因此路径逃逸检查是统一的。
     */
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

    /** 当前工作区的路径守卫，供外部查询根目录。 */
    public WorkspaceGuard guard() {
        return guard;
    }

    /** checkpoint 服务，供 status / rollback 命令绕过主循环直接操作快照。 */
    public CheckpointService checkpoints() {
        return checkpoints;
    }

    /**
     * 用已有 ChatClient 创建循环（测试可注入 {@code ScriptedChatClient}）。
     */
    public AgentLoop createLoop(
            ChatClient chatClient,
            PermissionMode mode,
            boolean dryRun,
            boolean autoApprove,
            ApprovalService approvalService
    ) {
        return createLoop(chatClient, mode, dryRun, autoApprove, approvalService, AgentObserver.NONE);
    }

    /**
     * 组装权限策略、上下文打包器和主循环。
     *
     * @param mode            ASK 只读；AGENT 可写文件/跑命令
     * @param dryRun          true 时写操作只预览不落盘
     * @param autoApprove     true 时跳过人工确认（对应 CLI {@code --yes}）
     * @param approvalService 需要确认时向用户/桌面端询问
     */
    public AgentLoop createLoop(
            ChatClient chatClient,
            PermissionMode mode,
            boolean dryRun,
            boolean autoApprove,
            ApprovalService approvalService,
            AgentObserver observer
    ) {
        PermissionPolicy policy = new PermissionPolicy(mode, dryRun, autoApprove, approvalService);
        ContextPacker packer = new ContextPacker(guard, limits, mode, dryRun);
        return new AgentLoop(chatClient, registry, policy, packer, checkpoints, limits, observer);
    }

    /**
     * 按 OpenAI 兼容接口参数创建循环，流式 token 通过 listener 回调。
     */
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
                baseUrl,
                apiKey,
                model,
                mode,
                dryRun,
                autoApprove,
                approvalService,
                listener,
                AgentObserver.NONE
        );
    }

    /**
     * 生产路径：构造 {@link OpenAiCompatibleChatClient} 后再交给上面的 createLoop。
     */
    public AgentLoop createLoop(
            String baseUrl,
            String apiKey,
            String model,
            PermissionMode mode,
            boolean dryRun,
            boolean autoApprove,
            ApprovalService approvalService,
            TokenListener listener,
            AgentObserver observer
    ) {
        return createLoop(
                new OpenAiCompatibleChatClient(baseUrl, apiKey, model, listener),
                mode,
                dryRun,
                autoApprove,
                approvalService,
                observer
        );
    }
}
