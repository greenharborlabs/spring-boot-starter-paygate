package com.greenharborlabs.paygate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentProtocol default methods")
class PaymentProtocolTest {

  @Test
  @DisplayName("validateAndResolveCapabilities delegates to validate and grants nothing")
  void defaultValidateAndResolveCapabilitiesGrantsNothing() {
    var protocol = new RecordingProtocol(null);
    var credential = credential();
    Map<String, String> context = Map.of("request.capability", "premium");

    assertThat(protocol.validateAndResolveCapabilities(credential, context)).isEmpty();
    assertThat(protocol.validatedContexts).containsExactly(context);
  }

  @Test
  @DisplayName("validateAndResolveCapabilities propagates validation failures")
  void defaultValidateAndResolveCapabilitiesPropagatesFailure() {
    var failure =
        new PaymentValidationException(PaymentValidationException.ErrorCode.INVALID, "invalid");
    var protocol = new RecordingProtocol(failure);

    assertThatThrownBy(() -> protocol.validateAndResolveCapabilities(credential(), Map.of()))
        .isSameAs(failure);
  }

  private static PaymentCredential credential() {
    return new PaymentCredential(
        new byte[32], new byte[32], "token", "Test", null, new ProtocolMetadata() {});
  }

  private static final class RecordingProtocol implements PaymentProtocol {
    private final PaymentValidationException failure;
    private final List<Map<String, String>> validatedContexts = new ArrayList<>();

    RecordingProtocol(PaymentValidationException failure) {
      this.failure = failure;
    }

    @Override
    public String scheme() {
      return "Test";
    }

    @Override
    public boolean canHandle(String authorizationHeader) {
      return false;
    }

    @Override
    public PaymentCredential parseCredential(String authorizationHeader) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ChallengeResponse formatChallenge(ChallengeContext context) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void validate(PaymentCredential credential, Map<String, String> requestContext) {
      validatedContexts.add(requestContext);
      if (failure != null) {
        throw failure;
      }
    }
  }
}
