package com.mharness.tool;

import com.mharness.workspace.WorkspaceGuard;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取工作区内普通文件，返回带行号的文本。
 * 支持从某行开始、限制行数；总输出超过约 32KB 会截断。
 */
public final class ReadFileTool implements AgentTool {
    static final int DEFAULT_MAX_CHARS = 32_000;
    private final WorkspaceGuard guard;

    public ReadFileTool(WorkspaceGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("读取工作区内文件，返回带行号的内容。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path", "相对工作区的文件路径")
                        .addIntegerProperty("offset", "可选，从该行开始（从 1 计）")
                        .addIntegerProperty("limit", "可选，最多读取的行数")
                        .required("path")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(String arguments) throws Exception {
        var args = JsonArgs.parse(arguments);
        String userPath = JsonArgs.requiredText(args, "path");
        int offset = JsonArgs.optionalInt(args, "offset", 1);
        int limit = JsonArgs.optionalInt(args, "limit", Integer.MAX_VALUE);
        // 解析并确认路径仍在工作区内（含 symlink realpath）。
        Path file = guard.resolveExisting(userPath);
        if (!Files.isRegularFile(file)) {
            return ToolResult.error("NOT_FOUND", "不是普通文件: " + userPath);
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        String[] lines = raw.split("\n", -1);
        int start = Math.max(1, offset);
        int end = Math.min(lines.length, start - 1 + Math.max(limit, 0));
        StringBuilder numbered = new StringBuilder();
        int chars = 0;
        boolean truncated = false;
        for (int i = start; i <= end; i++) {
            String line = String.format("%6d|%s%n", i, lines[i - 1]);
            if (chars + line.length() > DEFAULT_MAX_CHARS) {
                truncated = true;
                break;
            }
            numbered.append(line);
            chars += line.length();
        }
        if (truncated) {
            numbered.append("... truncated ...\n");
        }
        return ToolResult.ok(numbered.toString());
    }
}
