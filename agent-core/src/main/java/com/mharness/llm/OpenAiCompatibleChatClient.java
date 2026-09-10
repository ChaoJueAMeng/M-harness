package com.mharness.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class OpenAiCompatibleChatClient implements ChatClient {
    private final StreamingChatModel model;
    private final TokenListener listener;

    public OpenAiCompatibleChatClient(String baseUrl, String apiKey, String modelName, TokenListener listener) {
        this.listener = listener == null ? token -> {
        } : listener;
        this.model = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofMinutes(3))
                .build();
    }

    OpenAiCompatibleChatClient(StreamingChatModel model, TokenListener listener) {
        this.model = model;
        this.listener = listener == null ? token -> {
        } : listener;
    }

    @Override
    public LlmResponse chat(List<ChatTurn> turns, List<ToolSpecification> tools) {
        ChatRequest request = ChatRequest.builder()
                .messages(toMessages(turns))
                .toolSpecifications(tools)
                .build();
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                listener.onToken(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                future.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            ChatResponse response = future.join();
            AiMessage ai = response.aiMessage();
            List<LlmToolCall> calls = new ArrayList<>();
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    calls.add(new LlmToolCall(req.id(), req.name(), req.arguments()));
                }
            }
            return new LlmResponse(ai.text(), calls);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("调用模型失败: " + cause.getMessage(), cause);
        }
    }

    static List<ChatMessage> toMessages(List<ChatTurn> turns) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatTurn turn : turns) {
            switch (turn.role()) {
                case SYSTEM -> messages.add(SystemMessage.from(turn.content()));
                case USER -> messages.add(UserMessage.from(turn.content()));
                case ASSISTANT -> {
                    if (turn.toolCalls() != null && !turn.toolCalls().isEmpty()) {
                        List<ToolExecutionRequest> requests = new ArrayList<>();
                        for (LlmToolCall call : turn.toolCalls()) {
                            requests.add(ToolExecutionRequest.builder()
                                    .id(call.id())
                                    .name(call.name())
                                    .arguments(call.arguments())
                                    .build());
                        }
                        messages.add(AiMessage.from(requests));
                    } else {
                        messages.add(AiMessage.from(turn.content() == null ? "" : turn.content()));
                    }
                }
                case TOOL -> messages.add(ToolExecutionResultMessage.from(
                        turn.toolId(),
                        turn.toolName(),
                        turn.content()
                ));
            }
        }
        return messages;
    }
}
