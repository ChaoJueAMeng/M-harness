package com.mharness.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTest {
    @Test
    void toJsonIncludesContentNotDryRunFlag() {
        String json = ToolResult.ok("README\n# M-harness").toJson();
        assertThat(json).contains("\"success\":true");
        assertThat(json).contains("README");
        assertThat(json).contains("# M-harness");
        assertThat(json).doesNotContain("dryRun");
    }

    @Test
    void toJsonIncludesMatches() {
        String json = ToolResult.matches("MATCH_NOT_UNIQUE", List.of(new ToolResult.Match(3, "foo"))).toJson();
        assertThat(json).contains("MATCH_NOT_UNIQUE");
        assertThat(json).contains("\"line\":3");
        assertThat(json).contains("foo");
    }
}
