package com.mharness.tool;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 一次工具执行的结构化结果，序列化成 JSON 后再作为 TOOL 轮次回给模型。
 *
 * @param success 是否视为成功（dry-run 也是 true）
 * @param error   错误码或 {@code DRY_RUN}；成功且非预览时为 null
 * @param content 文本内容或错误说明
 * @param matches search_replace 不唯一时返回的若干处匹配
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        boolean success,
        String error,
        String content,
        List<Match> matches
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 成功，content 为给模型看的文本。 */
    public static ToolResult ok(String content) {
        return new ToolResult(true, null, content, null);
    }

    /** 预览：success=true 但 error=DRY_RUN，循环据此标记状态而不落盘。 */
    public static ToolResult dryRun(String preview) {
        return new ToolResult(true, "DRY_RUN", preview, null);
    }

    /** 失败，code 会作为 error 字段回传，例如 MATCH_NOT_FOUND。 */
    public static ToolResult error(String code, String content) {
        return new ToolResult(false, code, content, null);
    }

    /** 带多处匹配的失败（search_replace 的 MATCH_NOT_UNIQUE）。 */
    public static ToolResult matches(String error, List<Match> matches) {
        return new ToolResult(false, error, null, matches);
    }

    /** 序列化为模型可见的 JSON；序列化失败时退回手写错误对象。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"error\":\"SERIALIZE_FAILED\",\"content\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 是否为 dry-run 预览结果。 */
    @JsonIgnore
    public boolean isDryRun() {
        return "DRY_RUN".equals(error);
    }

    /**
     * 一处文本匹配，供模型缩小 old_string。
     *
     * @param line 1-based 行号
     * @param text 附近片段
     */
    public record Match(int line, String text) {
    }
}
