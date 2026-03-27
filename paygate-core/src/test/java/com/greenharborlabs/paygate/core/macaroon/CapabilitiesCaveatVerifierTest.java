package com.greenharborlabs.paygate.core.macaroon;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CapabilitiesCaveatVerifier")
class CapabilitiesCaveatVerifierTest {

    private static final String SERVICE_NAME = "my-api";

    private CapabilitiesCaveatVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
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
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
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
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "analyze"))
                    .build();

            assertThatCode(() -> verifier.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects when capability is absent from metadata (fail-closed)")
        void rejectsWhenRequestedCapabilityIsNull() {
            var caveat = new Caveat("my-api_capabilities", "search,analyze");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(MacaroonVerificationException.class)
                    .satisfies(e -> {
                        var ex = (MacaroonVerificationException) e;
                        assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                        assertThat(ex.getMessage()).contains("no capability declared");
                    });
        }

        @Test
        @DisplayName("throws when capability is not in the allowed list")
        void throwsWhenCapabilityNotInList() {
            var caveat = new Caveat("my-api_capabilities", "search,analyze");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "delete"))
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(MacaroonVerificationException.class)
                    .satisfies(e -> {
                        var ex = (MacaroonVerificationException) e;
                        assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                        assertThat(ex.getMessage()).contains("delete").contains("not allowed");
                    });
        }

        @Test
        @DisplayName("throws when capabilities list contains empty segments")
        void throwsWhenCapabilitiesListEmpty() {
            var caveat = new Caveat("my-api_capabilities", " , , ");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(MacaroonVerificationException.class)
                    .satisfies(e -> {
                        var ex = (MacaroonVerificationException) e;
                        assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                        assertThat(ex.getMessage()).contains("Empty segment");
                    });
        }

        @Test
        @DisplayName("trims whitespace around capability names")
        void trimsWhitespaceAroundCapabilityNames() {
            var caveat = new Caveat("my-api_capabilities", " search , analyze ");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
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

    @Nested
    @DisplayName("bounds checking")
    class BoundsChecking {

        @Test
        @DisplayName("verify rejects caveat exceeding max values count")
        void verifyRejectsCaveatExceedingMaxValues() {
            var bounded = new CapabilitiesCaveatVerifier(SERVICE_NAME, 3);
            var caveat = new Caveat("my-api_capabilities", "a,b,c,d");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "a"))
                    .build();

            assertThatThrownBy(() -> bounded.verify(caveat, context))
                    .isInstanceOf(MacaroonVerificationException.class)
                    .satisfies(e -> {
                        var ex = (MacaroonVerificationException) e;
                        assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                        assertThat(ex.getMessage()).contains("4").contains("3");
                    });
        }

        @Test
        @DisplayName("verify accepts caveat at max values limit")
        void verifyAcceptsCaveatAtLimit() {
            var bounded = new CapabilitiesCaveatVerifier(SERVICE_NAME, 3);
            var caveat = new Caveat("my-api_capabilities", "a,b,c");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "a"))
                    .build();

            assertThatCode(() -> bounded.verify(caveat, context))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("verify rejects empty segment in caveat value")
        void verifyRejectsEmptySegment() {
            var caveat = new Caveat("my-api_capabilities", "a,,b");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "a"))
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(MacaroonVerificationException.class)
                    .satisfies(e -> {
                        var ex = (MacaroonVerificationException) e;
                        assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                    });
        }

        @Test
        @DisplayName("verify rejects trailing comma in caveat value")
        void verifyRejectsTrailingComma() {
            var caveat = new Caveat("my-api_capabilities", "a,b,");
            var context = L402VerificationContext.builder()
                    .serviceName(SERVICE_NAME)
                    .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "a"))
                    .build();

            assertThatThrownBy(() -> verifier.verify(caveat, context))
                    .isInstanceOf(MacaroonVerificationException.class)
                    .satisfies(e -> {
                        var ex = (MacaroonVerificationException) e;
                        assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                    });
        }

        @Test
        @DisplayName("isMoreRestrictive returns false when previous exceeds bounds")
        void isMoreRestrictiveRejectsOversizedPrevious() {
            var bounded = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
            String oversized = IntStream.rangeClosed(1, 51)
                    .mapToObj(i -> "cap" + i)
                    .collect(Collectors.joining(","));
            var previous = new Caveat("my-api_capabilities", oversized);
            var current = new Caveat("my-api_capabilities", "a");

            assertThat(bounded.isMoreRestrictive(previous, current)).isFalse();
        }

        @Test
        @DisplayName("isMoreRestrictive returns false when current exceeds bounds")
        void isMoreRestrictiveRejectsOversizedCurrent() {
            var bounded = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
            String oversized = IntStream.rangeClosed(1, 51)
                    .mapToObj(i -> "cap" + i)
                    .collect(Collectors.joining(","));
            var previous = new Caveat("my-api_capabilities", "a");
            var current = new Caveat("my-api_capabilities", oversized);

            assertThat(bounded.isMoreRestrictive(previous, current)).isFalse();
        }

        @Test
        @DisplayName("isMoreRestrictive accepts values within bounds")
        void isMoreRestrictiveAcceptsWithinBounds() {
            var bounded = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
            var previous = new Caveat("my-api_capabilities", "a,b,c");
            var current = new Caveat("my-api_capabilities", "a,b");

            assertThat(bounded.isMoreRestrictive(previous, current)).isTrue();
        }
    }
}
