package com.mharness.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceIgnoreTest {
    @TempDir
    Path workspace;

    @Test
    void skipsBuiltinAndGitignoreDirectories() throws Exception {
        Files.writeString(workspace.resolve(".gitignore"), """
                # comment
                dist-extra/
                *.log
                !keep
                """);
        WorkspaceIgnore ignore = WorkspaceIgnore.of(workspace);
        assertThat(ignore.skipDirectory(workspace.resolve("node_modules"))).isTrue();
        assertThat(ignore.skipDirectory(workspace.resolve("dist"))).isTrue();
        assertThat(ignore.skipDirectory(workspace.resolve("dist-extra"))).isTrue();
        assertThat(ignore.skipDirectory(workspace.resolve("src"))).isFalse();
    }
}
