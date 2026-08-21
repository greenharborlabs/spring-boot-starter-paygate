package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VerificationFailureReasonTest {

  @Test
  void valuesContainsExactlyFiveReasons() {
    assertThat(VerificationFailureReason.values())
        .containsExactly(
            VerificationFailureReason.SIGNATURE_INVALID,
            VerificationFailureReason.CAVEAT_NOT_MET,
            VerificationFailureReason.CREDENTIAL_EXPIRED,
            VerificationFailureReason.CAVEAT_INVALID,
            VerificationFailureReason.CAVEAT_ESCALATION);
  }
}
