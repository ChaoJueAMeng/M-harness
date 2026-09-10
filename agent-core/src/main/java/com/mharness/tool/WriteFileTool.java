package com.mharness.tool;

import com.mharness.workspace.WorkspaceGuard;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements AgentTool {
    private final WorkspaceGuard guard;

    public WriteFileTool(WorkspaceGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("仅用于新建文件。若文件已存在则失败，请改用 search_replace。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path", "相对工作区的新文件路径")
                        .addStringProperty("content", "文件内容")
                        .required("path", "content")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(String arguments) throws Exception {
        var args = JsonArgs.parse(arguments);
        String userPath = JsonArgs.requiredText(args, "path");
        String content = args.has("content") ? args.get("content").asText() : "";
        Path file = guard.resolveForCreate(userPath);
        if (Files.exists(file)) {
            return ToolResult.error("ALREADY_EXISTS", "文件已存在，请用 search_replace: " + userPath);
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return ToolResult.ok("已创建: " + guard.relativize(file));
    }
}
