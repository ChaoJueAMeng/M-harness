package com.mharness.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

public final class JsonArgs {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonArgs() {
    }

    public static JsonNode parse(String arguments) throws IOException {
        if (arguments == null || arguments.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(arguments);
    }

    public static String requiredText(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalArgumentException("缺少参数: " + field);
        }
        return node.asText();
    }

    public static String optionalText(JsonNode args, String field, String defaultValue) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return defaultValue;
        }
        return node.asText();
    }

    public static int optionalInt(JsonNode args, String field, int defaultValue) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        return node.asInt(defaultValue);
    }
}
