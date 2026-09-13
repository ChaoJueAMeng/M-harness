package com.mharness.checkpoint;

/**
 * checkpoint 创建、读取、回滚失败时抛出的非受检异常。
 */
public final class CheckpointException extends RuntimeException {
    public CheckpointException(String message) {
        super(message);
    }

    public CheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
