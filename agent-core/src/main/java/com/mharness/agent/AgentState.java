package com.mharness.agent;

import com.mharness.checkpoint.Checkpoint;
import com.mharness.llm.ChatTurn;
import com.mharness.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 Agent 运行的可变会话状态：对话轮次，以及（若已拍快照）对应的 checkpoint。
 */
public final class AgentState {
    private final List<ChatTurn> turns = new ArrayList<>();
    private Checkpoint checkpoint;

    /** 追加一轮对话（用户 / 助手 / 工具结果）。 */
    public void add(ChatTurn turn) {
        turns.add(turn);
    }

    /** 当前全部轮次，供 ContextPacker 打包后发给模型。返回内部列表，调用方不要长期持有再改。 */
    public List<ChatTurn> turns() {
        return turns;
    }

    /** 本轮任务开始时创建的 checkpoint；Ask / dry-run 时为 null。 */
    public Checkpoint checkpoint() {
        return checkpoint;
    }

    /** 记录本轮任务的回滚点。 */
    public void setCheckpoint(Checkpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    /** 从后往前找最近一条助手消息；没有则返回 null。 */
    public ChatTurn lastAssistant() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role() == ChatTurn.Role.ASSISTANT) {
                return turns.get(i);
            }
        }
        return null;
    }

    /** 追加一条助手轮次，可携带本轮要执行的工具调用。 */
    public void addAssistant(String text, List<LlmToolCall> toolCalls) {
        turns.add(ChatTurn.assistant(text, toolCalls));
    }
}
