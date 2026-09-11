package com.mharness.tool;

import com.mharness.workspace.WorkspaceGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchReplaceToolTest {
    @TempDir
    Path workspace;

    @Test
    void replacesUniqueMatch() throws Exception {
        Files.writeString(workspace.resolve("A.java"), "alpha\nbeta\nalpha-not\n");
        SearchReplaceTool tool = new SearchReplaceTool(new WorkspaceGuard(workspace));
        ToolResult result = tool.execute("""
                {"path":"A.java","old_string":"beta","new_string":"gamma"}
                """);
        assertThat(result.success()).isTrue();
        assertThat(Files.readString(workspace.resolve("A.java"))).contains("gamma");
    }

    @Test
    void reportsNonUniqueMatches() throws Exception {
        Files.writeString(workspace.resolve("A.java"), "foo\nbar\nfoo\nbaz\nfoo\n");
        SearchReplaceTool tool = new SearchReplaceTool(new WorkspaceGuard(workspace));
        ToolResult result = tool.execute("""
                {"path":"A.java","old_string":"foo","new_string":"qux"}
                """);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("MATCH_NOT_UNIQUE");
        assertThat(result.matches()).hasSize(3);
        assertThat(result.matches().get(0).line()).isEqualTo(1);
        assertThat(Files.readString(workspace.resolve("A.java"))).contains("foo");
    }

    @Test
    void reportsMissingMatch() throws Exception {
        Files.writeString(workspace.resolve("A.java"), "hello\n");
        SearchReplaceTool tool = new SearchReplaceTool(new WorkspaceGuard(workspace));
        ToolResult result = tool.execute("""
                {"path":"A.java","old_string":"missing","new_string":"x"}
                """);
        assertThat(result.error()).isEqualTo("MATCH_NOT_FOUND");
    }

    @Test
    void matchCapIsFive() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            text.append("needle\n");
        }
        Files.writeString(workspace.resolve("A.java"), text.toString());
        List<Integer> offsets = SearchReplaceTool.findOffsets(text.toString(), "needle");
        assertThat(offsets).hasSize(8);
        assertThat(SearchReplaceTool.lineNumber(text.toString(), offsets.get(1))).isEqualTo(2);
        SearchReplaceTool tool = new SearchReplaceTool(new WorkspaceGuard(workspace));
        ToolResult result = tool.execute("""
                {"path":"A.java","old_string":"needle","new_string":"x"}
                """);
        assertThat(result.error()).isEqualTo("MATCH_NOT_UNIQUE");
        assertThat(result.matches()).hasSize(5);
        assertThat(Files.readString(workspace.resolve("A.java"))).contains("needle");
    }
}
