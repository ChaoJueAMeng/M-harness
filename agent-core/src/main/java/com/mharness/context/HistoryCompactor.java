package com.mharness.context;

import com.mharness.llm.ChatTurn;

import java.util.ArrayList;
import java.util.List;

/**
 * 把跨 run 的会话历史压成发给模型的 prior：丢掉工具/系统轮、截断超长正文、
 * 钉住首条用户消息并用滑动窗口丢掉中间轮次。磁盘上的全文不经过这里。
 */
public final class HistoryCompactor {
    public static final int DEFAULT_MAX_PRIOR_MESSAGES = 16;
    public static final int DEFAULT_MAX_TURN_CHARS = 4000;
    static final String TRUNCATION_MARK = "\n... truncated ...";

    private HistoryCompactor() {
    }

    /** 使用默认窗口（16 条）和单条 4000 字符上限压缩 prior。 */
    public static List<ChatTurn> compact(List<ChatTurn> prior) {
        return compact(prior, DEFAULT_MAX_PRIOR_MESSAGES, DEFAULT_MAX_TURN_CHARS);
    }

    /**
     * 压缩 prior。{@code null} 视为空列表。只保留 USER/ASSISTANT 且 content 非空的轮次。
     * {@code maxPriorMessages} / {@code maxTurnChars} 小于 1 时按 1 处理。
     */
    public static List<ChatTurn> compact(List<ChatTurn> prior, int maxPriorMessages, int maxTurnChars) {
        int maxMessages = Math.max(1, maxPriorMessages);
        int maxChars = Math.max(1, maxTurnChars);
        if (prior == null || prior.isEmpty()) {
            return List.of();
        }
        List<ChatTurn> filtered = new ArrayList<>();
        for (ChatTurn turn : prior) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if (turn.role() != ChatTurn.Role.USER && turn.role() != ChatTurn.Role.ASSISTANT) {
                continue;
            }
            filtered.add(truncate(turn, maxChars));
        }
        if (filtered.size() <= maxMessages) {
            return List.copyOf(filtered);
        }
        return List.copyOf(window(filtered, maxMessages));
    }

    /**
     * 钉住第一条 USER（若存在），再从尾部取满窗口；切点落在 ASSISTANT 上时向后挪到 USER，
     * 避免窗口以半截助手回复开头。若跳过会清空尾部，则保留原切点。
     */
    private static List<ChatTurn> window(List<ChatTurn> filtered, int maxMessages) {
        boolean pinFirst = filtered.getFirst().role() == ChatTurn.Role.USER;
        int tailBudget = pinFirst ? maxMessages - 1 : maxMessages;
        int tailStart = filtered.size() - tailBudget;
        if (pinFirst) {
            tailStart = Math.max(tailStart, 1);
        } else {
            tailStart = Math.max(tailStart, 0);
        }
        int cut = skipLeadingAssistant(filtered, tailStart);
        List<ChatTurn> kept = new ArrayList<>();
        if (pinFirst) {
            kept.add(filtered.getFirst());
            cut = Math.max(cut, 1);
        }
        for (int i = cut; i < filtered.size(); i++) {
            kept.add(filtered.get(i));
        }
        return kept;
    }

    private static int skipLeadingAssistant(List<ChatTurn> filtered, int tailStart) {
        int cut = tailStart;
        while (cut < filtered.size() && filtered.get(cut).role() == ChatTurn.Role.ASSISTANT) {
            cut++;
        }
        if (cut >= filtered.size()) {
            return tailStart;
        }
        return cut;
    }

    private static ChatTurn truncate(ChatTurn turn, int maxChars) {
        String content = turn.content();
        if (content.length() <= maxChars) {
            return copyRole(turn, content);
        }
        return copyRole(turn, content.substring(0, maxChars) + TRUNCATION_MARK);
    }

    private static ChatTurn copyRole(ChatTurn turn, String content) {
        if (turn.role() == ChatTurn.Role.USER) {
            return ChatTurn.user(content);
        }
        return ChatTurn.assistant(content, List.of());
    }
}
