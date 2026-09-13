package com.mharness.tool;

import com.mharness.workspace.WorkspaceGuard;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * 按 glob 模式列出工作区内文件路径，最多 200 条。
 * 跳过 {@code .git}、{@code target}、{@code node_modules}。不含通配符的模式会自动加上 {@literal **}/ 前缀。
 */
public final class GlobTool implements AgentTool {
    private static final int MAX_RESULTS = 200;
    private final WorkspaceGuard guard;

    public GlobTool(WorkspaceGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("按 glob 模式列出工作区内的文件路径。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("pattern", "glob 模式，例如 **/*.java")
                        .required("pattern")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(String arguments) throws Exception {
        String pattern = JsonArgs.requiredText(JsonArgs.parse(arguments), "pattern");
        // 用户常写 "*.java"；补成 **/*.java 才能匹配子目录。
        String glob = pattern.contains("*") || pattern.contains("?") ? pattern : "**/" + pattern;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<String> hits = new ArrayList<>();
        Files.walkFileTree(guard.workspace(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.equals(".git") || name.equals("target") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (hits.size() >= MAX_RESULTS) {
                    return FileVisitResult.TERMINATE;
                }
                Path relative = guard.workspace().relativize(file);
                Path slash = Path.of(relative.toString().replace('\\', '/'));
                if (matcher.matches(relative) || matcher.matches(slash) || matcher.matches(file.getFileName())) {
                    hits.add(relative.toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        String suffix = hits.size() >= MAX_RESULTS ? "\n(truncated at " + MAX_RESULTS + ")" : "";
        return ToolResult.ok(String.join("\n", hits) + suffix);
    }
}
