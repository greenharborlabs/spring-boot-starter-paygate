package com.greenharborlabs.l402.core.macaroon;

import com.greenharborlabs.l402.core.protocol.ErrorCode;
import com.greenharborlabs.l402.core.protocol.L402Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CapabilitiesCaveatVerifier")
class CapabilitiesCaveatVerifierTest {

    private static final String SERVICE_NAME = "my-api";

    private CapabilitiesCaveatVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new CapabilitiesCaveatVerifier(SERVICE_NAME);
    }

    @Test
    @DisplayName("getKey returns '<serviceName>_capabilities'")
    void getKeyReturnsServiceNameCapabilities() {
        assertThat(verifier.getKey()).isEqualTo("my-api_capabilities");
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("passes when requested capability is in the allowed list")
        void passesWhenCapabilityInList() {
            var caveat = new Caveat("my-api_capabilities", "search,analyze");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestedCapability("search")
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passes when requested capability is present among multiple capabilities")
        void passesWhenCapabilityPresentAmongMultiple() {
            var caveat = new Caveat("my-api_capabilities", "search,analyze,export");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestedCapability("analyze")
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passes when requestedCapability is null (permissive skip)")
        void passesWhenRequestedCapabilityIsNull() {
            var caveat = new Caveat("my-api_capabilities", "search,analyze");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws when capability is not in the allowed list")
        void throwsWhenCapabilityNotInList() {
            var caveat = new Caveat("my-api_capabilities", "search,analyze");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestedCapability("delete")
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(e -> {
                        var l402 = (L402Exception) e;
                        assertThat(l402.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                        assertThat(l402.getMessage()).contains("delete").contains("not allowed");
                    });
        }

        @Test
        @DisplayName("throws when capabilities list is empty (reject all)")
        void throwsWhenCapabilitiesListEmpty() {
            var caveat = new Caveat("my-api_capabilities", " , , ");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestedCapability("search")
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(L402Exception.class)
                    .satisfies(e -> {
                        var l402 = (L402Exception) e;
                        assertThat(l402.getErrorCode()).isEqualTo(ErrorCode.INVALID_SERVICE);
                        assertThat(l402.getMessage()).contains("Empty capabilities list");
                    });
        }

        @Test
        @DisplayName("trims whitespace around capability names")
        void trimsWhitespaceAroundCapabilityNames() {
            var caveat = new Caveat("my-api_capabilities", " search , analyze ");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestedCapability("search")
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("isMoreRestrictive")
    class IsMoreRestrictive {

        @Test
        @DisplayName("returns true when current is a subset of previous")
        void trueWhenCurrentIsSubset() {
            var previous = new Caveat("my-api_capabilities", "search,analyze");
            var current = new Caveat("my-api_capabilities", "search");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("returns true when current equals previous")
        void trueWhenCurrentEqualsPrevious() {
            var previous = new Caveat("my-api_capabilities", "search,analyze");
            var current = new Caveat("my-api_capabilities", "analyze,search");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("returns false when current escalates beyond previous (not a subset)")
        void falseWhenCurrentEscalates() {
            var previous = new Caveat("my-api_capabilities", "search");
            var current = new Caveat("my-api_capabilities", "search,analyze");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }

        @Test
        @DisplayName("returns true when current is empty subset")
        void trueWhenCurrentIsEmpty() {
            var previous = new Caveat("my-api_capabilities", "search,analyze");
            var current = new Caveat("my-api_capabilities", " , ");

            assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
        }

        @Test
        @DisplayName("returns false when current has a capability not in previous")
        void falseWhenCurrentHasNewCapability() {
            var previous = new Caveat("my-api_capabilities", "search,analyze");
            var current = new Caveat("my-api_capabilities", "search,export");

            assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
        }
    }
}
