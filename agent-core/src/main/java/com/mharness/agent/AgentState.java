package com.mharness.agent;

import com.mharness.checkpoint.Checkpoint;
import com.mharness.llm.ChatTurn;
import com.mharness.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.List;

public final class AgentState {
    private final List<ChatTurn> turns = new ArrayList<>();
    private Checkpoint checkpoint;

    public void add(ChatTurn turn) {
        turns.add(turn);
    }

    public List<ChatTurn> turns() {
        return turns;
    }

    public Checkpoint checkpoint() {
        return checkpoint;
    }

    public void setCheckpoint(Checkpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    public ChatTurn lastAssistant() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role() == ChatTurn.Role.ASSISTANT) {
                return turns.get(i);
            }
        }
        return null;
    }

    public void addAssistant(String text, List<LlmToolCall> toolCalls) {
        turns.add(ChatTurn.assistant(text, toolCalls));
    }
}
