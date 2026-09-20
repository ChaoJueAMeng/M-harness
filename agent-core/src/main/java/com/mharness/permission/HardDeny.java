package com.mharness.permission;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shell 命令的硬拒绝名单：force push、hard reset、删根、格式化磁盘、关机等破坏性操作一律禁止，无法被 --yes 覆盖。
 * 这只是兜底，不能替代 Ask 模式和批准框。
 */
public final class HardDeny {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("git\\s+push\\b.*(--force\\b|\\s-f\\b)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+reset\\s+--hard\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+clean\\s+-[a-zA-Z]*f[a-zA-Z]*d\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+clean\\s+-[a-zA-Z]*d[a-zA-Z]*f\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+checkout\\s+--\\s+\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+restore\\s+--source\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+branch\\s+(-D\\b|--delete\\s+--force\\b)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?/(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brd\\s+/s\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brmdir\\s+/s\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdel\\s+/[a-z]*f[a-z]*\\s+/[a-z]*s\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bdel\\s+/[a-z]*s[a-z]*\\s+/[a-z]*f\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("remove-item\\b.*-recurse\\b.*-force\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("remove-item\\b.*-force\\b.*-recurse\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bformat\\s+[a-z]:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bformat\\.com\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bshutdown\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\breboot\\b", Pattern.CASE_INSENSITIVE)
    );

    private HardDeny() {
    }

    /** 命令（已规范化空白）是否命中硬拒绝正则。空命令视为不匹配。 */
    public static boolean matches(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }
}
