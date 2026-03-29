package com.greenharborlabs.paygate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChallengeResponseTest {

  private static final String VALID_HEADER = "L402 macaroon=\"abc\", invoice=\"lnbc10u\"";
  private static final String VALID_SCHEME = "L402";

  private ChallengeResponse validResponse() {
    return new ChallengeResponse(VALID_HEADER, VALID_SCHEME, Map.of("invoice", "lnbc10u1p0test"));
  }

  // --- Valid construction ---

  @Test
  void constructWithAllValidFields() {
    var resp = validResponse();

    assertThat(resp.wwwAuthenticateHeader()).isEqualTo(VALID_HEADER);
    assertThat(resp.protocolScheme()).isEqualTo(VALID_SCHEME);
    assertThat(resp.bodyData()).containsEntry("invoice", "lnbc10u1p0test");
  }

  @Test
  void constructWithNullBodyDataIsAllowed() {
    var resp = new ChallengeResponse(VALID_HEADER, VALID_SCHEME, null);

    assertThat(resp.bodyData()).isNull();
  }

  // --- Null validation on required fields ---

  @Test
  void constructWithNullWwwAuthenticateHeaderThrows() {
    assertThatThrownBy(() -> new ChallengeResponse(null, VALID_SCHEME, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("wwwAuthenticateHeader");
  }

  @Test
  void constructWithNullProtocolSchemeThrows() {
    assertThatThrownBy(() -> new ChallengeResponse(VALID_HEADER, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("protocolScheme");
  }

  // --- bodyData defensive copy and immutability ---

  @Test
  void bodyDataIsImmutableAfterConstruction() {
    var resp = validResponse();

    assertThatThrownBy(() -> resp.bodyData().put("extra", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void bodyDataIsDefensivelyCopiedFromInput() {
    var mutableMap = new HashMap<String, Object>();
    mutableMap.put("key1", "value1");

    var resp = new ChallengeResponse(VALID_HEADER, VALID_SCHEME, mutableMap);

    mutableMap.put("key2", "value2");

    assertThat(resp.bodyData()).hasSize(1);
    assertThat(resp.bodyData()).containsOnlyKeys("key1");
  }

  @Test
  void bodyDataRemoveThrowsUnsupportedOperationException() {
    var resp = validResponse();

    assertThatThrownBy(() -> resp.bodyData().remove("invoice"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // --- Round-trip field access ---

  @Test
  void fieldsRoundTripCorrectly() {
    var bodyData = Map.<String, Object>of("method", "lightning", "amount", 100);
    var resp = new ChallengeResponse("Payment challenge=\"xyz\"", "Payment", bodyData);

    assertThat(resp.wwwAuthenticateHeader()).isEqualTo("Payment challenge=\"xyz\"");
    assertThat(resp.protocolScheme()).isEqualTo("Payment");
    assertThat(resp.bodyData()).containsEntry("method", "lightning");
    assertThat(resp.bodyData()).containsEntry("amount", 100);
  }
}
