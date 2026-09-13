package com.mharness.tool;

import com.mharness.workspace.WorkspaceGuard;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 在文件中用新字符串替换「恰好出现一次」的旧字符串。
 * 找不到或出现多次都失败；多次时最多返回 5 处行号和片段，让模型改用更独特的 old_string。
 */
public final class SearchReplaceTool implements AgentTool {
    private static final int MAX_MATCHES = 5;
    private static final int SNIPPET = 120;
    private final WorkspaceGuard guard;

    public SearchReplaceTool(WorkspaceGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "search_replace";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("在文件中用新字符串替换唯一出现的旧字符串。旧串必须恰好匹配一次。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path", "相对工作区的文件路径")
                        .addStringProperty("old_string", "必须在文件中唯一出现的原文")
                        .addStringProperty("new_string", "替换后的文本")
                        .required("path", "old_string", "new_string")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(String arguments) throws Exception {
        var args = JsonArgs.parse(arguments);
        String userPath = JsonArgs.requiredText(args, "path");
        String oldString = JsonArgs.requiredText(args, "old_string");
        String newString = args.has("new_string") ? args.get("new_string").asText() : "";
        Path file = guard.resolveExisting(userPath);
        if (!Files.isRegularFile(file)) {
            return ToolResult.error("NOT_FOUND", "不是普通文件: " + userPath);
        }
        String original = Files.readString(file, StandardCharsets.UTF_8);
        List<Integer> offsets = findOffsets(original, oldString);
        if (offsets.isEmpty()) {
            return ToolResult.error("MATCH_NOT_FOUND", "未找到 old_string: " + userPath);
        }
        if (offsets.size() > 1) {
            List<ToolResult.Match> matches = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_MATCHES, offsets.size()); i++) {
                int offsetPos = offsets.get(i);
                int line = lineNumber(original, offsetPos);
                String snippet = snippet(original, offsetPos, oldString.length());
                matches.add(new ToolResult.Match(line, snippet));
            }
            return ToolResult.matches("MATCH_NOT_UNIQUE", matches);
        }
        String updated = original.replace(oldString, newString);
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        return ToolResult.ok("已替换 1 处: " + guard.relativize(file));
    }

    /** 返回 needle 在 haystack 中每一次出现的起始下标（不重叠）。 */
    static List<Integer> findOffsets(String haystack, String needle) {
        List<Integer> offsets = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) {
                return offsets;
            }
            offsets.add(idx);
            from = idx + Math.max(needle.length(), 1);
        }
    }

    /** 把字符偏移换成 1-based 行号。 */
    static int lineNumber(String text, int offset) {
        int line = 1;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** 取出匹配附近一小段文本，换行压成空格，方便模型对照。 */
    private static String snippet(String text, int offset, int length) {
        int start = Math.max(0, offset - 20);
        int end = Math.min(text.length(), offset + length + 20);
        String raw = text.substring(start, end).replace('\n', ' ');
        if (raw.length() > SNIPPET) {
            return raw.substring(0, SNIPPET);
        }
        return raw;
    }
}
