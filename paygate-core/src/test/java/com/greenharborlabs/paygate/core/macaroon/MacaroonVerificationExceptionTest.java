package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MacaroonVerificationExceptionTest {

  @Test
  void reasonConstructorSetsReasonAndMessage() {
    var ex =
        new MacaroonVerificationException(
            VerificationFailureReason.CAVEAT_NOT_MET, "path mismatch");

    assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
    assertThat(ex.getMessage()).isEqualTo("path mismatch");
  }

  @Test
  void legacyStringConstructorDefaultsToSignatureInvalid() {
    var ex = new MacaroonVerificationException("sig failed");

    assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.SIGNATURE_INVALID);
    assertThat(ex.getMessage()).isEqualTo("sig failed");
  }

  @Test
  void exceptionIsRuntimeException() {
    var ex =
        new MacaroonVerificationException(
            VerificationFailureReason.CREDENTIAL_EXPIRED, "token expired");

    assertThat(ex).isInstanceOf(RuntimeException.class);
  }

  @Test
  void allReasonsCanBeUsedInConstructor() {
    for (VerificationFailureReason reason : VerificationFailureReason.values()) {
      var ex = new MacaroonVerificationException(reason, "test");
      assertThat(ex.getReason()).isEqualTo(reason);
    }
  }
}
