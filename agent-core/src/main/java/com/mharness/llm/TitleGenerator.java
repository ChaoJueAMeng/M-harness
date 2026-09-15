package com.mharness.llm;

import java.util.List;

/**
 * 根据用户第一条消息生成侧栏标题。不带工具、不走 Agent 循环。
 */
public final class TitleGenerator {
    public static final int MAX_TITLE_CHARS = 20;
    static final int MAX_PROMPT_CHARS = 1000;
    static final String SYSTEM_PROMPT = """
            你为用户的新对话生成侧栏标题。
            只根据用户第一条消息概括主题。
            只输出标题本身：不要引号、解释或换行。
            不超过 20 个字，使用用户消息的主要语言。
            """;

    private final ChatClient chat;

    public TitleGenerator(ChatClient chat) {
        this.chat = chat;
    }

    /**
     * 调用模型生成标题。prompt 为空时不请求模型，返回空串；
     * 模型输出为空或无法清洗时也返回空串，由调用方保留本地占位标题。
     */
    public String generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        String clipped = clip(prompt, MAX_PROMPT_CHARS);
        LlmResponse response = chat.chat(
                List.of(
                        ChatTurn.system(SYSTEM_PROMPT),
                        ChatTurn.user("为下面这条消息生成标题：\n" + clipped)
                ),
                List.of()
        );
        return sanitize(response == null ? null : response.text());
    }

    /** 取首行、去掉包裹引号、折叠空白，再截到 {@link #MAX_TITLE_CHARS}。 */
    static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String title = firstLine(raw.strip());
        title = unwrapQuotes(title);
        title = title.replaceAll("\\s+", " ").strip();
        if (title.length() > MAX_TITLE_CHARS) {
            title = title.substring(0, MAX_TITLE_CHARS).strip();
        }
        return title;
    }

    private static String firstLine(String text) {
        int newline = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                newline = i;
                break;
            }
        }
        if (newline < 0) {
            return text;
        }
        return text.substring(0, newline).strip();
    }

    private static String unwrapQuotes(String title) {
        String current = title;
        while (current.length() >= 2) {
            char start = current.charAt(0);
            char end = current.charAt(current.length() - 1);
            if (!isWrappingPair(start, end)) {
                break;
            }
            current = current.substring(1, current.length() - 1).strip();
        }
        return current;
    }

    private static boolean isWrappingPair(char start, char end) {
        return (start == '"' && end == '"')
                || (start == '\'' && end == '\'')
                || (start == '“' && end == '”')
                || (start == '‘' && end == '’')
                || (start == '「' && end == '」')
                || (start == '『' && end == '』')
                || (start == '《' && end == '》');
    }

    private static String clip(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }
}
