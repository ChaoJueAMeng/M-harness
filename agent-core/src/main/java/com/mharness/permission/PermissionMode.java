package com.mharness.permission;

/**
 * Agent 运行模式。
 */
public enum PermissionMode {
    /** 只读：可以搜索和读文件，不能写文件或跑 Shell。 */
    ASK,
    /** 可变更：写文件和 Shell 默认需确认，可用 --yes / autoApprove 跳过。 */
    AGENT
}
