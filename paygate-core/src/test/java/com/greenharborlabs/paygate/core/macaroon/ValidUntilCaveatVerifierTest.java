package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ValidUntilCaveatVerifier")
class ValidUntilCaveatVerifierTest {

    private static final String SERVICE_NAME = "my-api";

    private ValidUntilCaveatVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new ValidUntilCaveatVerifier(SERVICE_NAME);
    }

    @Test
    @DisplayName("getKey returns '<serviceName>_valid_until'")
    void getKeyReturnsServiceNameValidUntil() {
        assertThat(verifier.getKey()).isEqualTo("my-api_valid_until");
    }

    @Nested
    @DisplayName("verify with future timestamp")
    class FutureTimestamp {

        @Test
        @DisplayName("passes when expiry is in the future relative to context time")
        void passesWhenExpiryInFuture() {
            Instant now = Instant.now();
            long futureEpochSeconds = now.plusSeconds(3600).getEpochSecond();
            Caveat caveat = new Caveat("my-api_valid_until", String.valueOf(futureEpochSeconds));
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(now)
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passes when expiry is exactly at current time boundary")
        void passesWhenExpiryAtExactBoundary() {
            Instant now = Instant.now();
            // Expiry at exactly current second — should still be valid (not yet expired)
            long expiryEpochSeconds = now.getEpochSecond() + 1;
            Caveat caveat = new Caveat("my-api_valid_until", String.valueOf(expiryEpochSeconds));
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(now)
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("verify with past timestamp")
    class PastTimestamp {

        @Test
        @DisplayName("throws L402Exception with EXPIRED_CREDENTIAL when expiry is in the past")
        void throwsWhenExpiryInPast() {
            Instant now = Instant.now();
            long pastEpochSeconds = now.minusSeconds(3600).getEpochSecond();
            Caveat caveat = new Caveat("my-api_valid_until", String.valueOf(pastEpochSeconds));
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(now)
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
        }

        @Test
        @DisplayName("throws L402Exception with EXPIRED_CREDENTIAL when expiry is epoch zero")
        void throwsWhenExpiryIsEpochZero() {
            Caveat caveat = new Caveat("my-api_valid_until", "0");
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(Instant.now())
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
        }

        @Test
        @DisplayName("throws L402Exception when expiry equals current time exactly (already expired)")
        void throwsWhenExpiryEqualsCurrentTime() {
            Instant now = Instant.ofEpochSecond(1700000000);
            Caveat caveat = new Caveat("my-api_valid_until", "1700000000");
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .currentTime(now)
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
        }
    }

    @Nested
    @DisplayName("isMoreRestrictive")
    class IsMoreRestrictive {

        @Test
        @DisplayName("returns true when current timestamp is earlier than previous")
        void earlierTimestampIsMoreRestrictive() {
            Caveat previous = new Caveat("my-api_valid_until", "2000000000");
            Caveat current = new Caveat("my-api_valid_until", "1999999000");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("returns true when current timestamp equals previous")
        void equalTimestampIsMoreRestrictive() {
            Caveat previous = new Caveat("my-api_valid_until", "2000000000");
            Caveat current = new Caveat("my-api_valid_until", "2000000000");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("returns false when current timestamp is later than previous (escalation)")
        void laterTimestampIsNotMoreRestrictive() {
            Caveat previous = new Caveat("my-api_valid_until", "1999999000");
            Caveat current = new Caveat("my-api_valid_until", "2000000000");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }

        @Test
        @DisplayName("returns false when previous value is not a valid number")
        void invalidPreviousReturnsFalse() {
            Caveat previous = new Caveat("my-api_valid_until", "not-a-number");
            Caveat current = new Caveat("my-api_valid_until", "2000000000");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }

        @Test
        @DisplayName("returns false when current value is not a valid number")
        void invalidCurrentReturnsFalse() {
            Caveat previous = new Caveat("my-api_valid_until", "2000000000");
            Caveat current = new Caveat("my-api_valid_until", "not-a-number");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }
    }
}
