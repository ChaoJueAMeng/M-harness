package com.mharness.checkpoint;

public final class CheckpointException extends RuntimeException {
    public CheckpointException(String message) {
        super(message);
    }

    public CheckpointException(String message, Throwable cause) {
        super(message, cause);
    }
}
