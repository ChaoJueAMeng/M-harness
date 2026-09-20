package com.mharness.tool;

import com.mharness.workspace.TextFiles;
import com.mharness.workspace.WorkspaceGuard;
import com.mharness.workspace.WorkspaceIgnore;
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
import java.util.regex.Pattern;

/**
 * 在工作区内精确搜索文本。优先调用 ripgrep（{@code rg}），机器上没有 rg 或调用失败时退回 Java 遍历。
 * 最多返回 50 条，单行截到 200 字符。
 */
public final class GrepTool implements AgentTool {
    private static final int MAX_HITS = 50;
    private static final int CONTEXT_CHARS = 200;
    private final WorkspaceGuard guard;
    private final WorkspaceIgnore ignore;

    public GrepTool(WorkspaceGuard guard) {
        this.guard = guard;
        this.ignore = WorkspaceIgnore.of(guard.workspace());
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
            lines = javaSearch(query, glob);
        }
        if (lines.isEmpty()) {
            return ToolResult.ok("无匹配");
        }
        String suffix = lines.size() >= MAX_HITS ? "\n(truncated at " + MAX_HITS + ")" : "";
        return ToolResult.ok(String.join("\n", lines) + suffix);
    }

    /**
     * 固定字面量搜索（{@code -F -e}），工作目录锁在仓库根。
     * 读满 {@link #MAX_HITS} 后立刻杀掉 rg，避免管道塞满后 20 秒超时再退回全盘扫描。
     * 返回 null 表示需要走 Java 回退；空列表表示搜过但没有命中。
     */
    private List<String> tryRipgrep(String query, String glob) {
        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--line-number");
        command.add("--no-heading");
        command.add("--color");
        command.add("never");
        command.add("-F");
        command.add("-e");
        command.add(query);
        if (!glob.isBlank()) {
            command.add("--glob");
            command.add(glob);
        }
        command.add("--");
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
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            int exit;
            try {
                exit = process.exitValue();
            } catch (IllegalThreadStateException e) {
                return lines.isEmpty() ? null : lines;
            }
            if (exit == 0 || exit == 1 || lines.size() >= MAX_HITS) {
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
                return ignore.skipDirectory(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (hits.size() >= MAX_HITS) {
                    return FileVisitResult.TERMINATE;
                }
                if (WorkspaceIgnore.skipSnapshotFile(file, attrs)) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = guard.relativize(file);
                if (!glob.isBlank() && !matchesGlob(file.getFileName().toString(), glob) && !matchesGlob(relative, glob)) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    String text = TextFiles.read(file).withLf();
                    String[] fileLines = text.split("\n", -1);
                    for (int i = 0; i < fileLines.length && hits.size() < MAX_HITS; i++) {
                        if (fileLines[i].contains(query)) {
                            hits.add(relative + ":" + (i + 1) + ":" + truncate(fileLines[i]));
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

    /**
     * 把简单 glob（{@code *} / {@code **} / {@code ?}）转成正则再匹配文件名或相对路径。
     * 其它正则元字符先转义，避免 {@code +} / {@code (} 把模式拆坏。
     */
    static boolean matchesGlob(String name, String glob) {
        if (name == null || glob == null || glob.isBlank()) {
            return true;
        }
        String normalized = name.replace('\\', '/');
        return Pattern.compile(globToRegex(glob)).matcher(normalized).matches();
    }

    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                    i++;
                    regex.append("(?:.*/)?");
                }
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (".+()[]{}|^$\\".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }

    private static String truncate(String line) {
        if (line.length() <= CONTEXT_CHARS) {
            return line;
        }
        return line.substring(0, CONTEXT_CHARS) + "...";
    }
}
