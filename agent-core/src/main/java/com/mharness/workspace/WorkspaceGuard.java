package com.mharness.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Resolves user-supplied paths against the workspace using Path.startsWith,
 * following realpath for existing files and parent directories.
 */
public final class WorkspaceGuard {
    private final Path workspace;

    public WorkspaceGuard(Path workspace) {
        try {
            Path absolute = workspace.toAbsolutePath().normalize();
            this.workspace = Files.exists(absolute) ? absolute.toRealPath() : absolute;
        } catch (IOException e) {
            throw new IllegalArgumentException("无法解析工作区: " + workspace, e);
        }
    }

    public Path workspace() {
        return workspace;
    }

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

    public Path resolveNormalized(String userPath) {
        if (userPath == null || userPath.isBlank()) {
            throw new PathEscapeException("路径不能为空");
        }
        Path raw = Path.of(userPath);
        Path target = raw.isAbsolute() ? raw.normalize() : workspace.resolve(raw).normalize();
        return target;
    }

    public void assertInside(Path target) {
        Path candidate = target.isAbsolute() ? target.normalize() : workspace.resolve(target).normalize();
        if (!candidate.startsWith(workspace)) {
            throw new PathEscapeException("路径逃出工作区: " + candidate);
        }
    }

    public String relativize(Path path) {
        Path relative = workspace.relativize(path);
        return relative.toString().replace('\\', '/');
    }

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
