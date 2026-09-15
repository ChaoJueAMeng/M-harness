package com.mharness.agent;

import com.mharness.checkpoint.Checkpoint;
import com.mharness.checkpoint.CheckpointService;
import com.mharness.context.ContextPacker;
import com.mharness.context.HistoryCompactor;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环：把用户任务交给模型，按步执行工具调用，直到模型给出最终回复、步数用尽、失败或被取消。
 * <p>
 * 真正会改工作区时，进入循环前会先拍 checkpoint；成功则删掉快照，异常则尝试回滚。
 */
public final class AgentLoop {
    private final ChatClient chatClient;
    private final ToolRegistry registry;
    private final PermissionPolicy policy;
    private final ContextPacker packer;
    private final CheckpointService checkpoints;
    private final AgentLimits limits;
    private final AgentObserver observer;
    /** 协作式取消标志；下一步开始前或执行下一个工具前会检查。 */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * 使用空观察者构造循环（CLI/测试不需要工具执行回调时用）。
     */
    public AgentLoop(
            ChatClient chatClient,
            ToolRegistry registry,
            PermissionPolicy policy,
            ContextPacker packer,
            CheckpointService checkpoints,
            AgentLimits limits
    ) {
        this(chatClient, registry, policy, packer, checkpoints, limits, AgentObserver.NONE);
    }

    /**
     * 完整构造：observer 为 null 时退化为 {@link AgentObserver#NONE}，避免空指针。
     */
    public AgentLoop(
            ChatClient chatClient,
            ToolRegistry registry,
            PermissionPolicy policy,
            ContextPacker packer,
            CheckpointService checkpoints,
            AgentLimits limits,
            AgentObserver observer
    ) {
        this.chatClient = chatClient;
        this.registry = registry;
        this.policy = policy;
        this.packer = packer;
        this.checkpoints = checkpoints;
        this.limits = limits;
        this.observer = observer == null ? AgentObserver.NONE : observer;
    }

    /** 请求取消当前任务；正在进行的模型调用不会被打断，但下一轮步/工具不会再执行。 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 当前是否已被取消。 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 执行一次完整任务：写入用户消息 →（Agent 且非 dry-run 时）拍快照 → 循环调用模型并执行工具。
     *
     * @param userTask 用户自然语言任务
     * @return 完成 / 步数上限 / 失败 / 取消 四种结局之一
     */
    public AgentOutcome run(String userTask) {
        return run(userTask, List.of());
    }

    /**
     * 同 {@link #run(String)}，但先把同会话更早的 USER/ASSISTANT 轮次经 {@link HistoryCompactor} 压进状态。
     * {@code prior} 为 null 时视为没有历史。本轮工具循环仍走 {@link ContextPacker}。
     */
    public AgentOutcome run(String userTask, List<ChatTurn> prior) {
        if (cancelled.get()) {
            return AgentOutcome.cancelled("已取消。", null);
        }
        AgentState state = new AgentState();
        for (ChatTurn turn : HistoryCompactor.compact(prior == null ? List.of() : prior)) {
            state.add(turn);
        }
        state.add(ChatTurn.user(userTask));
        Checkpoint checkpoint = null;
        // 只有真正会改工作区时才拍快照；Ask / dry-run 不会落盘，无需回滚点。
        boolean mutatingRun = policy.mode() == PermissionMode.AGENT && !policy.dryRun();
        if (mutatingRun) {
            // 把当前工作区拍成独立 git ref，失败时可 rollback。
            checkpoint = checkpoints.create();
            state.setCheckpoint(checkpoint);
        }
        try {
            // 把注册表里的工具转成模型可调用的 schema。
            List<ToolSpecification> specs = new ArrayList<>();
            for (AgentTool tool : registry.all()) {
                specs.add(tool.specification());
            }
            for (int step = 0; step < limits.maxSteps(); step++) {
                if (cancelled.get()) {
                    return AgentOutcome.cancelled("已取消。", checkpoint);
                }
                // 截断历史、注入系统提示后再发给模型，避免撑爆上下文。
                List<ChatTurn> packed = packer.pack(state.turns());
                LlmResponse response = chatClient.chat(packed, specs);
                if (!response.hasToolCalls()) {
                    // 模型不再调工具，视为任务结束；成功则丢掉不再需要的 checkpoint。
                    state.addAssistant(response.text(), List.of());
                    if (checkpoint != null) {
                        checkpoints.delete(checkpoint);
                    }
                    return AgentOutcome.completed(response.text());
                }
                state.addAssistant(response.text(), response.toolCalls());
                for (LlmToolCall call : response.toolCalls()) {
                    if (cancelled.get()) {
                        return AgentOutcome.cancelled("已取消。", checkpoint);
                    }
                    // 权限检查 + 真正执行（或 dry-run / 拒绝）一条工具调用。
                    ToolResult result = executeOne(call);
                    String payload = result.toJson();
                    String status = result.isDryRun() ? "DRY_RUN" : (result.success() ? "ok" : result.error());
                    observer.onTool(call.name(), status, payload.length());
                    // 工具结果必须回写对话，模型下一轮才能看到执行情况。
                    state.add(ChatTurn.tool(call.id(), call.name(), payload));
                }
            }
            return AgentOutcome.maxSteps("达到步数上限，已停止。", checkpoint);
        } catch (Exception e) {
            if (checkpoint != null) {
                try {
                    // 运行时异常时尽量把工作区恢复到进入任务前的快照。
                    checkpoints.rollback(checkpoint);
                } catch (Exception ignored) {
                    // keep original error
                }
            }
            return AgentOutcome.failed(e.getMessage(), checkpoint);
        }
    }

    /**
     * 执行单次工具调用：查注册表 → 权限策略裁决 → 按裁决允许、预览或拒绝。
     * 包可见，方便单测直接覆盖权限分支，而不必走完整模型循环。
     */
    ToolResult executeOne(LlmToolCall call) {
        AgentTool tool = registry.get(call.name());
        if (tool == null) {
            return ToolResult.error("UNKNOWN_TOOL", "未知工具: " + call.name());
        }
        // 硬拒绝、Ask 模式、dry-run、用户确认都在这里一次性判定。
        PermissionDecision decision = policy.evaluate(tool, call.arguments());
        return switch (decision) {
            case HARD_DENY -> ToolResult.error("HARD_DENY", "命令被硬性拒绝: " + call.arguments());
            case DENY_ASK_MODE -> ToolResult.error("PERMISSION_DENIED", "Ask 模式不能执行 " + tool.name());
            case DENIED_BY_USER -> ToolResult.error("USER_DENIED", "用户拒绝执行 " + tool.name());
            case DRY_RUN -> ToolResult.dryRun("Would execute " + tool.name() + " " + call.arguments());
            case ALLOW -> executeSafely(tool, call.arguments());
        };
    }

    /** 调用具体工具；任何异常都转成 TOOL_ERROR，避免把未捕获异常冲出主循环。 */
    private static ToolResult executeSafely(AgentTool tool, String arguments) {
        try {
            return tool.execute(arguments);
        } catch (Exception e) {
            return ToolResult.error("TOOL_ERROR", e.getMessage());
        }
    }
}
