package com.mharness.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.mharness.tool.AgentTool;
import com.mharness.tool.JsonArgs;

import java.io.IOException;
import java.util.Set;

/**
 * 工具调用的权限裁决：只读工具直接放行；写文件/Shell 按模式、dry-run、硬拒绝名单和用户确认决定。
 */
public final class PermissionPolicy {
    /** 会改工作区或执行外部命令的工具；Ask 模式和 dry-run 主要针对它们。 */
    private static final Set<String> MUTATING = Set.of("search_replace", "write_file", "run_terminal");

    private final PermissionMode mode;
    private final boolean dryRun;
    private final ApprovalService approvalService;
    private final boolean autoApprove;

    /**
     * @param mode            ASK 禁止一切变更；AGENT 允许变更（可能仍需确认）
     * @param dryRun          true 时变更工具返回预览而不执行
     * @param autoApprove     true 时跳过 {@link ApprovalService}（CLI {@code --yes}）
     * @param approvalService 需要人工确认时询问
     */
    public PermissionPolicy(
            PermissionMode mode,
            boolean dryRun,
            boolean autoApprove,
            ApprovalService approvalService
    ) {
        this.mode = mode;
        this.dryRun = dryRun;
        this.autoApprove = autoApprove;
        this.approvalService = approvalService;
    }

    public PermissionMode mode() {
        return mode;
    }

    public boolean dryRun() {
        return dryRun;
    }

    /**
     * 按固定顺序裁决一次工具调用：硬拒绝 → 只读放行 → Ask 拒绝 → dry-run → 用户确认 → 允许。
     */
    public PermissionDecision evaluate(AgentTool tool, String arguments) {
        String name = tool.name();
        if ("run_terminal".equals(name) && HardDeny.matches(extractCommand(arguments))) {
            return PermissionDecision.HARD_DENY;
        }
        boolean mutating = MUTATING.contains(name);
        if (!mutating) {
            return PermissionDecision.ALLOW;
        }
        if (mode == PermissionMode.ASK) {
            return PermissionDecision.DENY_ASK_MODE;
        }
        if (dryRun) {
            return PermissionDecision.DRY_RUN;
        }
        if (!autoApprove && !approvalService.approve(name, arguments)) {
            return PermissionDecision.DENIED_BY_USER;
        }
        return PermissionDecision.ALLOW;
    }

    /**
     * 用与 {@code RunTerminalTool} 相同的 JSON 解析取出 {@code command} 字段，供硬拒绝正则匹配。
     * 必须和真正执行时看到的命令一致，否则转义引号（{@code \"}）会让手写截取提前结束而漏拦。
     * 解析失败或字段缺失时退回整段 arguments，宁可多拦不可漏拦。
     */
    static String extractCommand(String arguments) {
        if (arguments == null) {
            return "";
        }
        try {
            JsonNode node = JsonArgs.parse(arguments).get("command");
            if (node == null || node.isNull()) {
                return arguments;
            }
            return node.asText();
        } catch (IOException | RuntimeException e) {
            return arguments;
        }
    }
}
