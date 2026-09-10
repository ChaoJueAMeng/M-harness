package com.mharness.agent;

import com.mharness.checkpoint.Checkpoint;

public record AgentOutcome(
        Status status,
        String text,
        Checkpoint checkpoint,
        String error
) {
    public enum Status {
        COMPLETED,
        MAX_STEPS,
        FAILED
    }

    public static AgentOutcome completed(String text) {
        return new AgentOutcome(Status.COMPLETED, text, null, null);
    }

    public static AgentOutcome maxSteps(String text, Checkpoint checkpoint) {
        return new AgentOutcome(Status.MAX_STEPS, text, checkpoint, null);
    }

    public static AgentOutcome failed(String error, Checkpoint checkpoint) {
        return new AgentOutcome(Status.FAILED, null, checkpoint, error);
    }
}
