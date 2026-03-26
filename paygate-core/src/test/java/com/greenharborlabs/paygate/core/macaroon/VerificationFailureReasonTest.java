package com.greenharborlabs.paygate.core.macaroon;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationFailureReasonTest {

    @Test
    void valuesContainsExactlyFourReasons() {
        assertThat(VerificationFailureReason.values())
                .containsExactly(
                        VerificationFailureReason.SIGNATURE_INVALID,
                        VerificationFailureReason.CAVEAT_NOT_MET,
                        VerificationFailureReason.CREDENTIAL_EXPIRED,
                        VerificationFailureReason.CAVEAT_ESCALATION
                );
    }
}
