package com.mharness.context;

import com.mharness.llm.ChatTurn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryCompactorTest {
    @Test
    void nullAndEmptyYieldEmpty() {
        assertThat(HistoryCompactor.compact(null)).isEmpty();
        assertThat(HistoryCompactor.compact(List.of())).isEmpty();
    }

    @Test
    void dropsSystemToolAndBlankTurns() {
        List<ChatTurn> prior = List.of(
                ChatTurn.system("rules"),
                ChatTurn.user("  "),
                ChatTurn.user("keep me"),
                ChatTurn.tool("1", "glob", "{\"files\":[]}"),
                ChatTurn.assistant("ok", List.of())
        );
        List<ChatTurn> compacted = HistoryCompactor.compact(prior);
        assertThat(compacted).extracting(ChatTurn::content).containsExactly("keep me", "ok");
        assertThat(compacted).extracting(ChatTurn::role)
                .containsExactly(ChatTurn.Role.USER, ChatTurn.Role.ASSISTANT);
    }

    @Test
    void truncatesLongTurns() {
        String longText = "x".repeat(20);
        List<ChatTurn> compacted = HistoryCompactor.compact(
                List.of(ChatTurn.user(longText), ChatTurn.assistant(longText, List.of())),
                16,
                8
        );
        assertThat(compacted).hasSize(2);
        assertThat(compacted.get(0).content()).startsWith("xxxxxxxx");
        assertThat(compacted.get(0).content()).endsWith(HistoryCompactor.TRUNCATION_MARK);
        assertThat(compacted.get(0).content()).hasSize(8 + HistoryCompactor.TRUNCATION_MARK.length());
        assertThat(compacted.get(1).content()).endsWith(HistoryCompactor.TRUNCATION_MARK);
    }

    @Test
    void pinsFirstUserAndDropsMiddleTurns() {
        List<ChatTurn> prior = new ArrayList<>();
        prior.add(ChatTurn.user("original-task"));
        prior.add(ChatTurn.assistant("ack-0", List.of()));
        for (int i = 1; i <= 6; i++) {
            prior.add(ChatTurn.user("mid-" + i));
            prior.add(ChatTurn.assistant("ans-" + i, List.of()));
        }
        List<ChatTurn> compacted = HistoryCompactor.compact(prior, 5, 4000);
        assertThat(compacted).extracting(ChatTurn::content)
                .containsExactly("original-task", "mid-5", "ans-5", "mid-6", "ans-6");
        assertThat(compacted).extracting(ChatTurn::content)
                .doesNotContain("mid-1", "ans-1", "mid-2", "ack-0");
    }

    @Test
    void cutMovesForwardToAvoidLeadingAssistant() {
        List<ChatTurn> prior = List.of(
                ChatTurn.user("first"),
                ChatTurn.assistant("a0", List.of()),
                ChatTurn.user("u1"),
                ChatTurn.assistant("a1", List.of()),
                ChatTurn.user("u2"),
                ChatTurn.assistant("a2", List.of())
        );
        // pin first + tail budget 3 会落在 a1 上，向后挪到 u2。
        List<ChatTurn> compacted = HistoryCompactor.compact(prior, 4, 4000);
        assertThat(compacted).extracting(ChatTurn::content).containsExactly("first", "u2", "a2");
        assertThat(compacted.get(1).role()).isEqualTo(ChatTurn.Role.USER);
    }

    @Test
    void keepsOriginalCutWhenSkippingAssistantsWouldEmptyTail() {
        List<ChatTurn> prior = List.of(
                ChatTurn.user("first"),
                ChatTurn.assistant("only-tail", List.of())
        );
        List<ChatTurn> compacted = HistoryCompactor.compact(prior, 1, 4000);
        assertThat(compacted).extracting(ChatTurn::content).containsExactly("first");
    }
}
