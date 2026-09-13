package com.mharness.permission;

/**
 * 固定返回批准或拒绝，不询问用户。
 * CLI {@code --yes} 和 HTTP {@code autoApprove=true} 使用 {@code new AutoApprovalService(true)}。
 */
public final class AutoApprovalService implements ApprovalService {
    private final boolean approve;

    /** @param approve 每次 {@link #approve} 都返回该值 */
    public AutoApprovalService(boolean approve) {
        this.approve = approve;
    }

    @Override
    public boolean approve(String toolName, String arguments) {
        return approve;
    }
}
