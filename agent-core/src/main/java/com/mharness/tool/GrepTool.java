package com.mharness.tool;

import com.mharness.workspace.WorkspaceGuard;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 在工作区内精确搜索文本。优先调用 ripgrep（{@code rg}），机器上没有 rg 或调用失败时退回 Java 遍历。
 * 最多返回 50 条，单行截到 200 字符。
 */
public final class GrepTool implements AgentTool {
    private static final int MAX_HITS = 50;
    private static final int CONTEXT_CHARS = 200;
    private final WorkspaceGuard guard;

    public GrepTool(WorkspaceGuard guard) {
        this.guard = guard;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public ToolSpecification specification() {
        return ToolSpecification.builder()
                .name(name())
                .description("在工作区内精确搜索文本，优先使用 ripgrep。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("query", "要搜索的文本")
                        .addStringProperty("glob", "可选文件 glob，例如 *.java")
                        .required("query")
                        .build())
                .build();
    }

    @Override
    public ToolResult execute(String arguments) throws Exception {
        var args = JsonArgs.parse(arguments);
        String query = JsonArgs.requiredText(args, "query");
        String glob = JsonArgs.optionalText(args, "glob", "");
        List<String> lines = tryRipgrep(query, glob);
        if (lines == null) {
            // rg 不存在、超时或非 0/1 退出码时，用纯 Java 扫文件。
            lines = javaSearch(query, glob);
        }
        if (lines.isEmpty()) {
            return ToolResult.ok("无匹配");
        }
        String suffix = lines.size() >= MAX_HITS ? "\n(truncated at " + MAX_HITS + ")" : "";
        return ToolResult.ok(String.join("\n", lines) + suffix);
    }

    /**
     * 固定字面量搜索（{@code -F}），工作目录锁在仓库根。
     * 返回 null 表示需要走 Java 回退；空列表表示搜过但没有命中。
     */
    private List<String> tryRipgrep(String query, String glob) {
        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--line-number");
        command.add("--no-heading");
        command.add("--color");
        command.add("never");
        command.add("--max-count");
        command.add(String.valueOf(MAX_HITS));
        command.add("-F");
        if (!glob.isBlank()) {
            command.add("--glob");
            command.add(glob);
        }
        command.add(query);
        command.add(".");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(guard.workspace().toFile());
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < MAX_HITS) {
                    lines.add(truncate(line));
                }
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() == 0 || process.exitValue() == 1) {
                return lines;
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 跳过构建产物和大文件/符号链接，按行 {@code contains} 做精确匹配。 */
    private List<String> javaSearch(String query, String glob) throws IOException {
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
                if (hits.size() >= MAX_HITS) {
                    return FileVisitResult.TERMINATE;
                }
                if (Files.isSymbolicLink(file) || attrs.size() > 1_000_000) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = guard.relativize(file);
                if (!glob.isBlank() && !matchesGlob(file.getFileName().toString(), glob) && !matchesGlob(relative, glob)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    List<String> fileLines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < fileLines.size() && hits.size() < MAX_HITS; i++) {
                        if (fileLines.get(i).contains(query)) {
                            hits.add(relative + ":" + (i + 1) + ":" + truncate(fileLines.get(i)));
                        }
                    }
                } catch (IOException ignored) {
                    return FileVisitResult.CONTINUE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return hits;
    }

    /** 把简单 glob（{@code *} / {@code **}）转成正则再匹配文件名或相对路径。 */
    private static boolean matchesGlob(String name, String glob) {
        String regex = glob.replace(".", "\\.").replace("**", "§§").replace("*", ".*").replace("§§", ".*");
        return name.replace('\\', '/').matches(regex);
    }

    private static String truncate(String line) {
        if (line.length() <= CONTEXT_CHARS) {
            return line;
        }
        return line.substring(0, CONTEXT_CHARS) + "...";
    }
}
