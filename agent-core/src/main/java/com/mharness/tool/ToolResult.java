package com.mharness.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ToolResult {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean success;
    private final String error;
    private final String content;
    private final List<Match> matches;

    private ToolResult(boolean success, String error, String content, List<Match> matches) {
        this.success = success;
        this.error = error;
        this.content = content;
        this.matches = matches;
    }

    public static ToolResult ok(String content) {
        return new ToolResult(true, null, content, null);
    }

    public static ToolResult dryRun(String preview) {
        return new ToolResult(true, "DRY_RUN", preview, null);
    }

    public static ToolResult error(String code, String content) {
        return new ToolResult(false, code, content, null);
    }

    public static ToolResult matches(String error, List<Match> matches) {
        return new ToolResult(false, error, null, matches);
    }

    public boolean success() {
        return success;
    }

    public String error() {
        return error;
    }

    public String content() {
        return content;
    }

    public List<Match> matches() {
        return matches;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"SERIALIZE_FAILED\",\"content\":\"" + e.getMessage() + "\"}";
        }
    }

    public boolean isDryRun() {
        return "DRY_RUN".equals(error);
    }

    public record Match(int line, String text) {
    }
}
