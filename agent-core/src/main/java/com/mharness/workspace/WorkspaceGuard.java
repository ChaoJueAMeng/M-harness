package com.mharness.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 把用户/模型给出的路径解析到工作区内，阻止 {@code ../} 和符号链接逃出仓库。
 * 已存在文件走 realpath；新建文件则校验已存在的祖先目录仍在工作区内。
 */
public final class WorkspaceGuard {
    private final Path workspace;

    /**
     * 规范化工作区根路径；目录已存在时解析到真实路径，避免通过 junction/symlink 绕过检查。
     */
    public WorkspaceGuard(Path workspace) {
        try {
            Path absolute = workspace.toAbsolutePath().normalize();
            this.workspace = Files.exists(absolute) ? absolute.toRealPath() : absolute;
        } catch (IOException e) {
            throw new IllegalArgumentException("无法解析工作区: " + workspace, e);
        }
    }

    /** 工作区根的绝对真实路径。 */
    public Path workspace() {
        return workspace;
    }

    /**
     * 解析已存在（或作为 dangling symlink 存在）的路径，并确认 realpath 仍在工作区内。
     * 读文件、替换、glob 命中后访问文件时使用。
     */
    public Path resolveExisting(String userPath) throws IOException {
        Path target = resolveNormalized(userPath);
        if (Files.exists(target) || Files.isSymbolicLink(target)) {
            Path real = realPathEvenIfDangling(target);
            assertInside(real);
            return real;
        }
        assertInside(target);
        return target;
    }

    /**
     * 解析「即将创建」的路径：目标本身可以还不存在，但必须落在工作区内，
     * 且已存在的祖先目录的 realpath 也不能逃出工作区。
     */
    public Path resolveForCreate(String userPath) throws IOException {
        Path target = resolveNormalized(userPath);
        assertInside(target);
        Path parent = target.getParent();
        if (parent == null) {
            throw new PathEscapeException("拒绝没有父目录的路径: " + userPath);
        }
        Path existingAncestor = parent;
        while (existingAncestor != null && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            throw new PathEscapeException("无法定位工作区内的父目录: " + userPath);
        }
        Path realAncestor = existingAncestor.toRealPath();
        assertInside(realAncestor);
        if (Files.exists(target) || Files.isSymbolicLink(target)) {
            assertInside(realPathEvenIfDangling(target));
        }
        return target;
    }

    /**
     * 相对路径基于工作区拼接后 normalize；绝对路径只 normalize。
     * 此步尚未跟符号链接，真正的越界检查在 {@link #assertInside(Path)}。
     */
    public Path resolveNormalized(String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new PathEscapeException("路径不能为空");
        }
        Path raw = Path.of(userPath);
        Path target = raw.isAbsolute() ? raw.normalize() : workspace.resolve(raw).normalize();
        return target;
    }

    /** 用 {@link Path#startsWith} 判断规范化后的路径是否仍以工作区为前缀。 */
    public void assertInside(Path target) {
        Path candidate = target.isAbsolute() ? target.normalize() : workspace.resolve(target).normalize();
        if (!candidate.startsWith(workspace)) {
            throw new PathEscapeException("路径逃出工作区: " + candidate);
        }
    }

    /** 把绝对路径转成工作区内的正斜杠相对路径，便于工具结果展示。 */
    public String relativize(Path path) {
        Path relative = workspace.relativize(path);
        return relative.toString().replace('\\', '/');
    }

    /**
     * 解析真实路径；目标是悬空符号链接时，按链接文本拼出指向位置再检查。
     * 这样「指向工作区外的坏链接」也会被拦住。
     */
    private static Path realPathEvenIfDangling(Path target) throws IOException {
        if (Files.exists(target)) {
            return target.toRealPath();
        }
        if (Files.isSymbolicLink(target)) {
            Path parent = target.getParent() == null
                    ? target
                    : target.getParent().toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path linkTarget = Files.readSymbolicLink(target);
            Path resolved = linkTarget.isAbsolute()
                    ? linkTarget.normalize()
                    : parent.resolve(linkTarget).normalize();
            if (Files.exists(resolved)) {
                return resolved.toRealPath();
            }
            return resolved;
        }
        return target.toAbsolutePath().normalize();
    }
}
