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
}
