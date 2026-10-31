package com.greenharborlabs.paygate.core.macaroon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class IssuerCaveatPrefixTest {

  private static final String SERVICE = "svc";
  private static final Caveat SERVICES = new Caveat("services", "svc:0");
  private static final Caveat ROUTE = new Caveat("route", "/a");
  private static final Caveat METHOD = new Caveat("method", "GET");
  private static final Caveat CAPABILITIES = new Caveat("svc_capabilities", "~");
  private static final Caveat VALID_UNTIL = new Caveat("svc_valid_until", "4102444800");

  @Test
  void prefixEndsAtFirstValidUntil() {
    Caveat appended = new Caveat("svc_price_sats", "5");
    List<Caveat> caveats =
        List.of(SERVICES, ROUTE, VALID_UNTIL, appended, new Caveat("svc_valid_until", "1"));

    assertThat(IssuerCaveatPrefix.of(caveats, SERVICE)).containsExactly(SERVICES, ROUTE);
  }

  @Test
  void paddedValidUntilKeyEndsThePrefix() {
    List<Caveat> caveats = List.of(SERVICES, new Caveat(" svc_valid_until", "4102444800"), ROUTE);

    assertThat(IssuerCaveatPrefix.of(caveats, SERVICE)).containsExactly(SERVICES);
  }

  @Test
  void otherServiceValidUntilDoesNotEndThePrefix() {
    List<Caveat> caveats = List.of(SERVICES, new Caveat("other_valid_until", "1"), ROUTE);

    assertThat(IssuerCaveatPrefix.of(caveats, SERVICE)).isEmpty();
  }

  @Test
  void missingValidUntilYieldsNoIssuerEvidence() {
    assertThat(IssuerCaveatPrefix.of(List.of(SERVICES, ROUTE, METHOD), SERVICE)).isEmpty();
  }

  @Test
  void requireBoundariesAcceptsBuiltInOrder() {
    assertThatCode(
            () ->
                IssuerCaveatPrefix.requireBoundaries(
                    List.of(SERVICES, ROUTE, METHOD, CAPABILITIES, VALID_UNTIL), SERVICE))
        .doesNotThrowAnyException();
  }

  @Test
  void requireBoundariesRejectsBoundaryOnlyAfterValidUntil() {
    assertThatThrownBy(
            () ->
                IssuerCaveatPrefix.requireBoundaries(
                    List.of(SERVICES, ROUTE, METHOD, VALID_UNTIL, CAPABILITIES), SERVICE))
        .isInstanceOfSatisfying(
            MacaroonVerificationException.class,
            e -> assertThat(e.getReason()).isEqualTo(VerificationFailureReason.CAVEAT_NOT_MET));
  }

  @Test
  void requireBoundariesRejectsMissingValidUntil() {
    assertThatThrownBy(
            () ->
                IssuerCaveatPrefix.requireBoundaries(
                    List.of(SERVICES, ROUTE, METHOD, CAPABILITIES), SERVICE))
        .isInstanceOf(MacaroonVerificationException.class);
  }
}
