package com.greenharborlabs.paygate.protocol.mpp;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinimalJsonParserTest {

    private Map<String, Object> parse(String json) {
        var parser = new MinimalJsonParser(json);
        Map<String, Object> result = parser.parseObject();
        parser.expectEnd();
        return result;
    }

    @Test
    void parsesEmptyObject() {
        Map<String, Object> result = parse("{}");
        assertThat(result).isEmpty();
    }

    @Test
    void parsesSingleStringField() {
        Map<String, Object> result = parse("""
                {"key": "value"}""");
        assertThat(result).containsEntry("key", "value");
    }

    @Test
    void parsesNullValue() {
        Map<String, Object> result = parse("""
                {"key": null}""");
        assertThat(result).containsEntry("key", null);
    }

    @Test
    void parsesNestedObject() {
        Map<String, Object> result = parse("""
                {"outer": {"inner": "value"}}""");
        assertThat(result).containsKey("outer");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) result.get("outer");
        assertThat(nested).containsEntry("inner", "value");
    }

    @Test
    void parsesEscapedStrings() {
        Map<String, Object> result = parse("""
                {"key": "line1\\nline2\\ttab\\\\\\"quote\\/slash"}""");
        assertThat(result).containsEntry("key", "line1\nline2\ttab\\\"quote/slash");
    }

    @Test
    void parsesUnicodeEscapes() {
        Map<String, Object> result = parse("""
                {"key": "\\u0041\\u0042"}""");
        assertThat(result).containsEntry("key", "AB");
    }

    @Test
    void parsesMultipleFields() {
        Map<String, Object> result = parse("""
                {"a": "1", "b": "2", "c": "3"}""");
        assertThat(result).hasSize(3);
        assertThat(result).containsEntry("a", "1");
        assertThat(result).containsEntry("b", "2");
        assertThat(result).containsEntry("c", "3");
    }

    @Test
    void handlesWhitespace() {
        Map<String, Object> result = parse("  {  \"key\"  :  \"value\"  }  ");
        assertThat(result).containsEntry("key", "value");
    }

    @Test
    void rejectsUnterminatedString() {
        assertThatThrownBy(() -> parse("""
                {"key": "unterminated"""))
                .isInstanceOf(MinimalJsonParser.JsonParseException.class);
    }

    @Test
    void rejectsTrailingComma() {
        assertThatThrownBy(() -> parse("""
                {"key": "value",}"""))
                .isInstanceOf(MinimalJsonParser.JsonParseException.class);
    }

    @Test
    void rejectsMissingColon() {
        assertThatThrownBy(() -> parse("""
                {"key" "value"}"""))
                .isInstanceOf(MinimalJsonParser.JsonParseException.class);
    }

    @Test
    void rejectsUnsupportedValueTypes() {
        // Numbers are not supported
        assertThatThrownBy(() -> parse("""
                {"key": 42}"""))
                .isInstanceOf(MinimalJsonParser.JsonParseException.class);
    }

    @Test
    void rejectsTrailingContent() {
        var parser = new MinimalJsonParser("""
                {"key": "value"}extra""");
        parser.parseObject();
        assertThatThrownBy(parser::expectEnd)
                .isInstanceOf(MinimalJsonParser.JsonParseException.class)
                .hasMessageContaining("Unexpected trailing content");
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> parse(""))
                .isInstanceOf(MinimalJsonParser.JsonParseException.class);
    }

    // --- MppParserLimits validation ---

    @Test
    void limitsRecordRejectsNonPositiveMaxDepth() {
        assertThatThrownBy(() -> new MppParserLimits(0, 100, 10, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }

    @Test
    void limitsRecordRejectsNonPositiveMaxStringLength() {
        assertThatThrownBy(() -> new MppParserLimits(5, 0, 10, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxStringLength");
    }

    @Test
    void limitsRecordRejectsNonPositiveMaxKeysPerObject() {
        assertThatThrownBy(() -> new MppParserLimits(5, 100, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxKeysPerObject");
    }

    @Test
    void limitsRecordRejectsNonPositiveMaxInputLength() {
        assertThatThrownBy(() -> new MppParserLimits(5, 100, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxInputLength");
    }

    // --- Depth limits ---

    @Test
    void depthLimitHitAt6LevelsWithMaxDepth5() {
        var limits = new MppParserLimits(5, 8192, 32, 65_536);
        String json = """
                {"a":{"b":{"c":{"d":{"e":{"f":"deep"}}}}}}""";
        var parser = new MinimalJsonParser(json, limits);
        assertThatThrownBy(parser::parseObject)
                .isInstanceOf(MinimalJsonParser.JsonParseException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void depthLimitNotHitAtExactly5LevelsWithMaxDepth5() {
        var limits = new MppParserLimits(5, 8192, 32, 65_536);
        String json = """
                {"a":{"b":{"c":{"d":{"e":"ok"}}}}}""";
        var parser = new MinimalJsonParser(json, limits);
        Map<String, Object> result = parser.parseObject();
        parser.expectEnd();
        assertThat(result).containsKey("a");
    }

    @Test
    void depthOneAllowsFlatObject() {
        var limits = new MppParserLimits(1, 8192, 32, 65_536);
        String json = """
                {"a":"b","c":"d"}""";
        var parser = new MinimalJsonParser(json, limits);
        Map<String, Object> result = parser.parseObject();
        parser.expectEnd();
        assertThat(result).containsEntry("a", "b").containsEntry("c", "d");
    }

    @Test
    void depthOneRejectsNestedObject() {
        var limits = new MppParserLimits(1, 8192, 32, 65_536);
        String json = """
                {"a":{"b":"c"}}""";
        var parser = new MinimalJsonParser(json, limits);
        assertThatThrownBy(parser::parseObject)
                .isInstanceOf(MinimalJsonParser.JsonParseException.class)
                .hasMessageContaining("depth");
    }

    // --- String length limit ---

    @Test
    void rejectsStringExceedingMaxLength() {
        var limits = new MppParserLimits(5, 8192, 32, 65_536);
        String longValue = "x".repeat(8193);
        String json = "{\"k\":\"" + longValue + "\"}";
        var parser = new MinimalJsonParser(json, limits);
        assertThatThrownBy(parser::parseObject)
                .isInstanceOf(MinimalJsonParser.JsonParseException.class)
                .hasMessageContaining("string length");
    }

    @Test
    void acceptsStringAtExactMaxLength() {
        var limits = new MppParserLimits(5, 8192, 32, 65_536);
        String exactValue = "x".repeat(8192);
        String json = "{\"k\":\"" + exactValue + "\"}";
        var parser = new MinimalJsonParser(json, limits);
        Map<String, Object> result = parser.parseObject();
        parser.expectEnd();
        assertThat(result).containsEntry("k", exactValue);
    }

    // --- Key count limit ---

    @Test
    void rejectsObjectExceedingMaxKeys() {
        var limits = new MppParserLimits(5, 8192, 32, 65_536);
        var sb = new StringBuilder("{");
        for (int i = 0; i < 33; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"k%d\":\"v\"".formatted(i));
        }
        sb.append("}");
        var parser = new MinimalJsonParser(sb.toString(), limits);
        assertThatThrownBy(parser::parseObject)
                .isInstanceOf(MinimalJsonParser.JsonParseException.class)
                .hasMessageContaining("key count");
    }

    @Test
    void acceptsObjectAtExactMaxKeys() {
        var limits = new MppParserLimits(5, 8192, 32, 65_536);
        var sb = new StringBuilder("{");
        for (int i = 0; i < 32; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"k%d\":\"v\"".formatted(i));
        }
        sb.append("}");
        var parser = new MinimalJsonParser(sb.toString(), limits);
        Map<String, Object> result = parser.parseObject();
        parser.expectEnd();
        assertThat(result).hasSize(32);
    }

    // --- Input length limit ---

    @Test
    void rejectsInputExceedingMaxLength() {
        var limits = new MppParserLimits(5, 8192, 32, 65_536);
        String longInput = "{\"k\":\"" + "x".repeat(65_531) + "\"}";
        // longInput.length() = 6 + 65531 + 2 = 65539 > 65536
        assertThatThrownBy(() -> new MinimalJsonParser(longInput, limits))
                .isInstanceOf(MinimalJsonParser.JsonParseException.class)
                .hasMessageContaining("input length");
    }

    // --- Default limits parse valid credential JSON ---

    @Test
    void defaultLimitsParseValidCredentialJson() {
        String json = """
                {"version":"1","challenge":"abc123","receipt":"def456","service":"test"}""";
        var parser = new MinimalJsonParser(json, MppParserLimits.defaults());
        Map<String, Object> result = parser.parseObject();
        parser.expectEnd();
        assertThat(result).hasSize(4);
        assertThat(result).containsEntry("version", "1");
        assertThat(result).containsEntry("service", "test");
    }
}
