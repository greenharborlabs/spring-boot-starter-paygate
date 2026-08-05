package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("RouteCaveatVerifier")
class RouteCaveatVerifierTest {

  private RouteCaveatVerifier verifier;

  @BeforeEach
  void setUp() {
    verifier = new RouteCaveatVerifier(1);
  }

  @Test
  @DisplayName("getKey returns 'route'")
  void getKeyReturnsRoute() {
    assertThat(verifier.getKey()).isEqualTo("route");
  }

  @Test
  @DisplayName("constructor rejects a non-positive input bound")
  void constructorRejectsInvalidMaxValues() {
    assertThatThrownBy(() -> new RouteCaveatVerifier(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxValuesPerCaveat must be >= 1");
    assertThatThrownBy(() -> new RouteCaveatVerifier(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxValuesPerCaveat must be >= 1");
  }

  @Nested
  @DisplayName("exact route identity")
  class ExactRouteIdentity {

    @Test
    @DisplayName("accepts an exact canonical route")
    void acceptsExactCanonicalRoute() {
      var caveat = new Caveat("route", "/products/{id}");

      assertThatCode(() -> verifier.verify(caveat, contextWithRoute("/products/{id}")))
          .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "rejects {0} in the signed route")
    @MethodSource("whitespaceAlteredRoutes")
    @DisplayName("rejects whitespace-altered signed routes without disclosing route values")
    void rejectsWhitespaceAlteredSignedRoutes(String description, String caveatRoute) {
      var requestRoute = "/private/request-route-marker";

      assertThat(CaveatValues.splitBounded(caveatRoute, 1, "route")).containsExactly(requestRoute);
      assertThatThrownBy(
              () ->
                  verifier.verify(new Caveat("route", caveatRoute), contextWithRoute(requestRoute)))
          .isInstanceOf(MacaroonVerificationException.class)
          .satisfies(
              exception ->
                  assertThat(((MacaroonVerificationException) exception).getReason())
                      .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET))
          .hasMessageNotContaining(caveatRoute)
          .hasMessageNotContaining(requestRoute);
    }

    private static Stream<Arguments> whitespaceAlteredRoutes() {
      var canonicalRoute = "/private/request-route-marker";
      return Stream.of(
          Arguments.of("leading space", " " + canonicalRoute),
          Arguments.of("trailing space", canonicalRoute + " "),
          Arguments.of("leading tab", "\t" + canonicalRoute),
          Arguments.of("trailing newline", canonicalRoute + "\n"));
    }

    @Test
    @DisplayName("rejects a different canonical route")
    void rejectsDifferentCanonicalRoute() {
      var caveat = new Caveat("route", "/products/{id}");

      assertCaveatNotMet(() -> verifier.verify(caveat, contextWithRoute("/products/{productId}")));
    }

    @Test
    @DisplayName("comparison is case-sensitive and does not trim")
    void comparesCaseAndWhitespaceExactly() {
      var caveat = new Caveat("route", "/Products/{id}");

      assertCaveatNotMet(() -> verifier.verify(caveat, contextWithRoute("/products/{id}")));
      assertCaveatNotMet(() -> verifier.verify(caveat, contextWithRoute(" /Products/{id} ")));
    }

    @Test
    @DisplayName("wildcards, encoding, binary data, and trailing slash are never normalized")
    void matchesOnlyExactCanonicalTemplateIncludingWildcardsEncodingAndTrailingSlash() {
      assertExactOnly("/files/**/");
      assertExactOnly("/items/{id:[0-9]+}/");
      assertExactOnly("/encoded/%2F/%20/");
      assertExactOnly("/binary/\u0000\u0001\u007f/");

      assertCaveatNotMet(
          () ->
              verifier.verify(
                  new Caveat("route", "/encoded/%2F/%20/"), contextWithRoute("/encoded/// /")));
      assertCaveatNotMet(
          () -> verifier.verify(new Caveat("route", "/files/**/"), contextWithRoute("/files/**")));
    }

    @Test
    @DisplayName("repeated route restrictions are monotonic only when identical")
    void repeatedRoutesMustRemainIdentical() {
      var original = new Caveat("route", "/products/{id}");

      assertThat(verifier.isMoreRestrictive(original, new Caveat("route", "/products/{id}")))
          .isTrue();
      assertThat(verifier.isMoreRestrictive(original, new Caveat("route", "/orders/{id}")))
          .isFalse();
    }
  }

  @Nested
  @DisplayName("fail-closed inputs")
  class FailClosedInputs {

    @Test
    @DisplayName("missing or null request route rejects")
    void missingOrNullRequestRouteRejects() {
      assertCaveatNotMet(
          () -> verifier.verify(new Caveat("route", "/products"), contextWithMetadata(Map.of())));

      var metadataWithNullRoute = new HashMap<String, String>();
      metadataWithNullRoute.put(VerificationContextKeys.REQUEST_ROUTE, null);
      assertCaveatNotMet(
          () ->
              verifier.verify(
                  new Caveat("route", "/products"), contextWithMetadata(metadataWithNullRoute)));
    }

    @Test
    @DisplayName("blank request route rejects")
    void blankRequestRouteRejects() {
      assertCaveatNotMet(
          () -> verifier.verify(new Caveat("route", "/products"), contextWithRoute(" \t")));
    }

    @Test
    @DisplayName("blank caveat values are rejected")
    void blankCaveatValueRejects() {
      assertThatThrownBy(() -> new Caveat("route", " \t"))
          .isInstanceOf(IllegalArgumentException.class);
      assertCaveatNotMet(
          () -> verifier.verify(new Caveat("route", " , "), contextWithRoute(" , ")));
    }

    @Test
    @DisplayName("configured input bound rejects multi-value abuse")
    void configuredBoundRejectsMultiValueAbuse() {
      var multiValue = "/products/{id},/orders/{id}";

      assertCaveatNotMet(
          () -> verifier.verify(new Caveat("route", multiValue), contextWithRoute(multiValue)));
    }

    @Test
    @DisplayName("oversized value count rejects before matching a later value")
    void oversizedValueCountRejects() {
      var bounded = new RouteCaveatVerifier(2);
      var caveat = new Caveat("route", "/first,/second,/target");

      assertCaveatNotMet(() -> bounded.verify(caveat, contextWithRoute("/target")));
      assertThat(bounded.isMoreRestrictive(caveat, caveat)).isFalse();
    }

    @Test
    @DisplayName("verification failures do not disclose either route value")
    void failuresDoNotDiscloseRouteValues() {
      var caveatRoute = "/private/caveat-marker";
      var requestRoute = "/private/request-marker";

      assertThatThrownBy(
              () ->
                  verifier.verify(new Caveat("route", caveatRoute), contextWithRoute(requestRoute)))
          .isInstanceOf(MacaroonVerificationException.class)
          .hasMessageNotContaining(caveatRoute)
          .hasMessageNotContaining(requestRoute);
    }
  }

  private void assertExactOnly(String route) {
    var caveat = new Caveat("route", route);
    assertThatCode(() -> verifier.verify(caveat, contextWithRoute(route)))
        .doesNotThrowAnyException();
  }

  private static void assertCaveatNotMet(ThrowingOperation operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(MacaroonVerificationException.class)
        .extracting(e -> ((MacaroonVerificationException) e).getReason())
        .isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET);
  }

  private static L402VerificationContext contextWithRoute(String route) {
    return contextWithMetadata(Map.of(VerificationContextKeys.REQUEST_ROUTE, route));
  }

  private static L402VerificationContext contextWithMetadata(Map<String, String> metadata) {
    return L402VerificationContext.builder().requestMetadata(metadata).build();
  }

  @FunctionalInterface
  private interface ThrowingOperation {
    void run();
  }
}
