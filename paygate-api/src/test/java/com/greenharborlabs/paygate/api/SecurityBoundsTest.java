package com.greenharborlabs.paygate.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityBoundsTest {

  @ParameterizedTest
  @ValueSource(longs = {1L, 16_777_216L})
  void acceptsRequestBodySizesAtTheSupportedBoundaries(long bodySizeBytes) {
    assertThat(SecurityBounds.isValidRequestBodySize(bodySizeBytes)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, 16_777_217L, Long.MAX_VALUE, Long.MIN_VALUE})
  void rejectsRequestBodySizesOutsideTheSupportedRange(long bodySizeBytes) {
    assertThat(SecurityBounds.isValidRequestBodySize(bodySizeBytes)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 86_400L})
  void acceptsLifetimesAtTheSupportedBoundaries(long lifetimeSeconds) {
    assertThat(SecurityBounds.isValidLifetime(lifetimeSeconds)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, 86_401L, Long.MAX_VALUE, Long.MIN_VALUE})
  void rejectsLifetimesOutsideTheSupportedRangeWithoutOverflow(long lifetimeSeconds) {
    assertThat(SecurityBounds.isValidLifetime(lifetimeSeconds)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 2_100_000_000_000_000L})
  void acceptsPricesAtTheSupportedBoundaries(long priceSats) {
    assertThat(SecurityBounds.isValidPrice(priceSats)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, 2_100_000_000_000_001L, Long.MAX_VALUE, Long.MIN_VALUE})
  void rejectsPricesOutsideTheSupportedRangeWithoutOverflow(long priceSats) {
    assertThat(SecurityBounds.isValidPrice(priceSats)).isFalse();
  }
}
