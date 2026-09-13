package com.mharness.workspace;

/**
 * 用户/模型给出的路径经过规范化或 realpath 后不再位于工作区内时抛出。
 */
public final class PathEscapeException extends RuntimeException {
    public PathEscapeException(String message) {
        super(message);
    }
}
