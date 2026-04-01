package com.greenharborlabs.paygate.protocol.l402;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.core.macaroon.L402VerificationContext;
import com.greenharborlabs.paygate.core.macaroon.MacaroonIdentifier;
import com.greenharborlabs.paygate.core.macaroon.MacaroonMinter;
import com.greenharborlabs.paygate.core.macaroon.MacaroonSerializer;
import com.greenharborlabs.paygate.core.macaroon.VerificationContextKeys;
import com.greenharborlabs.paygate.core.protocol.ErrorCode;
import com.greenharborlabs.paygate.core.protocol.L402Exception;
import com.greenharborlabs.paygate.core.protocol.L402Validator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class L402ProtocolTest {

  private static final String SERVICE_NAME = "test-service";
  private static final HexFormat HEX = HexFormat.of();

  private L402Validator validator;
  private L402Protocol protocol;

  @BeforeEach
  void setUp() {
    validator = mock(L402Validator.class);
    protocol = new L402Protocol(validator, SERVICE_NAME);
  }

  @Test
  void scheme_returnsL402() {
    assertThat(protocol.scheme()).isEqualTo("L402");
  }

  @Test
  void constructor_rejectsNullValidator() {
    assertThatThrownBy(() -> new L402Protocol(null, SERVICE_NAME))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("validator");
  }

  @Test
  void constructor_rejectsNullServiceName() {
    assertThatThrownBy(() -> new L402Protocol(validator, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("serviceName");
  }

  @Nested
  class CanHandle {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "L402 abc123:def456",
          "LSAT abc123:def456",
          "L402 x",
          "LSAT x",
          "L402 ",
          "LSAT "
        })
    void trueForL402OrLsatPrefix(String header) {
      assertThat(protocol.canHandle(header)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    void falseForNull(String header) {
      assertThat(protocol.canHandle(header)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "L402", "LSAT", "Bea", "abcd"})
    void falseForShortStrings(String header) {
      assertThat(protocol.canHandle(header)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "Payment proof=abc",
          "Bearer token123",
          "Basic dXNlcjpwYXNz",
          "L4O2 something",
          "l402 abc123:def456",
          "lsat abc123:def456",
          "l402 x",
          "lsat x"
        })
    void falseForNonL402Schemes(String header) {
      assertThat(protocol.canHandle(header)).isFalse();
    }
  }

  @Nested
  class ParseCredential {

    @Test
    void parsesValidL402Header() {
      String authHeader = buildValidAuthHeader("L402");

      PaymentCredential credential = protocol.parseCredential(authHeader);

      assertThat(credential.sourceProtocolScheme()).isEqualTo("L402");
      assertThat(credential.tokenId()).isNotEmpty();
      assertThat(credential.paymentHash()).hasSize(32);
      assertThat(credential.preimage()).hasSize(32);
      assertThat(credential.source()).isNull();
      assertThat(credential.metadata()).isInstanceOf(L402Metadata.class);
    }

    @Test
    void parsesValidLsatHeader() {
      String authHeader = buildValidAuthHeader("LSAT");

      PaymentCredential credential = protocol.parseCredential(authHeader);

      assertThat(credential.sourceProtocolScheme()).isEqualTo("L402");
      assertThat(credential.tokenId()).isNotEmpty();
    }

    @Test
    void metadataContainsMacaroonAndRawHeader() {
      String authHeader = buildValidAuthHeader("L402");

      PaymentCredential credential = protocol.parseCredential(authHeader);
      L402Metadata metadata = (L402Metadata) credential.metadata();

      assertThat(metadata.macaroon()).isNotNull();
      assertThat(metadata.additionalMacaroons()).isEmpty();
      assertThat(metadata.rawAuthorizationHeader()).isEqualTo(authHeader);
    }

    @Test
    void tokenIdMatchesExpectedHex() {
      byte[] tokenId = new byte[32];
      Arrays.fill(tokenId, (byte) 0xAB);
      String authHeader = buildAuthHeaderWithTokenId("L402", tokenId);

      PaymentCredential credential = protocol.parseCredential(authHeader);

      assertThat(credential.tokenId()).isEqualTo(HEX.formatHex(tokenId));
    }

    @Test
    void throwsPaymentValidationExceptionForMalformedHeader() {
      assertThatThrownBy(() -> protocol.parseCredential("L402 not-valid"))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              ex -> {
                PaymentValidationException pve = (PaymentValidationException) ex;
                assertThat(pve.getErrorCode())
                    .isEqualTo(PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL);
              });
    }

    @Test
    void throwsPaymentValidationExceptionForNullHeader() {
      assertThatThrownBy(() -> protocol.parseCredential(null))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              ex -> {
                PaymentValidationException pve = (PaymentValidationException) ex;
                assertThat(pve.getErrorCode())
                    .isEqualTo(PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL);
              });
    }

    @Test
    void throwsPaymentValidationExceptionForEmptyHeader() {
      assertThatThrownBy(() -> protocol.parseCredential(""))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              ex -> {
                PaymentValidationException pve = (PaymentValidationException) ex;
                assertThat(pve.getErrorCode())
                    .isEqualTo(PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL);
              });
    }
  }

  @Nested
  class FormatChallenge {

    @Test
    void producesCorrectWwwAuthenticateHeaderFormat() {
      byte[] rootKey = new byte[32];
      Arrays.fill(rootKey, (byte) 0x01);
      byte[] paymentHash = new byte[32];
      Arrays.fill(paymentHash, (byte) 0x02);
      byte[] tokenId = new byte[32];
      Arrays.fill(tokenId, (byte) 0x03);
      String tokenIdHex = HEX.formatHex(tokenId);
      String bolt11 = "lnbc10n1p0abcdef";

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              bolt11,
              10L,
              "test description",
              SERVICE_NAME,
              3600L,
              "read",
              rootKey,
              null,
              null);

      ChallengeResponse response = protocol.formatChallenge(context);

      assertThat(response.protocolScheme()).isEqualTo("L402");
      assertThat(response.bodyData()).isNull();

      String header = response.wwwAuthenticateHeader();
      assertThat(header).startsWith("L402 version=\"0\", token=\"");
      assertThat(header).contains("\", macaroon=\"");
      assertThat(header).contains("\", invoice=\"" + bolt11 + "\"");
    }

    @Test
    void macaroonInHeaderIsValidBase64() {
      byte[] rootKey = new byte[32];
      Arrays.fill(rootKey, (byte) 0x11);
      byte[] paymentHash = new byte[32];
      byte[] tokenId = new byte[32];
      String tokenIdHex = HEX.formatHex(tokenId);

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              "lnbc1invoice",
              5L,
              null,
              SERVICE_NAME,
              600L,
              null,
              rootKey,
              null,
              null);

      ChallengeResponse response = protocol.formatChallenge(context);
      String header = response.wwwAuthenticateHeader();

      // Extract macaroon base64 from: token="<base64>", macaroon="<base64>"
      String tokenPrefix = "token=\"";
      int tokenStart = header.indexOf(tokenPrefix) + tokenPrefix.length();
      int tokenEnd = header.indexOf("\"", tokenStart);
      String macaroonBase64 = header.substring(tokenStart, tokenEnd);

      // Should decode without error
      byte[] decoded = Base64.getDecoder().decode(macaroonBase64);
      assertThat(decoded).isNotEmpty();
    }

    @Test
    void challengeIncludesCapabilityCaveatWhenPresent() {
      byte[] rootKey = new byte[32];
      byte[] paymentHash = new byte[32];
      byte[] tokenId = new byte[32];
      String tokenIdHex = HEX.formatHex(tokenId);

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              "lnbc1invoice",
              1L,
              null,
              SERVICE_NAME,
              3600L,
              "write",
              rootKey,
              null,
              null);

      ChallengeResponse response = protocol.formatChallenge(context);

      // The macaroon should contain the capability caveat — verify by deserializing
      String header = response.wwwAuthenticateHeader();
      String macaroonPrefix = "macaroon=\"";
      int macStart = header.indexOf(macaroonPrefix) + macaroonPrefix.length();
      int macEnd = header.indexOf("\"", macStart);
      String base64Mac = header.substring(macStart, macEnd);
      byte[] macBytes = Base64.getDecoder().decode(base64Mac);
      var macaroon = MacaroonSerializer.deserializeV2(macBytes);

      assertThat(macaroon.caveats())
          .anySatisfy(
              caveat -> {
                assertThat(caveat.key()).isEqualTo(SERVICE_NAME + "_capabilities");
                assertThat(caveat.value()).isEqualTo("write");
              });
    }

    @Test
    void challengeOmitsCapabilityCaveatWhenNull() {
      byte[] rootKey = new byte[32];
      byte[] paymentHash = new byte[32];
      byte[] tokenId = new byte[32];
      String tokenIdHex = HEX.formatHex(tokenId);

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              "lnbc1invoice",
              1L,
              null,
              SERVICE_NAME,
              3600L,
              null,
              rootKey,
              null,
              null);

      ChallengeResponse response = protocol.formatChallenge(context);
      String header = response.wwwAuthenticateHeader();
      String macaroonPrefix = "macaroon=\"";
      int macStart = header.indexOf(macaroonPrefix) + macaroonPrefix.length();
      int macEnd = header.indexOf("\"", macStart);
      byte[] macBytes = Base64.getDecoder().decode(header.substring(macStart, macEnd));
      var macaroon = MacaroonSerializer.deserializeV2(macBytes);

      assertThat(macaroon.caveats())
          .noneSatisfy(
              caveat -> assertThat(caveat.key()).isEqualTo(SERVICE_NAME + "_capabilities"));
    }

    @Test
    void challengeIncludesServicesCaveat() {
      byte[] rootKey = new byte[32];
      byte[] paymentHash = new byte[32];
      byte[] tokenId = new byte[32];
      String tokenIdHex = HEX.formatHex(tokenId);

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              "lnbc1invoice",
              1L,
              null,
              SERVICE_NAME,
              3600L,
              "read",
              rootKey,
              null,
              null);

      ChallengeResponse response = protocol.formatChallenge(context);
      String header = response.wwwAuthenticateHeader();
      String macaroonPrefix = "macaroon=\"";
      int macStart = header.indexOf(macaroonPrefix) + macaroonPrefix.length();
      int macEnd = header.indexOf("\"", macStart);
      byte[] macBytes = Base64.getDecoder().decode(header.substring(macStart, macEnd));
      var macaroon = MacaroonSerializer.deserializeV2(macBytes);

      assertThat(macaroon.caveats())
          .anySatisfy(
              caveat -> {
                assertThat(caveat.key()).isEqualTo("services");
                assertThat(caveat.value()).isEqualTo(SERVICE_NAME + ":0");
              });
    }

    @Test
    void challengeIncludesValidUntilCaveat() {
      byte[] rootKey = new byte[32];
      byte[] paymentHash = new byte[32];
      byte[] tokenId = new byte[32];
      String tokenIdHex = HEX.formatHex(tokenId);

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              "lnbc1invoice",
              1L,
              null,
              SERVICE_NAME,
              7200L,
              "read",
              rootKey,
              null,
              null);

      ChallengeResponse response = protocol.formatChallenge(context);
      String header = response.wwwAuthenticateHeader();
      String macaroonPrefix = "macaroon=\"";
      int macStart = header.indexOf(macaroonPrefix) + macaroonPrefix.length();
      int macEnd = header.indexOf("\"", macStart);
      byte[] macBytes = Base64.getDecoder().decode(header.substring(macStart, macEnd));
      var macaroon = MacaroonSerializer.deserializeV2(macBytes);

      assertThat(macaroon.caveats())
          .anySatisfy(
              caveat -> {
                assertThat(caveat.key()).isEqualTo(SERVICE_NAME + "_valid_until");
                // Value should be a future epoch second
                long epoch = Long.parseLong(caveat.value());
                assertThat(epoch).isGreaterThan(System.currentTimeMillis() / 1000);
              });
    }

    @Test
    void rejectsBolt11WithControlCharacters() {
      byte[] rootKey = new byte[32];
      byte[] paymentHash = new byte[32];
      byte[] tokenId = new byte[32];
      String tokenIdHex = HEX.formatHex(tokenId);

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              "lnbc\r\ninjection",
              1L,
              null,
              SERVICE_NAME,
              3600L,
              null,
              rootKey,
              null,
              null);

      assertThatThrownBy(() -> protocol.formatChallenge(context))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("illegal character");
    }

    @Test
    void tokenAndMacaroonFieldsAreIdentical() {
      byte[] rootKey = new byte[32];
      Arrays.fill(rootKey, (byte) 0x55);
      byte[] paymentHash = new byte[32];
      byte[] tokenId = new byte[32];
      String tokenIdHex = HEX.formatHex(tokenId);

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              "lnbc1invoice",
              1L,
              null,
              SERVICE_NAME,
              3600L,
              null,
              rootKey,
              null,
              null);

      ChallengeResponse response = protocol.formatChallenge(context);
      String header = response.wwwAuthenticateHeader();

      // Extract token and macaroon values
      String tokenVal = extractQuotedValue(header, "token");
      String macaroonVal = extractQuotedValue(header, "macaroon");

      assertThat(tokenVal).isEqualTo(macaroonVal);
    }
  }

  @Nested
  class Validate {

    @Test
    void delegatesToL402Validator() {
      String authHeader = buildValidAuthHeader("L402");
      PaymentCredential credential = protocol.parseCredential(authHeader);
      Map<String, String> requestContext = Map.of("path", "/api/data", "method", "GET");

      when(validator.validate(eq(authHeader), any())).thenReturn(null);

      protocol.validate(credential, requestContext);

      verify(validator).validate(eq(authHeader), any());
    }

    @Test
    void passesRequestContextToVerificationContext() {
      String authHeader = buildValidAuthHeader("L402");
      PaymentCredential credential = protocol.parseCredential(authHeader);
      Map<String, String> requestContext = Map.of("path", "/api/resource", "method", "POST");

      // Use ArgumentCaptor to inspect the L402VerificationContext
      ArgumentCaptor<com.greenharborlabs.paygate.core.macaroon.L402VerificationContext>
          contextCaptor =
              ArgumentCaptor.forClass(
                  com.greenharborlabs.paygate.core.macaroon.L402VerificationContext.class);

      when(validator.validate(eq(authHeader), contextCaptor.capture())).thenReturn(null);

      protocol.validate(credential, requestContext);

      var capturedContext = contextCaptor.getValue();
      assertThat(capturedContext.getServiceName()).isEqualTo(SERVICE_NAME);
      assertThat(capturedContext.getRequestMetadata()).containsAllEntriesOf(requestContext);
      assertThat(capturedContext.getCurrentTime()).isNotNull();
    }

    @Test
    void rejectsNullCredential() {
      assertThatThrownBy(() -> protocol.validate(null, Map.of()))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("credential");
    }

    @Test
    void rejectsNullRequestContext() {
      String authHeader = buildValidAuthHeader("L402");
      PaymentCredential credential = protocol.parseCredential(authHeader);

      assertThatThrownBy(() -> protocol.validate(credential, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("requestContext");
    }

    @Test
    void rejectsCredentialWithNonL402Metadata() {
      // Create a PaymentCredential with a non-L402 metadata type
      PaymentCredential badCredential =
          new PaymentCredential(
              new byte[32], new byte[32], "tok123", "L402", null, new NonL402Metadata());

      assertThatThrownBy(() -> protocol.validate(badCredential, Map.of()))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              ex -> {
                PaymentValidationException pve = (PaymentValidationException) ex;
                assertThat(pve.getErrorCode())
                    .isEqualTo(PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL);
                assertThat(pve.getTokenId()).isEqualTo("tok123");
              });
    }
  }

  @Nested
  class ErrorCodeMapping {

    @Test
    void malformedHeaderMapsTOMalformedCredential() {
      verifyErrorMapping(
          ErrorCode.MALFORMED_HEADER, PaymentValidationException.ErrorCode.MALFORMED_CREDENTIAL);
    }

    @Test
    void invalidPreimageMapsToInvalidPreimage() {
      verifyErrorMapping(
          ErrorCode.INVALID_PREIMAGE, PaymentValidationException.ErrorCode.INVALID_PREIMAGE);
    }

    @Test
    void expiredCredentialMapsToExpiredCredential() {
      verifyErrorMapping(
          ErrorCode.EXPIRED_CREDENTIAL, PaymentValidationException.ErrorCode.EXPIRED_CREDENTIAL);
    }

    @Test
    void invalidMacaroonMapsToInvalidChallengeBinding() {
      verifyErrorMapping(
          ErrorCode.INVALID_MACAROON,
          PaymentValidationException.ErrorCode.INVALID_CHALLENGE_BINDING);
    }

    @Test
    void invalidServiceMapsToInvalidChallengeBinding() {
      verifyErrorMapping(
          ErrorCode.INVALID_SERVICE,
          PaymentValidationException.ErrorCode.INVALID_CHALLENGE_BINDING);
    }

    @Test
    void revokedCredentialMapsToInvalidChallengeBinding() {
      verifyErrorMapping(
          ErrorCode.REVOKED_CREDENTIAL,
          PaymentValidationException.ErrorCode.INVALID_CHALLENGE_BINDING);
    }

    @Test
    void lightningUnavailableMapsToServiceUnavailable() {
      verifyErrorMapping(
          ErrorCode.LIGHTNING_UNAVAILABLE,
          PaymentValidationException.ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void errorMessageAndTokenIdArePreserved() {
      String authHeader = buildValidAuthHeader("L402");
      PaymentCredential credential = protocol.parseCredential(authHeader);

      doThrow(new L402Exception(ErrorCode.INVALID_PREIMAGE, "bad preimage", "tok-42"))
          .when(validator)
          .validate(eq(authHeader), any());

      assertThatThrownBy(() -> protocol.validate(credential, Map.of()))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              ex -> {
                PaymentValidationException pve = (PaymentValidationException) ex;
                assertThat(pve.getMessage()).isEqualTo("bad preimage");
                assertThat(pve.getTokenId()).isEqualTo("tok-42");
              });
    }

    private void verifyErrorMapping(
        ErrorCode l402Code, PaymentValidationException.ErrorCode expectedCode) {
      String authHeader = buildValidAuthHeader("L402");
      PaymentCredential credential = protocol.parseCredential(authHeader);

      doThrow(new L402Exception(l402Code, "test error", "tok-1"))
          .when(validator)
          .validate(eq(authHeader), any());

      assertThatThrownBy(() -> protocol.validate(credential, Map.of()))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              ex -> {
                PaymentValidationException pve = (PaymentValidationException) ex;
                assertThat(pve.getErrorCode()).isEqualTo(expectedCode);
              });
    }
  }

  @Nested
  class SanitizeBolt11ForHeader {

    @Test
    void returnsEmptyStringForNull() {
      assertThat(L402Protocol.sanitizeBolt11ForHeader(null)).isEqualTo("");
    }

    @Test
    void returnsEmptyStringForEmpty() {
      assertThat(L402Protocol.sanitizeBolt11ForHeader("")).isEqualTo("");
    }

    @Test
    void passesThroughValidBolt11() {
      assertThat(L402Protocol.sanitizeBolt11ForHeader("lnbc10n1p0valid"))
          .isEqualTo("lnbc10n1p0valid");
    }

    @Test
    void rejectsDelCharacter() {
      assertThatThrownBy(() -> L402Protocol.sanitizeBolt11ForHeader("lnbc\u007F"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("illegal character");
    }

    @Test
    void rejectsDoubleQuote() {
      assertThatThrownBy(() -> L402Protocol.sanitizeBolt11ForHeader("lnbc\"injection"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("illegal character");
    }

    @Test
    void rejectsNullByte() {
      assertThatThrownBy(() -> L402Protocol.sanitizeBolt11ForHeader("\u0000evil"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("illegal character");
    }

    @Test
    void rejectsTabCharacter() {
      assertThatThrownBy(() -> L402Protocol.sanitizeBolt11ForHeader("lnbc\tinjection"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("illegal character");
    }
  }

  @Nested
  class FormatChallengeBlankCapability {

    @Test
    void omitsCapabilityCaveatWhenCapabilityIsBlank() {
      byte[] rootKey = new byte[32];
      byte[] paymentHash = new byte[32];
      byte[] tokenId = new byte[32];
      String tokenIdHex = HEX.formatHex(tokenId);

      ChallengeContext context =
          new ChallengeContext(
              paymentHash,
              tokenIdHex,
              "lnbc1invoice",
              1L,
              null,
              SERVICE_NAME,
              3600L,
              "  ",
              rootKey,
              null,
              null);

      ChallengeResponse response = protocol.formatChallenge(context);
      String header = response.wwwAuthenticateHeader();
      String macaroonPrefix = "macaroon=\"";
      int macStart = header.indexOf(macaroonPrefix) + macaroonPrefix.length();
      int macEnd = header.indexOf("\"", macStart);
      byte[] macBytes = Base64.getDecoder().decode(header.substring(macStart, macEnd));
      var macaroon = MacaroonSerializer.deserializeV2(macBytes);

      assertThat(macaroon.caveats())
          .noneSatisfy(caveat -> assertThat(caveat.key()).contains("_capabilities"));
    }
  }

  @Nested
  class ValidateRequestedCapability {

    @Test
    void passesRequestedCapabilityToVerificationContext() {
      String authHeader = buildValidAuthHeader("L402");
      PaymentCredential credential = protocol.parseCredential(authHeader);
      Map<String, String> requestContext =
          Map.of(VerificationContextKeys.REQUESTED_CAPABILITY, "write");

      ArgumentCaptor<L402VerificationContext> contextCaptor =
          ArgumentCaptor.forClass(L402VerificationContext.class);

      when(validator.validate(eq(authHeader), contextCaptor.capture())).thenReturn(null);

      protocol.validate(credential, requestContext);

      var capturedContext = contextCaptor.getValue();
      assertThat(
              capturedContext
                  .getRequestMetadata()
                  .get(VerificationContextKeys.REQUESTED_CAPABILITY))
          .isEqualTo("write");
    }
  }

  // --- Test helpers ---

  /** Builds a valid L402/LSAT Authorization header using real macaroon infrastructure. */
  private String buildValidAuthHeader(String scheme) {
    byte[] preimage = new byte[32];
    Arrays.fill(preimage, (byte) 0x42);
    byte[] paymentHash = sha256(preimage);
    byte[] tokenId = new byte[32];
    Arrays.fill(tokenId, (byte) 0x07);
    return buildAuthHeader(scheme, preimage, paymentHash, tokenId);
  }

  private String buildAuthHeaderWithTokenId(String scheme, byte[] tokenId) {
    byte[] preimage = new byte[32];
    Arrays.fill(preimage, (byte) 0x42);
    byte[] paymentHash = sha256(preimage);
    return buildAuthHeader(scheme, preimage, paymentHash, tokenId);
  }

  private String buildAuthHeader(
      String scheme, byte[] preimage, byte[] paymentHash, byte[] tokenId) {
    byte[] rootKey = new byte[32];
    Arrays.fill(rootKey, (byte) 0xFF);

    MacaroonIdentifier id = new MacaroonIdentifier(0, paymentHash, tokenId);
    var macaroon = MacaroonMinter.mint(rootKey, id, null, List.of());
    byte[] serialized = MacaroonSerializer.serializeV2(macaroon);
    String base64 = Base64.getEncoder().encodeToString(serialized);
    String preimageHex = HEX.formatHex(preimage);

    return scheme + " " + base64 + ":" + preimageHex;
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }

  private static String extractQuotedValue(String header, String key) {
    String prefix = key + "=\"";
    int start = header.indexOf(prefix) + prefix.length();
    int end = header.indexOf("\"", start);
    return header.substring(start, end);
  }

  /** Stub metadata type used to test rejection of non-L402 metadata in validate(). */
  private record NonL402Metadata() implements com.greenharborlabs.paygate.api.ProtocolMetadata {}
}
