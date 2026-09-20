package com.mharness.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleChatClientTest {
    @Test
    void toMessagesKeepsAssistantToolCallsAndResults() {
        List<ChatTurn> turns = List.of(
                ChatTurn.system("sys"),
                ChatTurn.user("hi"),
                ChatTurn.assistant("", List.of(new LlmToolCall("1", "read_file", "{\"path\":\"a\"}"))),
                ChatTurn.tool("1", "read_file", "ok")
        );
        var messages = OpenAiCompatibleChatClient.toMessages(turns);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(2)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) messages.get(2)).hasToolExecutionRequests()).isTrue();
        assertThat(messages.get(3)).isInstanceOf(ToolExecutionResultMessage.class);
    }

    @Test
    void retryableDetectsRateLimitAndServerErrors() {
        assertThat(OpenAiCompatibleChatClient.retryable(new IllegalStateException("HTTP 429 rate limit"))).isTrue();
        assertThat(OpenAiCompatibleChatClient.retryable(new IllegalStateException("502 Bad Gateway"))).isTrue();
        assertThat(OpenAiCompatibleChatClient.retryable(new IllegalStateException("invalid api key"))).isFalse();
    }
}
