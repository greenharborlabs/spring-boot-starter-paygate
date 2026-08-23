package com.greenharborlabs.paygate.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
@DisplayName("Low-security test support")
class LowSecurityTestSupportTest {

  @Test
  @DisplayName("loads non-secret markers and rejects their appearance in diagnostics")
  void rejectsCompleteMarkerValues() {
    var marker = LowSecurityTestSupport.markerSecrets().getFirst();

    assertThat(LowSecurityTestSupport.markerSecrets()).isNotEmpty();
    LowSecurityTestSupport.assertContainsNoMarkerSecrets("safe response", "fixed public detail");
    assertThatThrownBy(
            () -> LowSecurityTestSupport.assertContainsNoMarkerSecrets("unsafe response", marker))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("rejects any observed protected-path side effect")
  void rejectsProtectedPathSideEffects() {
    var count = new AtomicLong();

    LowSecurityTestSupport.assertNoProtectedHandlerExecution(count::get);
    LowSecurityTestSupport.assertNoInvoiceCreation(count::get);
    LowSecurityTestSupport.assertNoRootKeyOperation(count::get);
    LowSecurityTestSupport.assertNoProtocolFormatting(count::get);

    count.incrementAndGet();

    assertThatThrownBy(() -> LowSecurityTestSupport.assertNoInvoiceCreation(count::get))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  @DisplayName("bounds captures and rejects nested attacker-controlled error text")
  void rejectsNestedAttackerControlledMessages() {
    var captured = LowSecurityTestSupport.capturedDiagnostics();
    var attackerControlled =
        new IllegalStateException(
            "outer attacker marker", new IllegalArgumentException("nested marker"));

    captured.record("safe response", "fixed public detail");
    captured.assertContainsNoMarkers();
    captured.assertDoesNotContain(attackerControlled);

    captured.record("unsafe response", "fixed public detail nested marker");

    assertThatThrownBy(() -> captured.assertDoesNotContain(attackerControlled))
        .isInstanceOf(AssertionError.class);
  }
}
