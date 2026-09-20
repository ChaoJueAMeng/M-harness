package com.mharness.context;

/**
 * 粗估发给模型的 token 数。拉丁字母大约 4 字符 1 token，CJK / 全角大约 1 字符 1 token。
 * 只用于裁剪上下文，不替代供应商的真实用量。
 */
public final class TokenEstimator {
    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isDense(cp)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    /** 截到不超过 {@code maxTokens} 的前缀；超长时附上标记。 */
    public static String truncate(String text, int maxTokens) {
        if (text == null || text.isEmpty() || estimate(text) <= maxTokens) {
            return text == null ? "" : text;
        }
        String mark = "\n... truncated tool result ...";
        int budget = Math.max(1, maxTokens - estimate(mark));
        int lo = 0;
        int hi = text.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (estimate(text.substring(0, mid)) <= budget) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if (lo <= 0) {
            return mark.strip();
        }
        return text.substring(0, lo) + mark;
    }

    static boolean isDense(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xFF00 && cp <= 0xFFEF);
    }
}
