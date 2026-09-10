package com.mharness.workspace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceGuardTest {
    @TempDir
    Path temp;

    @Test
    void rejectsSiblingPrefixPath() throws Exception {
        Path repo = temp.resolve("repo");
        Path repo2 = temp.resolve("repo2");
        Files.createDirectories(repo);
        Files.createDirectories(repo2);
        Files.writeString(repo2.resolve("secret.txt"), "secret");
        WorkspaceGuard guard = new WorkspaceGuard(repo);
        assertThatThrownBy(() -> guard.resolveExisting(repo2.resolve("secret.txt").toString()))
                .isInstanceOf(PathEscapeException.class);
        assertThatThrownBy(() -> guard.resolveExisting(repo2.resolve("secret.txt").toAbsolutePath().toString()))
                .isInstanceOf(PathEscapeException.class);
    }

    @Test
    void rejectsParentTraversal() throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        Files.writeString(temp.resolve("outside.txt"), "nope");
        WorkspaceGuard guard = new WorkspaceGuard(repo);
        assertThatThrownBy(() -> guard.resolveExisting("../outside.txt"))
                .isInstanceOf(PathEscapeException.class);
    }

    @Test
    void allowsWorkspaceFile() throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        Path file = repo.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}");
        WorkspaceGuard guard = new WorkspaceGuard(repo);
        Path resolved = guard.resolveExisting("src/App.java");
        assertThat(resolved).exists();
        assertThat(resolved.toRealPath().startsWith(guard.workspace())).isTrue();
    }

    @Test
    void rejectsSymlinkEscape() throws Exception {
        Path repo = temp.resolve("workspace");
        Path outside = temp.resolve("outside");
        Files.createDirectories(repo);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Path link = repo.resolve("link");
        boolean linked = false;
        try {
            Files.createSymbolicLink(link, outside);
            linked = true;
        } catch (Exception ignored) {
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
                    .redirectErrorStream(true)
                    .start();
            process.waitFor();
            linked = Files.exists(link);
        }
        Assumptions.assumeTrue(linked, "当前环境不能创建符号链接或 junction");
        WorkspaceGuard guard = new WorkspaceGuard(repo);
        assertThatThrownBy(() -> guard.resolveExisting("link/secret.txt"))
                .isInstanceOf(PathEscapeException.class);
    }
}
