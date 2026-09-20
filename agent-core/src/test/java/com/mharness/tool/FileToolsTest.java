package com.mharness.tool;

import com.mharness.workspace.TextFiles;
import com.mharness.workspace.WorkspaceGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolsTest {
    @TempDir
    Path workspace;

    @Test
    void globListsAndSkipsTarget() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.createDirectories(workspace.resolve("target"));
        Files.writeString(workspace.resolve("src/A.java"), "class A {}");
        Files.writeString(workspace.resolve("target/A.class"), "xx");
        GlobTool glob = new GlobTool(new WorkspaceGuard(workspace));
        ToolResult result = glob.execute("{\"pattern\":\"**/*\"}");
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("src/A.java");
        assertThat(result.content()).doesNotContain("target");
    }

    @Test
    void writeThenReadRoundTrip() throws Exception {
        WorkspaceGuard guard = new WorkspaceGuard(workspace);
        assertThat(new WriteFileTool(guard).execute("{\"path\":\"n.txt\",\"content\":\"hi\\n\"}").success()).isTrue();
        ToolResult read = new ReadFileTool(guard).execute("{\"path\":\"n.txt\"}");
        assertThat(read.content()).contains("1|hi");
    }

    @Test
    void searchReplaceMatchesLfNeedleInCrlfFile() throws Exception {
        Path file = workspace.resolve("w.txt");
        Files.writeString(file, "one\r\ntwo\r\n", StandardCharsets.UTF_8);
        SearchReplaceTool tool = new SearchReplaceTool(new WorkspaceGuard(workspace));
        ToolResult result = tool.execute("{\"path\":\"w.txt\",\"old_string\":\"one\\ntwo\\n\",\"new_string\":\"one\\nTWO\\n\"}");
        assertThat(result.success()).isTrue();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("one\r\nTWO\r\n");
    }

    @Test
    void grepJavaFallbackFindsText() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "needle here");
        GrepTool grep = new GrepTool(new WorkspaceGuard(workspace));
        ToolResult result = grep.execute("{\"query\":\"needle\"}");
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("a.txt:1:");
    }

    @Test
    void globToRegexEscapesRegexMetacharacters() {
        assertThat(GrepTool.matchesGlob("file+.java", "file+.java")).isTrue();
        assertThat(GrepTool.matchesGlob("fileX.java", "file+.java")).isFalse();
        assertThat(GrepTool.matchesGlob("src/A.java", "*.java")).isFalse();
        assertThat(GrepTool.matchesGlob("A.java", "*.java")).isTrue();
    }

    @Test
    void writeNewUsesUtf8() throws Exception {
        Path file = workspace.resolve("u.txt");
        TextFiles.writeNew(file, "你好");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("你好");
    }
}
