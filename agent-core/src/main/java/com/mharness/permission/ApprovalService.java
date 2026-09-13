package com.mharness.permission;

/**
 * 变更类工具在非 auto-approve 时的人工确认接口。
 * CLI 用控制台，桌面端用 HTTP 把请求推到窗口。
 */
public interface ApprovalService {
    /**
     * 询问是否执行该工具。
     *
     * @return true 批准，false 拒绝或超时
     */
    boolean approve(String toolName, String arguments);
}
