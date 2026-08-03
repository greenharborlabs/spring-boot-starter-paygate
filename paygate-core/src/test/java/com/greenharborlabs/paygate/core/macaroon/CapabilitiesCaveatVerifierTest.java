package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

  @Test
  @DisplayName("effective capability parser returns immutable named and sentinel sets")
  void effectiveCapabilityParserReturnsImmutableSets() {
    Set<String> named = verifier.parseEffectiveCapabilities("search,analyze,search");

    assertThat(named).containsExactlyInAnyOrder("search", "analyze");
    assertThat(verifier.parseEffectiveCapabilities("~")).isEmpty();
    assertThatThrownBy(() -> named.add("admin")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Nested
  @DisplayName("verify")
  class Verify {

    @Test
    @DisplayName("uses the final capability caveat when a named ceiling narrows to none")
    void usesFinalCaveatWhenNamedCeilingNarrowsToNone() {
      var caveats =
          List.of(
              new Caveat("my-api_capabilities", "search,analyze"),
              new Caveat("my-api_capabilities", "~"));
      var context = L402VerificationContext.builder().serviceName(SERVICE_NAME).build();

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(caveats, List.of(verifier), context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes when requested capability is in the allowed list")
    void passesWhenCapabilityInList() {
      var caveat = new Caveat("my-api_capabilities", "search,analyze");
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes when requested capability is present among multiple capabilities")
    void passesWhenCapabilityPresentAmongMultiple() {
      var caveat = new Caveat("my-api_capabilities", "search,analyze,export");
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "analyze"))
              .build();

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("treats a multi-capability endpoint declaration as any-of")
    void passesWhenFinalCeilingOverlapsMultiCapabilityDeclaration() {
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(
                  Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search,analyze"))
              .build();

      assertThatCode(() -> verifier.verify(new Caveat("my-api_capabilities", "search"), context))
          .doesNotThrowAnyException();
      assertThatCode(() -> verifier.verify(new Caveat("my-api_capabilities", "analyze"), context))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a disjoint final ceiling for a multi-capability endpoint declaration")
    void rejectsDisjointFinalCeilingForMultiCapabilityDeclaration() {
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(
                  Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search,analyze"))
              .build();

      assertThatThrownBy(
              () -> verifier.verify(new Caveat("my-api_capabilities", "export"), context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("not allowed");
      assertThatThrownBy(() -> verifier.verify(new Caveat("my-api_capabilities", "~"), context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("not allowed");
    }

    @Test
    @DisplayName("rejects malformed endpoint capability declarations")
    void rejectsMalformedEndpointCapabilityDeclarations() {
      for (String requested : List.of("", " ", "search,,analyze", "~,search", "~")) {
        var context =
            L402VerificationContext.builder()
                .serviceName(SERVICE_NAME)
                .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, requested))
                .build();

        assertThatThrownBy(
                () -> verifier.verify(new Caveat("my-api_capabilities", "search"), context))
            .as("requested declaration %s must fail closed", requested)
            .isInstanceOf(MacaroonVerificationException.class);
      }
    }

    @Test
    @DisplayName("rejects endpoint capability declarations exceeding the configured bound")
    void rejectsOverBoundEndpointCapabilityDeclaration() {
      var bounded = new CapabilitiesCaveatVerifier(SERVICE_NAME, 2);
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(
                  Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search,analyze,export"))
              .build();

      assertThatThrownBy(() -> bounded.verify(new Caveat("my-api_capabilities", "search"), context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("maximum allowed is 2");
    }

    @Test
    @DisplayName("rejects when capability is absent from metadata (fail-closed)")
    void rejectsWhenRequestedCapabilityIsNull() {
      var caveat = new Caveat("my-api_capabilities", "search,analyze");
      var context = L402VerificationContext.builder().serviceName(SERVICE_NAME).build();

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              e -> {
                var ex = (MacaroonVerificationException) e;
                assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                assertThat(ex.getMessage()).contains("no capability declared");
              });
    }

    @Test
    @DisplayName("throws when capability is not in the allowed list")
    void throwsWhenCapabilityNotInList() {
      var caveat = new Caveat("my-api_capabilities", "search,analyze");
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "delete"))
              .build();

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              e -> {
                var ex = (MacaroonVerificationException) e;
                assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                assertThat(ex.getMessage()).contains("delete").contains("not allowed");
              });
    }

    @Test
    @DisplayName("throws when capabilities list contains empty segments")
    void throwsWhenCapabilitiesListEmpty() {
      var caveat = new Caveat("my-api_capabilities", " , , ");
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              e -> {
                var ex = (MacaroonVerificationException) e;
                assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
                assertThat(ex.getMessage()).contains("Empty segment");
              });
    }

    @Test
    @DisplayName("trims whitespace around capability names")
    void trimsWhitespaceAroundCapabilityNames() {
      var caveat = new Caveat("my-api_capabilities", " search , analyze ");
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      assertThatCode(() -> verifier.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("passes with no requested capability only for the no-capability sentinel")
    void passesCapabilityLessEndpointOnlyForSentinel() {
      var context = L402VerificationContext.builder().serviceName(SERVICE_NAME).build();

      assertThatCode(() -> verifier.verify(new Caveat("my-api_capabilities", "~"), context))
          .doesNotThrowAnyException();
      assertThatThrownBy(
              () -> verifier.verify(new Caveat("my-api_capabilities", "search"), context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("no capability declared");
    }

    @Test
    @DisplayName("rejects a named request when the final ceiling is no capability")
    void rejectsNamedRequestForSentinel() {
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      assertThatThrownBy(() -> verifier.verify(new Caveat("my-api_capabilities", "~"), context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("search")
          .hasMessageContaining("not allowed");
    }

    @Test
    @DisplayName("rejects mixing the no-capability sentinel with named capabilities")
    void rejectsMixedSentinelAndNames() {
      var context = L402VerificationContext.builder().serviceName(SERVICE_NAME).build();

      assertThatThrownBy(
              () -> verifier.verify(new Caveat("my-api_capabilities", "~,search"), context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("cannot be mixed");
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
    @DisplayName("returns false when current contains blank segments")
    void falseWhenCurrentContainsBlankSegments() {
      var previous = new Caveat("my-api_capabilities", "search,analyze");
      var current = new Caveat("my-api_capabilities", " , ");

      assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
    }

    @Test
    @DisplayName("returns false when current has a capability not in previous")
    void falseWhenCurrentHasNewCapability() {
      var previous = new Caveat("my-api_capabilities", "search,analyze");
      var current = new Caveat("my-api_capabilities", "search,export");

      assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
    }

    @Test
    @DisplayName("allows a named ceiling to narrow to no capability")
    void allowsNamedToNone() {
      var previous = new Caveat("my-api_capabilities", "search,analyze");
      var current = new Caveat("my-api_capabilities", "~");

      assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
    }

    @Test
    @DisplayName("allows the no-capability ceiling to remain no capability")
    void allowsNoneToNone() {
      var previous = new Caveat("my-api_capabilities", "~");
      var current = new Caveat("my-api_capabilities", "~");

      assertThat(verifier.isMoreRestrictive(previous, current)).isTrue();
    }

    @Test
    @DisplayName("rejects changing no capability into a named grant")
    void rejectsNoneToNamed() {
      var previous = new Caveat("my-api_capabilities", "~");
      var current = new Caveat("my-api_capabilities", "search");

      assertThat(verifier.isMoreRestrictive(previous, current)).isFalse();
    }

    @Test
    @DisplayName("rejects mixed sentinel values in either occurrence")
    void rejectsMixedSentinelValues() {
      var named = new Caveat("my-api_capabilities", "search");
      var mixed = new Caveat("my-api_capabilities", "~,search");

      assertThat(verifier.isMoreRestrictive(named, mixed)).isFalse();
      assertThat(verifier.isMoreRestrictive(mixed, named)).isFalse();
    }

    @Test
    @DisplayName("treats duplicates and ordering with set semantics")
    void duplicateOrderDoesNotChangeSetSemantics() {
      var previous = new Caveat("my-api_capabilities", "search,analyze,search");
      var reordered = new Caveat("my-api_capabilities", "analyze,search");
      var narrowed = new Caveat("my-api_capabilities", "search,search");

      assertThat(verifier.isMoreRestrictive(previous, reordered)).isTrue();
      assertThat(verifier.isMoreRestrictive(previous, narrowed)).isTrue();
    }

    @Test
    @DisplayName("allows only bounded monotonic narrowing across repeated caveats")
    void allowsOnlyBoundedMonotonicNarrowingAcrossRepeatedCaveats() {
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();
      var valid =
          List.of(
              new Caveat("my-api_capabilities", "search,analyze,search"),
              new Caveat("my-api_capabilities", "search"));
      var expansion =
          List.of(
              new Caveat("my-api_capabilities", "search"),
              new Caveat("my-api_capabilities", "search,analyze"));

      assertThatCode(() -> MacaroonVerifier.verifyCaveats(valid, List.of(verifier), context))
          .doesNotThrowAnyException();
      assertThatThrownBy(
              () -> MacaroonVerifier.verifyCaveats(expansion, List.of(verifier), context))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              e ->
                  assertThat(((MacaroonVerificationException) e).getReason())
                      .isEqualTo(VerificationFailureReason.CAVEAT_ESCALATION));
    }

    @Test
    @DisplayName("evaluates named endpoint satisfaction only against the final caveat")
    void evaluatesNamedEndpointAgainstFinalCaveat() {
      var caveats =
          List.of(
              new Caveat("my-api_capabilities", "search,analyze"),
              new Caveat("my-api_capabilities", "analyze"));
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "search"))
              .build();

      assertThatThrownBy(() -> MacaroonVerifier.verifyCaveats(caveats, List.of(verifier), context))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageContaining("search")
          .hasMessageContaining("not allowed");
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
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "a"))
              .build();

      assertThatThrownBy(() -> bounded.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              e -> {
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
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "a"))
              .build();

      assertThatCode(() -> bounded.verify(caveat, context)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("verify rejects empty segment in caveat value")
    void verifyRejectsEmptySegment() {
      var caveat = new Caveat("my-api_capabilities", "a,,b");
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "a"))
              .build();

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              e -> {
                var ex = (MacaroonVerificationException) e;
                assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
              });
    }

    @Test
    @DisplayName("verify rejects trailing comma in caveat value")
    void verifyRejectsTrailingComma() {
      var caveat = new Caveat("my-api_capabilities", "a,b,");
      var context =
          L402VerificationContext.builder()
              .serviceName(SERVICE_NAME)
              .requestMetadata(Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "a"))
              .build();

      assertThatThrownBy(() -> verifier.verify(caveat, context))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              e -> {
                var ex = (MacaroonVerificationException) e;
                assertThat(ex.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
              });
    }

    @Test
    @DisplayName("isMoreRestrictive returns false when previous exceeds bounds")
    void isMoreRestrictiveRejectsOversizedPrevious() {
      var bounded = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
      String oversized =
          IntStream.rangeClosed(1, 51).mapToObj(i -> "cap" + i).collect(Collectors.joining(","));
      var previous = new Caveat("my-api_capabilities", oversized);
      var current = new Caveat("my-api_capabilities", "a");

      assertThat(bounded.isMoreRestrictive(previous, current)).isFalse();
    }

    @Test
    @DisplayName("isMoreRestrictive returns false when current exceeds bounds")
    void isMoreRestrictiveRejectsOversizedCurrent() {
      var bounded = new CapabilitiesCaveatVerifier(SERVICE_NAME, 50);
      String oversized =
          IntStream.rangeClosed(1, 51).mapToObj(i -> "cap" + i).collect(Collectors.joining(","));
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
