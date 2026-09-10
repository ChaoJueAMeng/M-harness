package com.mharness.permission;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class HardDeny {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("git\\s+push\\b.*(--force\\b|\\s-f\\b)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+reset\\s+--hard\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+clean\\s+-[a-zA-Z]*f[a-zA-Z]*d\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("git\\s+clean\\s+-[a-zA-Z]*d[a-zA-Z]*f\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?/(\\s|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bformat\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bshutdown\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\breboot\\b", Pattern.CASE_INSENSITIVE)
    );

    private HardDeny() {
    }

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
