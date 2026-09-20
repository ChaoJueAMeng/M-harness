package com.mharness.agent;

/**
 * 观察一次工具执行的结果，供 CLI 打日志、HTTP 服务推 SSE 事件。
 * 不参与控制流，失败也不应抛出异常。
 */
@FunctionalInterface
public interface AgentObserver {
    /** 空实现：不需要回调时使用。 */
    AgentObserver NONE = (toolName, status, chars) -> {
    };

    /**
     * 一条工具调用结束后通知观察者。
     *
     * @param toolName 工具名，如 {@code read_file}
     * @param status   {@code ok} / 错误码 / {@code DRY_RUN}
     * @param chars    回传给模型的 JSON 载荷长度
     */
    void onTool(String toolName, String status, int chars);

    /** 一轮模型调用结束后的用量；供应商没返回时两个参数都可能为 null。 */
    default void onUsage(Integer inputTokens, Integer outputTokens) {
    }
}
