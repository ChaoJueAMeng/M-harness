package com.mharness.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        boolean success,
        String error,
        String content,
        List<Match> matches
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"SERIALIZE_FAILED\",\"content\":\"" + e.getMessage() + "\"}";
        }
    }

    @JsonIgnore
    public boolean isDryRun() {
        return "DRY_RUN".equals(error);
    }

    public record Match(int line, String text) {
    }
}
