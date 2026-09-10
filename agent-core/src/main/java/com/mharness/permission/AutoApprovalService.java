package com.mharness.permission;

public final class AutoApprovalService implements ApprovalService {
    private final boolean approve;

    public AutoApprovalService(boolean approve) {
        this.approve = approve;
    }

    @Override
    public boolean approve(String toolName, String arguments) {
        return approve;
    }
}
