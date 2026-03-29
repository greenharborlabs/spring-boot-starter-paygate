package com.greenharborlabs.paygate.protocol.mpp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser for MPP credential payloads.
 *
 * <p>Supports only the subset of JSON needed for credential parsing:
 * objects ({@code Map<String, Object>}), strings, and {@code null}.
 * Arrays, numbers, and booleans are not supported and will throw
 * {@link JsonParseException} if encountered.
 *
 * <p>This class exists to avoid external JSON library dependencies in the
 * protocol module.
 */
final class MinimalJsonParser {

    private final String input;
    private final MppParserLimits limits;
    private int pos;
    private int currentDepth;

    MinimalJsonParser(String input) {
        this(input, MppParserLimits.defaults());
    }

    MinimalJsonParser(String input, MppParserLimits limits) {
        if (input.length() > limits.maxInputLength()) {
            throw new JsonParseException(
                    "JSON input length exceeds maximum of %d".formatted(limits.maxInputLength()));
        }
        this.input = input;
        this.limits = limits;
        this.pos = 0;
        this.currentDepth = 0;
    }

    /**
     * Parses the input string as a JSON object.
     *
     * @return a {@code Map<String, Object>} where values are {@code String},
     *         {@code Map<String, Object>}, or {@code null}
     * @throws JsonParseException if the input is not valid JSON or contains
     *         unsupported value types
     */
    Map<String, Object> parseObject() {
        currentDepth++;
        if (currentDepth > limits.maxDepth()) {
            throw error("JSON nesting depth exceeds maximum of %d".formatted(limits.maxDepth()));
        }
        try {
            skipWhitespace();
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                advance();
                return map;
            }
            int keyCount = 0;
            while (true) {
                skipWhitespace();
                String key = parseString();
                keyCount++;
                if (keyCount > limits.maxKeysPerObject()) {
                    throw error("JSON object key count exceeds maximum of %d".formatted(limits.maxKeysPerObject()));
                }
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    advance();
                } else if (c == '}') {
                    advance();
                    return map;
                } else {
                    throw error("Expected ',' or '}' but got '%c'".formatted(c));
                }
            }
        } finally {
            currentDepth--;
        }
    }

    /**
     * Ensures no trailing content exists after parsing the root value.
     *
     * @throws JsonParseException if there is non-whitespace content remaining
     */
    void expectEnd() {
        skipWhitespace();
        if (pos < input.length()) {
            throw error("Unexpected trailing content at position %d".formatted(pos));
        }
    }

    private Object parseValue() {
        skipWhitespace();
        char c = peek();
        return switch (c) {
            case '"' -> parseString();
            case '{' -> parseObject();
            case 'n' -> parseNull();
            default -> throw error("Unsupported JSON value starting with '%c'".formatted(c));
        };
    }

    private String parseString() {
        expect('"');
        var sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '"') {
                advance();
                String result = sb.toString();
                if (result.length() > limits.maxStringLength()) {
                    throw error("JSON string length exceeds maximum of %d".formatted(limits.maxStringLength()));
                }
                return result;
            }
            if (c == '\\') {
                advance();
                if (pos >= input.length()) {
                    throw error("Unterminated string escape");
                }
                char escaped = input.charAt(pos);
                switch (escaped) {
                    case '"', '\\', '/' -> sb.append(escaped);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 >= input.length()) {
                            throw error("Incomplete unicode escape");
                        }
                        String hex = input.substring(pos + 1, pos + 5);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException _) {
                            throw error("Invalid unicode escape: \\u%s".formatted(hex));
                        }
                        pos += 4; // advance past the 4 hex digits (the loop advance handles +1)
                    }
                    default -> throw error("Unknown escape character: \\%c".formatted(escaped));
                }
                advance();
            } else {
                sb.append(c);
                advance();
            }
        }
        throw error("Unterminated string");
    }

    private Object parseNull() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw error("Expected 'null'");
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= input.length()) {
            throw error("Unexpected end of input");
        }
        return input.charAt(pos);
    }

    private void advance() {
        pos++;
    }

    private void expect(char expected) {
        char actual = peek();
        if (actual != expected) {
            throw error("Expected '%c' but got '%c'".formatted(expected, actual));
        }
        advance();
    }

    private JsonParseException error(String message) {
        return new JsonParseException(message);
    }

    /**
     * Thrown when JSON parsing fails.
     */
    static final class JsonParseException extends RuntimeException {
        JsonParseException(String message) {
            super(message);
        }
    }
}
