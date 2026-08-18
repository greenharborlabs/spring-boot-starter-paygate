package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CaveatKeyTest {

  @ParameterizedTest
  @MethodSource("canonicalKeys")
  void removesOnlyEdgeAsciiSpaceAndTabs(String raw, String expected) {
    assertThat(CaveatKey.canonicalize(raw)).isEqualTo(expected);
    assertThat(CaveatKey.hasEdgePadding(raw)).isEqualTo(!raw.equals(expected));
  }

  private static Stream<Arguments> canonicalKeys() {
    return Stream.of(
        Arguments.of("services", "services"),
        Arguments.of(" \tservices\t ", "services"),
        Arguments.of("service name", "service name"),
        Arguments.of("\u00a0services\u00a0", "\u00a0services\u00a0"),
        Arguments.of("\nservices\r", "\nservices\r"),
        Arguments.of("service\tname", "service\tname"),
        Arguments.of(" services=ignored", "services=ignored"));
  }
}
