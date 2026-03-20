package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ServicesCaveatVerifier")
class ServicesCaveatVerifierTest {

    private ServicesCaveatVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new ServicesCaveatVerifier();
    }

    @Test
    @DisplayName("getKey returns 'services'")
    void getKeyReturnsServices() {
        org.assertj.core.api.Assertions.assertThat(verifier.getKey()).isEqualTo("services");
    }

    @Nested
    @DisplayName("verify with valid service")
    class ValidService {

        @Test
        @DisplayName("passes when caveat contains the requested service name")
        void passesWhenServiceMatches() {
            Caveat caveat = new Caveat("services", "my-api:0");
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName("my-api")
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passes when caveat contains multiple services including the requested one")
        void passesWithMultipleServicesIncludingRequested() {
            Caveat caveat = new Caveat("services", "other-api:0,my-api:1,another:2");
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName("my-api")
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("verify with invalid service")
    class InvalidService {

        @Test
        @DisplayName("throws L402Exception with INVALID_SERVICE when service name does not match")
        void throwsWhenServiceDoesNotMatch() {
            Caveat caveat = new Caveat("services", "other-api:0");
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName("my-api")
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("throws L402Exception with INVALID_SERVICE when service list is empty")
        void throwsWhenServiceListEmpty() {
            // Caveat record rejects blank values, so test with a service list
            // that doesn't contain the requested service
            Caveat caveat = new Caveat("services", "unrelated:0");
            L402VerificationContext context = L402VerificationContext.builder()
                    .serviceName("my-api")
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }

        @Test
        @DisplayName("throws L402Exception when context service name is null")
        void throwsWhenContextServiceNameIsNull() {
            Caveat caveat = new Caveat("services", "my-api:0");
            L402VerificationContext context = L402VerificationContext.builder()
                    .build(); // serviceName defaults to null

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .extracting(e -> ((L402Exception) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_SERVICE);
        }
    }

    @Nested
    @DisplayName("isMoreRestrictive")
    class IsMoreRestrictive {

        @Test
        @DisplayName("returns true when current services are a subset of previous")
        void subsetIsMoreRestrictive() {
            Caveat previous = new Caveat("services", "a:0,b:0,c:0");
            Caveat current = new Caveat("services", "a:0,b:0");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("returns true when current services are equal to previous")
        void equalSetsAreMoreRestrictive() {
            Caveat previous = new Caveat("services", "a:0,b:0");
            Caveat current = new Caveat("services", "b:0,a:0");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("returns true when current is a single service from previous set")
        void singleServiceSubset() {
            Caveat previous = new Caveat("services", "a:0,b:0");
            Caveat current = new Caveat("services", "a:0");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("returns false when current services are a superset of previous (escalation)")
        void supersetIsNotMoreRestrictive() {
            Caveat previous = new Caveat("services", "a:0");
            Caveat current = new Caveat("services", "a:0,b:0");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }

        @Test
        @DisplayName("returns false when current has a service not in previous (escalation)")
        void disjointServiceIsNotMoreRestrictive() {
            Caveat previous = new Caveat("services", "a:0,b:0");
            Caveat current = new Caveat("services", "a:0,c:0");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }
    }
}
