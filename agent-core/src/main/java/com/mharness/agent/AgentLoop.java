package com.mharness.agent;

import com.mharness.checkpoint.Checkpoint;
import com.mharness.checkpoint.CheckpointService;
import com.mharness.config.HarnessLog;
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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 主循环：把用户任务交给模型，按步执行工具调用，直到模型给出最终回复、步数用尽、失败或被取消。
 * <p>
 * 真正会改工作区时，进入循环前会先拍 checkpoint；成功则删掉快照，异常则尝试回滚。
 */
public final class AgentLoop {
    private static final Set<String> READONLY = Set.of("glob", "grep", "read_file");
    private static final int MAX_IDENTICAL_FAILURES = 3;

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

    /** 请求取消当前任务：打断模型等待和正在跑的 shell，并不再执行下一轮步/工具。 */
    public void cancel() {
        cancelled.set(true);
        try {
            chatClient.cancel();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        for (AgentTool tool : registry.all()) {
            try {
                tool.cancel();
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }
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
        boolean mutatingRun = policy.mode() == PermissionMode.AGENT && !policy.dryRun();
        if (mutatingRun) {
            checkpoint = checkpoints.create();
            state.setCheckpoint(checkpoint);
        }
        String lastFailureKey = "";
        int identicalFailures = 0;
        try {
            List<ToolSpecification> specs = new ArrayList<>();
            for (AgentTool tool : registry.all()) {
                specs.add(tool.specification());
            }
            for (int step = 0; step < limits.maxSteps(); step++) {
                if (cancelled.get()) {
                    return AgentOutcome.cancelled("已取消。", checkpoint);
                }
                List<ChatTurn> packed = packer.pack(state.turns());
                LlmResponse response;
                try {
                    response = chatClient.chat(packed, specs);
                } catch (CancellationException e) {
                    return AgentOutcome.cancelled("已取消。", checkpoint);
                }
                observer.onUsage(response.inputTokens(), response.outputTokens());
                if (!response.hasToolCalls()) {
                    state.addAssistant(response.text(), List.of());
                    if (checkpoint != null) {
                        checkpoints.delete(checkpoint);
                    }
                    return AgentOutcome.completed(response.text());
                }
                state.addAssistant(response.text(), response.toolCalls());
                List<CallResult> results = executeStep(response.toolCalls());
                for (CallResult item : results) {
                    if (item.cancelled()) {
                        return AgentOutcome.cancelled("已取消。", checkpoint);
                    }
                    ToolResult result = item.result();
                    LlmToolCall call = item.call();
                    String payload = result.toJson();
                    String status = result.isDryRun() ? "DRY_RUN" : (result.success() ? "ok" : result.error());
                    observer.onTool(call.name(), status, payload.length());
                    state.add(ChatTurn.tool(call.id(), call.name(), payload));
                    if (!result.success() && !result.isDryRun()) {
                        String key = call.name() + "\n" + call.arguments() + "\n" + result.error();
                        if (key.equals(lastFailureKey)) {
                            identicalFailures++;
                        } else {
                            lastFailureKey = key;
                            identicalFailures = 1;
                        }
                        if (identicalFailures >= MAX_IDENTICAL_FAILURES) {
                            HarnessLog.warn("同一工具连续失败 " + identicalFailures + " 次: " + call.name());
                            return AgentOutcome.failed(
                                    "同一工具连续失败 " + identicalFailures + " 次，已停止: " + call.name(),
                                    checkpoint);
                        }
                    } else {
                        lastFailureKey = "";
                        identicalFailures = 0;
                    }
                }
            }
            return AgentOutcome.maxSteps("达到步数上限，已停止。", checkpoint);
        } catch (CancellationException e) {
            return AgentOutcome.cancelled("已取消。", checkpoint);
        } catch (Exception e) {
            if (cancelled.get()) {
                return AgentOutcome.cancelled("已取消。", checkpoint);
            }
            HarnessLog.error("Agent 循环失败", e);
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

    /**
     * 执行本轮全部工具调用。权限裁决保持顺序（批准框不能并行）；
     * 连续的只读 ALLOW 调用用虚拟线程并行执行。
     */
    private List<CallResult> executeStep(List<LlmToolCall> calls) {
        List<CallResult> out = new ArrayList<>();
        List<Prepared> batch = new ArrayList<>();
        for (LlmToolCall call : calls) {
            if (cancelled.get()) {
                out.add(CallResult.cancelled(call));
                return out;
            }
            Prepared prepared = prepare(call);
            if (prepared.immediate != null) {
                flushReadonly(batch, out);
                out.add(new CallResult(call, prepared.immediate, false));
            } else {
                batch.add(prepared);
            }
        }
        flushReadonly(batch, out);
        return out;
    }

    private Prepared prepare(LlmToolCall call) {
        AgentTool tool = registry.get(call.name());
        if (tool == null) {
            return Prepared.done(ToolResult.error("UNKNOWN_TOOL", "未知工具: " + call.name()));
        }
        PermissionDecision decision = policy.evaluate(tool, call.arguments());
        ToolResult immediate = switch (decision) {
            case HARD_DENY -> ToolResult.error("HARD_DENY", "命令被硬性拒绝: " + call.arguments());
            case DENY_ASK_MODE -> ToolResult.error("PERMISSION_DENIED", "Ask 模式不能执行 " + tool.name());
            case DENIED_BY_USER -> ToolResult.error("USER_DENIED", "用户拒绝执行 " + tool.name());
            case DRY_RUN -> ToolResult.dryRun("Would execute " + tool.name() + " " + call.arguments());
            case ALLOW -> null;
        };
        if (immediate != null) {
            return Prepared.done(clip(immediate));
        }
        if (!READONLY.contains(tool.name())) {
            return Prepared.done(clip(executeSafely(tool, call.arguments())));
        }
        return Prepared.pending(call, tool);
    }

    private void flushReadonly(List<Prepared> batch, List<CallResult> out) {
        if (batch.isEmpty()) {
            return;
        }
        if (batch.size() == 1) {
            Prepared one = batch.removeFirst();
            out.add(new CallResult(one.call, clip(executeSafely(one.tool, one.call.arguments())), false));
            return;
        }
        List<Prepared> work = List.copyOf(batch);
        batch.clear();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResult>> futures = new ArrayList<>();
            for (Prepared item : work) {
                Callable<ToolResult> task = () -> clip(executeSafely(item.tool, item.call.arguments()));
                futures.add(pool.submit(task));
            }
            for (int i = 0; i < work.size(); i++) {
                if (cancelled.get()) {
                    out.add(CallResult.cancelled(work.get(i).call));
                    continue;
                }
                try {
                    out.add(new CallResult(work.get(i).call, futures.get(i).get(), false));
                } catch (Exception e) {
                    if (cancelled.get()) {
                        out.add(CallResult.cancelled(work.get(i).call));
                    } else {
                        out.add(new CallResult(work.get(i).call, ToolResult.error("TOOL_ERROR", e.getMessage()), false));
                    }
                }
            }
        }
    }

    /**
     * 执行单次工具调用：查注册表 → 权限策略裁决 → 按裁决允许、预览或拒绝。
     * 包可见，方便单测直接覆盖权限分支，而不必走完整模型循环。
     */
    ToolResult executeOne(LlmToolCall call) {
        Prepared prepared = prepare(call);
        if (prepared.immediate != null) {
            return prepared.immediate;
        }
        AgentTool tool = prepared.tool != null ? prepared.tool : registry.get(call.name());
        if (tool == null) {
            return ToolResult.error("UNKNOWN_TOOL", "未知工具: " + call.name());
        }
        return clip(executeSafely(tool, call.arguments()));
    }

    private ToolResult clip(ToolResult result) {
        int maxChars = Math.max(256, limits.maxToolResultTokens() * 2);
        return result.clipped(maxChars);
    }

    /** 调用具体工具；任何异常都转成 TOOL_ERROR，避免把未捕获异常冲出主循环。 */
    private static ToolResult executeSafely(AgentTool tool, String arguments) {
        try {
            return tool.execute(arguments);
        } catch (Exception e) {
            return ToolResult.error("TOOL_ERROR", e.getMessage());
        }
    }

    private record Prepared(LlmToolCall call, AgentTool tool, ToolResult immediate) {
        static Prepared done(ToolResult result) {
            return new Prepared(null, null, result);
        }

        static Prepared pending(LlmToolCall call, AgentTool tool) {
            return new Prepared(call, tool, null);
        }
    }

    private record CallResult(LlmToolCall call, ToolResult result, boolean cancelled) {
        static CallResult cancelled(LlmToolCall call) {
            return new CallResult(call, ToolResult.error("CANCELLED", "已取消"), true);
        }
    }
}
