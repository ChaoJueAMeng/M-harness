package com.mharness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 解析模型传来的工具 JSON 参数，并按字段取出字符串/整数。
 */
public final class JsonArgs {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonArgs() {
    }

    /** 解析 JSON 对象；空参数当成 {@code {}}。 */
    public static JsonNode parse(String arguments) throws IOException {
        if (arguments == null || arguments.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(arguments);
    }

    /** 取必填字符串字段，缺失或空白则抛 {@link IllegalArgumentException}。 */
    public static String requiredText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException("缺少参数: " + field);
        }
        return node.asText();
    }

    /** 取可选字符串，缺失时返回 defaultValue。 */
    public static String optionalText(JsonNode args, String field, String defaultValue) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return defaultValue;
        }
        return node.asText();
    }

    /** 取可选整数，缺失时返回 defaultValue。 */
    public static int optionalInt(JsonNode args, String field, int defaultValue) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        return node.asInt(defaultValue);
    }
}
