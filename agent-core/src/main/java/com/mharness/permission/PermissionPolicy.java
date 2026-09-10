package com.mharness.permission;

import com.mharness.tool.AgentTool;

import java.util.Set;

public final class PermissionPolicy {
    private static final Set<String> MUTATING = Set.of("search_replace", "write_file", "run_terminal");

    private final PermissionMode mode;
    private final boolean dryRun;
    private final ApprovalService approvalService;
    private final boolean autoApprove;

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

    private static String extractCommand(String arguments) {
        if (arguments == null) {
            return "";
        }
        int idx = arguments.indexOf("\"command\"");
        if (idx < 0) {
            return arguments;
        }
        int colon = arguments.indexOf(':', idx);
        int firstQuote = arguments.indexOf('"', colon + 1);
        int secondQuote = arguments.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return arguments;
        }
        return arguments.substring(firstQuote + 1, secondQuote);
    }
}
