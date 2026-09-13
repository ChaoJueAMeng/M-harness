package com.mharness.agent;

import com.mharness.checkpoint.Checkpoint;

/**
 * 一次 {@link AgentLoop#run} 的结局。
 * 成功完成时 checkpoint 为 null（快照已删除）；步数上限、失败、取消时可能仍带着可回滚的快照。
 */
public record AgentOutcome(
        Status status,
        String text,
        Checkpoint checkpoint,
        String error
) {
    /** 任务结束状态。 */
    public enum Status {
        /** 模型给出最终文本，未再调工具。 */
        COMPLETED,
        /** 达到 {@link AgentLimits#maxSteps()} 仍未结束。 */
        MAX_STEPS,
        /** 循环抛出未捕获异常（通常已尝试回滚）。 */
        FAILED,
        /** 用户或服务端取消。 */
        CANCELLED
    }

    /** 正常完成，text 为模型最终回复。 */
    public static AgentOutcome completed(String text) {
        return new AgentOutcome(Status.COMPLETED, text, null, null);
    }

    /** 步数用尽；checkpoint 仍保留，方便用户手动 rollback。 */
    public static AgentOutcome maxSteps(String text, Checkpoint checkpoint) {
        return new AgentOutcome(Status.MAX_STEPS, text, checkpoint, null);
    }

    /** 运行失败；error 为异常信息。 */
    public static AgentOutcome failed(String error, Checkpoint checkpoint) {
        return new AgentOutcome(Status.FAILED, null, checkpoint, error);
    }

    /** 任务被取消，尚未完成。 */
    public static AgentOutcome cancelled(String text, Checkpoint checkpoint) {
        return new AgentOutcome(Status.CANCELLED, text, checkpoint, null);
    }
}
