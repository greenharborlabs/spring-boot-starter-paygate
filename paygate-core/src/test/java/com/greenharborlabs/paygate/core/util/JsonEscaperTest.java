package com.greenharborlabs.paygate.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonEscaperTest {

  @Test
  void normalTextPassesThrough() {
    assertThat(JsonEscaper.escape("hello world")).isEqualTo("hello world");
  }

  @Test
  void emptyStringReturnsEmpty() {
    assertThat(JsonEscaper.escape("")).isEqualTo("");
  }

  @Test
  void nullInputReturnsEmptyString() {
    assertThat(JsonEscaper.escape(null)).isEqualTo("");
  }

  @Test
  void doubleQuotesAreEscaped() {
    assertThat(JsonEscaper.escape("say \"hello\"")).isEqualTo("say \\\"hello\\\"");
  }

  @Test
  void backslashesAreEscaped() {
    assertThat(JsonEscaper.escape("path\\to\\file")).isEqualTo("path\\\\to\\\\file");
  }

  @Test
  void newlinesAreEscaped() {
    assertThat(JsonEscaper.escape("line1\nline2")).isEqualTo("line1\\nline2");
  }

  @Test
  void carriageReturnsAreEscaped() {
    assertThat(JsonEscaper.escape("line1\rline2")).isEqualTo("line1\\rline2");
  }

  @Test
  void tabsAreEscaped() {
    assertThat(JsonEscaper.escape("col1\tcol2")).isEqualTo("col1\\tcol2");
  }

  @Test
  void controlCharsBelow0x20AreUnicodeEscaped() {
    // NUL (0x00), BEL (0x07), BS (0x08), FF (0x0C)
    assertThat(JsonEscaper.escape("\u0000")).isEqualTo("\\u0000");
    assertThat(JsonEscaper.escape("\u0007")).isEqualTo("\\u0007");
    assertThat(JsonEscaper.escape("\u0008")).isEqualTo("\\u0008");
    assertThat(JsonEscaper.escape("\u000C")).isEqualTo("\\u000c");
  }

  @Test
  void mixedContentIsEscapedCorrectly() {
    String input = "He said \"hi\"\nand\tthen\u0001left";
    String expected = "He said \\\"hi\\\"\\nand\\tthen\\u0001left";
    assertThat(JsonEscaper.escape(input)).isEqualTo(expected);
  }

  @Test
  void constructorIsPrivate() throws Exception {
    var constructor = JsonEscaper.class.getDeclaredConstructor();
    assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
    constructor.setAccessible(true);
    constructor.newInstance(); // exercises the constructor for coverage
  }
}
