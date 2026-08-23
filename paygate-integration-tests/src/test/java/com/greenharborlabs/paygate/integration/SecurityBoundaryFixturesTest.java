package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@DisplayName("Security boundary recording fixtures")
class SecurityBoundaryFixturesTest {

  @Test
  @DisplayName("records and resets each security-relevant side effect independently")
  void recordsAndResetsSideEffects() {
    var recorder = SecurityBoundaryFixtures.recordingSideEffects();

    recorder.recordProtectedHandlerExecution();
    recorder.recordInvoiceCreation();
    recorder.recordRootKeyOperation();
    recorder.recordProtocolFormatting();

    assertThat(recorder.snapshot())
        .isEqualTo(new SecurityBoundaryFixtures.SideEffectCounts(1, 1, 1, 1));

    recorder.reset();

    assertThat(recorder.snapshot())
        .isEqualTo(new SecurityBoundaryFixtures.SideEffectCounts(0, 0, 0, 0));
  }
}
