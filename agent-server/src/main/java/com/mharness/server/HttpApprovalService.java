package com.mharness.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mharness.permission.ApprovalService;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 通过 SSE 把批准请求推给桌面端，再阻塞等待 {@link #complete} 的结果。
 * 5 分钟超时或异常视为拒绝，避免 Agent 线程永久卡住。
 */
final class HttpApprovalService implements ApprovalService {
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final EventLog events;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    HttpApprovalService(EventLog events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    /**
     * 发出 {@code approval} 事件并等待 UI 回 POST /approval。
     * 在 Agent 线程上调用，会阻塞到用户点批准/拒绝、取消或超时。
     */
    @Override
    public boolean approve(String toolName, String arguments) {
        String requestId = java.util.UUID.randomUUID().toString();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "approval");
            node.put("requestId", requestId);
            node.put("toolName", toolName == null ? "" : toolName);
            node.put("arguments", arguments == null ? "" : arguments);
            events.emitJson(mapper.writeValueAsString(node));
            return Boolean.TRUE.equals(future
                    .orTimeout(TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                    .exceptionally(error -> false)
                    .join());
        } catch (Exception e) {
            return false;
        } finally {
            pending.remove(requestId);
        }
    }

    /**
     * 桌面端提交批准结果。未知 requestId 返回 false。
     */
    boolean complete(String requestId, boolean approved) {
        CompletableFuture<Boolean> future = pending.get(requestId);
        if (future == null) {
            return false;
        }
        return future.complete(approved);
    }

    /** 取消任务时把所有挂起的确认都完成成拒绝。 */
    void denyAll() {
        for (CompletableFuture<Boolean> future : pending.values()) {
            future.complete(false);
        }
        pending.clear();
    }
}
