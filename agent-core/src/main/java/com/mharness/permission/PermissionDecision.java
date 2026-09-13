package com.mharness.permission;

/**
 * {@link PermissionPolicy#evaluate} 对一次工具调用的裁决结果。
 */
public enum PermissionDecision {
    /** 允许真正执行工具。 */
    ALLOW,
    /** 不执行，由循环返回 dry-run 预览。 */
    DRY_RUN,
    /** Ask 模式禁止变更类工具。 */
    DENY_ASK_MODE,
    /** 命中硬拒绝名单，即使用户 --yes 也不执行。 */
    HARD_DENY,
    /** 用户在确认框里拒绝，或确认超时。 */
    DENIED_BY_USER
}
