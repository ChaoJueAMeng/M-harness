package com.mharness.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TitleGeneratorTest {
    @Test
    void blankPromptDoesNotCallModel() {
        AtomicInteger calls = new AtomicInteger();
        ChatClient chat = (turns, tools) -> {
            calls.incrementAndGet();
            return new LlmResponse("不应出现", List.of());
        };
        TitleGenerator generator = new TitleGenerator(chat);
        assertThat(generator.generate(null)).isEmpty();
        assertThat(generator.generate("  ")).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void sendsSystemAndUserTurnsWithoutTools() {
        List<List<ChatTurn>> turnsSeen = new ArrayList<>();
        List<List<ToolSpecification>> toolsSeen = new ArrayList<>();
        ChatClient chat = (turns, tools) -> {
            turnsSeen.add(List.copyOf(turns));
            toolsSeen.add(List.copyOf(tools));
            return new LlmResponse("修复登录超时", List.of());
        };
        String title = new TitleGenerator(chat).generate("帮我排查登录接口 504");
        assertThat(title).isEqualTo("修复登录超时");
        assertThat(toolsSeen).containsExactly(List.of());
        assertThat(turnsSeen).hasSize(1);
        assertThat(turnsSeen.get(0)).hasSize(2);
        assertThat(turnsSeen.get(0).get(0).role()).isEqualTo(ChatTurn.Role.SYSTEM);
        assertThat(turnsSeen.get(0).get(0).content()).contains("侧栏标题");
        assertThat(turnsSeen.get(0).get(1).role()).isEqualTo(ChatTurn.Role.USER);
        assertThat(turnsSeen.get(0).get(1).content()).contains("帮我排查登录接口 504");
    }

    @Test
    void clipsLongPromptBeforeCallingModel() {
        ScriptedChatClient chat = new ScriptedChatClient(new LlmResponse("长任务", List.of()));
        String prompt = "x".repeat(TitleGenerator.MAX_PROMPT_CHARS + 80);
        assertThat(new TitleGenerator(chat).generate(prompt)).isEqualTo("长任务");
        assertThat(chat.calls().get(0).get(1).content())
                .isEqualTo("为下面这条消息生成标题：\n" + "x".repeat(TitleGenerator.MAX_PROMPT_CHARS));
    }

    @Test
    void sanitizesQuotesNewlinesAndLength() {
        assertThat(TitleGenerator.sanitize("  「修复 Nacos」  \n第二行")).isEqualTo("修复 Nacos");
        assertThat(TitleGenerator.sanitize("\"'标题'\"")).isEqualTo("标题");
        assertThat(TitleGenerator.sanitize("《部署指南》")).isEqualTo("部署指南");
        assertThat(TitleGenerator.sanitize("这是一段超过二十个字的侧栏标题应当被截断")).hasSize(TitleGenerator.MAX_TITLE_CHARS);
        assertThat(TitleGenerator.sanitize("   ")).isEmpty();
        assertThat(TitleGenerator.sanitize(null)).isEmpty();
    }

    @Test
    void blankModelTextYieldsEmptyTitle() {
        TitleGenerator generator = new TitleGenerator(new ScriptedChatClient(new LlmResponse("  \n  ", List.of())));
        assertThat(generator.generate("写一个排序函数")).isEmpty();
    }
}
