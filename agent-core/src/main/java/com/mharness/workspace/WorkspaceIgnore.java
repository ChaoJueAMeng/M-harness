package com.mharness.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 遍历工作区时统一跳过的目录与文件：版本库元数据、体积巨大的构建产物 / 依赖，以及常见二进制。
 * glob、grep、文件树、checkpoint 快照与回滚都必须使用同一份规则。
 */
public final class WorkspaceIgnore {
    /** 按目录名匹配，不看路径深度。 */
    public static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git",
            "target",
            "node_modules",
            "dist",
            "build",
            "out",
            "bin",
            "obj",
            ".idea",
            ".vs",
            ".venv",
            "venv",
            "__pycache__",
            ".gradle",
            ".next",
            ".turbo",
            "coverage",
            "vendor"
    );

    public static final long MAX_SNAPSHOT_BYTES = 2_000_000;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".class", ".jar", ".exe", ".dll", ".so", ".dylib", ".bin", ".dat", ".pdb",
            ".o", ".a", ".lib", ".obj", ".wasm", ".node",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".bmp", ".psd",
            ".woff", ".woff2", ".ttf", ".otf", ".eot",
            ".pdf", ".zip", ".7z", ".rar", ".gz", ".bz2", ".xz",
            ".mp3", ".mp4", ".wav", ".ogg", ".webm", ".mov"
    );

    private final Set<String> extraDirectories;

    private WorkspaceIgnore(Set<String> extraDirectories) {
        this.extraDirectories = extraDirectories;
    }

    /** 只使用内置名单。 */
    public static WorkspaceIgnore builtin() {
        return new WorkspaceIgnore(Set.of());
    }

    /** 内置名单加上工作区根目录 {@code .gitignore} 里能安全识别的目录名。 */
    public static WorkspaceIgnore of(Path workspace) {
        return new WorkspaceIgnore(readGitignoreDirectories(workspace));
    }

    public boolean skipDirectory(Path dir) {
        Path name = dir.getFileName();
        if (name == null) {
            return false;
        }
        String text = name.toString();
        return SKIPPED_DIRECTORIES.contains(text) || extraDirectories.contains(text);
    }

    public static boolean skipDirectoryName(String name) {
        return name != null && SKIPPED_DIRECTORIES.contains(name);
    }

    /**
     * 快照与回滚都不应碰的文件：符号链接、非普通文件、过大、或常见二进制扩展名。
     */
    public static boolean skipSnapshotFile(Path file, BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
            return true;
        }
        if (attrs.size() > MAX_SNAPSHOT_BYTES) {
            return true;
        }
        Path name = file.getFileName();
        if (name == null) {
            return false;
        }
        String lower = name.toString().toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(lower.substring(dot));
    }

    static Set<String> readGitignoreDirectories(Path workspace) {
        Path file = workspace == null ? null : workspace.resolve(".gitignore");
        if (file == null || !Files.isRegularFile(file)) {
            return Set.of();
        }
        Set<String> extra = new HashSet<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = stripGitignoreComment(raw).trim();
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) {
                    continue;
                }
                if (line.contains("*") || line.contains("?") || line.contains("[")) {
                    continue;
                }
                line = line.replace('\\', '/');
                if (line.startsWith("/")) {
                    line = line.substring(1);
                }
                if (line.endsWith("/")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.isEmpty() || line.contains("/")) {
                    continue;
                }
                extra.add(line);
            }
        } catch (IOException ignored) {
            return Set.of();
        }
        extra.removeAll(SKIPPED_DIRECTORIES);
        return Set.copyOf(extra);
    }

    private static String stripGitignoreComment(String raw) {
        int hash = raw.indexOf('#');
        if (hash < 0) {
            return raw;
        }
        return raw.substring(0, hash);
    }
}
