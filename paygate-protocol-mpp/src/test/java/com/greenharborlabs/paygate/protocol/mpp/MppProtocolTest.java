package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.ChallengeContext;
import com.greenharborlabs.paygate.api.ChallengeResponse;
import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentReceipt;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MppProtocolTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final byte[] SECRET_BYTES = new byte[32];

  static {
    // Fill with non-zero pattern for realistic HMAC
    for (int i = 0; i < SECRET_BYTES.length; i++) {
      SECRET_BYTES[i] = (byte) (i + 1);
    }
  }

  /** Creates a fresh SensitiveBytes wrapping a clone of SECRET_BYTES (safe for repeated use). */
  private static SensitiveBytes secret() {
    return new SensitiveBytes(SECRET_BYTES.clone());
  }

  private static final String REALM = "test-service";
  private static final String BOLT11 = "lnbc100n1p0test";
  private static final long PRICE_SATS = 100;
  private static final long TIMEOUT_SECONDS = 3600;

  // A deterministic 32-byte preimage
  private static final byte[] PREIMAGE = new byte[32];

  static {
    for (int i = 0; i < PREIMAGE.length; i++) {
      PREIMAGE[i] = (byte) i;
    }
  }

  private static final byte[] PAYMENT_HASH = sha256(PREIMAGE);
  private static final String PREIMAGE_HEX = HEX.formatHex(PREIMAGE);
  private static final String REQUEST_DIGEST = "sha-256=:dGVzdC1kaWdlc3Q=:";

  private MppProtocol protocol;

  @BeforeEach
  void setUp() {
    protocol = new MppProtocol(secret());
  }

  // --- Constructor ---

  @Nested
  class ConstructorTests {

    @Test
    void rejectsNullSecret() {
      assertThatThrownBy(() -> new MppProtocol(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("challengeBindingSecret must not be null");
    }

    @Test
    void rejectsShortSecret() {
      SensitiveBytes tooShort = new SensitiveBytes(new byte[31]);
      assertThatThrownBy(() -> new MppProtocol(tooShort))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void rejectsEmptySecret() {
      // SensitiveBytes itself rejects zero-length arrays
      assertThatThrownBy(() -> new SensitiveBytes(new byte[0]))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be empty");
    }

    @Test
    void acceptsExactly32ByteSecret() {
      MppProtocol p = new MppProtocol(new SensitiveBytes(new byte[32]));
      assertThat(p.scheme()).isEqualTo("Payment");
    }

    @Test
    void acceptsLongerSecret() {
      MppProtocol p = new MppProtocol(new SensitiveBytes(new byte[64]));
      assertThat(p.scheme()).isEqualTo("Payment");
    }

    @Test
    void rejectsNullLimits() {
      assertThatThrownBy(() -> new MppProtocol(secret(), null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("parserLimits must not be null");
    }

    @Test
    void acceptsCustomLimits() {
      MppParserLimits limits = new MppParserLimits(10, 16384, 64, 131_072);
      MppProtocol p = new MppProtocol(secret(), limits);
      assertThat(p.scheme()).isEqualTo("Payment");
    }

    @Test
    void singleArgConstructorUsesDefaults() {
      // Should not throw -- backward compatible
      MppProtocol p = new MppProtocol(secret());
      assertThat(p.scheme()).isEqualTo("Payment");
    }
  }

  // --- scheme() ---

  @Test
  void schemeReturnsPayment() {
    assertThat(protocol.scheme()).isEqualTo("Payment");
  }

  // --- canHandle() ---

  @Nested
  class CanHandleTests {

    @Test
    void trueForPaymentPrefix() {
      assertThat(protocol.canHandle("Payment eyJjaGFsbGVuZ2Ui")).isTrue();
    }

    @Test
    void trueForLowercasePrefix() {
      assertThat(protocol.canHandle("payment eyJjaGFsbGVuZ2Ui")).isTrue();
    }

    @Test
    void trueForMixedCasePrefix() {
      assertThat(protocol.canHandle("PAYMENT eyJjaGFsbGVuZ2Ui")).isTrue();
    }

    @Test
    void trueForPayMeNtMixedCase() {
      assertThat(protocol.canHandle("PaYmEnT something")).isTrue();
    }

    @Test
    void falseForL402Scheme() {
      assertThat(protocol.canHandle("L402 macaroon:preimage")).isFalse();
    }

    @Test
    void falseForBearerScheme() {
      assertThat(protocol.canHandle("Bearer token123")).isFalse();
    }

    @Test
    void falseForNull() {
      assertThat(protocol.canHandle(null)).isFalse();
    }

    @Test
    void falseForEmptyString() {
      assertThat(protocol.canHandle("")).isFalse();
    }

    @Test
    void falseForShortString() {
      assertThat(protocol.canHandle("Pay")).isFalse();
    }

    @Test
    void falseForExactSchemeWithoutSpace() {
      // "payment" is 7 chars, "payment " prefix is 8
      assertThat(protocol.canHandle("payment")).isFalse();
    }

    @Test
    void trueForSchemeWithMinimalPayload() {
      assertThat(protocol.canHandle("payment x")).isTrue();
    }
  }

  // --- formatChallenge() ---

  @Nested
  class FormatChallengeTests {

    @Test
    void producesValidWwwAuthenticateHeader() {
      ChallengeContext ctx = challengeContext("Access to API", null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      String header = response.wwwAuthenticateHeader();
      assertThat(header).startsWith("Payment ");
      assertThat(header).contains("id=\"");
      assertThat(header).contains("realm=\"" + REALM + "\"");
      assertThat(header).contains("method=\"lightning\"");
      assertThat(header).contains("intent=\"charge\"");
      assertThat(header).contains("request=\"");
      assertThat(header).contains("expires=\"");
    }

    @Test
    void includesDigestWhenPresent() {
      ChallengeContext ctx = challengeContextWithDigest("Access to API", null, REQUEST_DIGEST);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      String header = response.wwwAuthenticateHeader();
      assertThat(header).contains("digest=\"" + REQUEST_DIGEST + "\"");
      assertThat(response.bodyData()).containsEntry("digest", REQUEST_DIGEST);
    }

    @Test
    void protocolSchemeIsPayment() {
      ChallengeContext ctx = challengeContext("test", null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      assertThat(response.protocolScheme()).isEqualTo("Payment");
    }

    @Test
    void expiresIsRfc3339() {
      ChallengeContext ctx = challengeContext("test", null);
      Instant before = Instant.now().plusSeconds(TIMEOUT_SECONDS);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      Instant after = Instant.now().plusSeconds(TIMEOUT_SECONDS);

      // Extract expires from header
      String header = response.wwwAuthenticateHeader();
      String expires = extractParam(header, "expires");
      Instant expiresInstant = Instant.parse(expires);

      // Expires should be between before and after (within the timeout window)
      assertThat(expiresInstant).isAfterOrEqualTo(before.minusSeconds(1));
      assertThat(expiresInstant).isBeforeOrEqualTo(after.plusSeconds(1));
    }

    @Test
    void requestDecodesToValidJcsJson() {
      ChallengeContext ctx = challengeContext("Access to API", null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      String header = response.wwwAuthenticateHeader();
      String requestB64 = extractParam(header, "request");

      // Decode base64url-nopad
      byte[] decoded = Base64.getUrlDecoder().decode(requestB64);
      String json = new String(decoded, StandardCharsets.UTF_8);

      // JCS: keys sorted lexicographically, no whitespace
      assertThat(json).contains("\"amount\":\"" + PRICE_SATS + "\"");
      assertThat(json).contains("\"currency\":\"BTC\"");
      assertThat(json).contains("\"description\":\"Access to API\"");
      assertThat(json).contains("\"methodDetails\":");
      assertThat(json).contains("\"invoice\":\"" + BOLT11 + "\"");
      assertThat(json).contains("\"paymentHash\":\"" + HEX.formatHex(PAYMENT_HASH) + "\"");
      assertThat(json).contains("\"network\":\"mainnet\"");

      // Verify JCS ordering: amount < currency < description < methodDetails
      int amountIdx = json.indexOf("\"amount\"");
      int currencyIdx = json.indexOf("\"currency\"");
      int descIdx = json.indexOf("\"description\"");
      int methodDetailsIdx = json.indexOf("\"methodDetails\"");
      assertThat(amountIdx).isLessThan(currencyIdx);
      assertThat(currencyIdx).isLessThan(descIdx);
      assertThat(descIdx).isLessThan(methodDetailsIdx);
    }

    @Test
    void idIsValidHmac() {
      ChallengeContext ctx = challengeContext("test", null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      String header = response.wwwAuthenticateHeader();
      String id = extractParam(header, "id");

      // The ID should be base64url-nopad (decodable)
      byte[] idBytes = Base64.getUrlDecoder().decode(id);
      // HMAC-SHA256 produces 32 bytes
      assertThat(idBytes).hasSize(32);
    }

    @Test
    void includesDescriptionWhenPresent() {
      ChallengeContext ctx = challengeContext("Access to premium content", null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      assertThat(response.wwwAuthenticateHeader())
          .contains("description=\"Access to premium content\"");
    }

    @Test
    void omitsDescriptionWhenNull() {
      ChallengeContext ctx = challengeContext(null, null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      assertThat(response.wwwAuthenticateHeader()).doesNotContain("description=");
    }

    @Test
    void omitsDescriptionWhenEmpty() {
      ChallengeContext ctx = challengeContext("", null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      assertThat(response.wwwAuthenticateHeader()).doesNotContain("description=");
    }

    @Test
    void includesOpaqueWhenPresent() {
      Map<String, String> opaque = new LinkedHashMap<>();
      opaque.put("session", "abc123");
      opaque.put("tier", "premium");

      ChallengeContext ctx = challengeContext("test", opaque);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      String header = response.wwwAuthenticateHeader();
      assertThat(header).contains("opaque=\"");

      // Opaque should be base64url-nopad decodable to JCS JSON
      String opaqueB64 = extractParam(header, "opaque");
      byte[] decoded = Base64.getUrlDecoder().decode(opaqueB64);
      String json = new String(decoded, StandardCharsets.UTF_8);
      assertThat(json).contains("\"session\":\"abc123\"");
      assertThat(json).contains("\"tier\":\"premium\"");
    }

    @Test
    void omitsOpaqueWhenNull() {
      ChallengeContext ctx = challengeContext("test", null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      assertThat(response.wwwAuthenticateHeader()).doesNotContain("opaque=");
    }

    @Test
    void bodyDataContainsAllFields() {
      Map<String, String> opaque = Map.of("key", "val");
      ChallengeContext ctx = challengeContext("A description", opaque);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      Map<String, Object> body = response.bodyData();
      assertThat(body).containsKey("id");
      assertThat(body).containsEntry("realm", REALM);
      assertThat(body).containsEntry("method", "lightning");
      assertThat(body).containsEntry("intent", "charge");
      assertThat(body).containsKey("request");
      assertThat(body).containsKey("expires");
      assertThat(body).containsEntry("description", "A description");
      assertThat(body).containsKey("opaque");
    }

    @Test
    void rejectsBackslashInDescription() {
      ChallengeContext ctx = challengeContext("value with \\ backslash", null);

      assertThatThrownBy(() -> protocol.formatChallenge(ctx))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("illegal character");
    }

    @Test
    void bodyDataOmitsDescriptionWhenNull() {
      ChallengeContext ctx = challengeContext(null, null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      assertThat(response.bodyData()).doesNotContainKey("description");
    }

    @Test
    void bodyDataOmitsOpaqueWhenNull() {
      ChallengeContext ctx = challengeContext("test", null);

      ChallengeResponse response = protocol.formatChallenge(ctx);

      assertThat(response.bodyData()).doesNotContainKey("opaque");
    }
  }

  // --- parseCredential() ---

  @Nested
  class ParseCredentialTests {

    @Test
    void parsesValidLightningCredential() {
      String authHeader = buildValidAuthHeader("lightning");

      PaymentCredential cred = protocol.parseCredential(authHeader);

      assertThat(cred.tokenId()).isNotEmpty();
      assertThat(cred.sourceProtocolScheme()).isEqualTo("Payment");
      assertThat(cred.preimage()).isEqualTo(PREIMAGE);
      assertThat(cred.paymentHash()).isEqualTo(PAYMENT_HASH);
    }

    @Test
    void rejectsNonLightningMethod() {
      String authHeader = buildValidAuthHeader("bitcoin-onchain");

      assertThatThrownBy(() -> protocol.parseCredential(authHeader))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              e -> {
                PaymentValidationException pve = (PaymentValidationException) e;
                assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.METHOD_UNSUPPORTED);
                assertThat(pve.getMessage()).contains("Unsupported payment method");
              });
    }

    @Test
    void rejectsNullHeader() {
      assertThatThrownBy(() -> protocol.parseCredential(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  // --- validate() ---

  @Nested
  class ValidateTests {

    @Test
    void acceptsValidCredential() {
      PaymentCredential credential =
          buildValidCredentialForValidation(PREIMAGE, Instant.now().plusSeconds(3600));

      // Should not throw
      protocol.validate(credential, requestContextWithDigest(REQUEST_DIGEST));
    }

    @Test
    void rejectsBadPreimage() {
      // Use a different preimage that does not match the payment hash
      byte[] wrongPreimage = new byte[32];
      wrongPreimage[0] = (byte) 0xFF; // different from PREIMAGE

      // Build credential with wrong preimage but correct payment hash from PREIMAGE
      PaymentCredential credential =
          buildCredentialWithMismatchedPreimage(
              wrongPreimage, PAYMENT_HASH, Instant.now().plusSeconds(3600));

      assertThatThrownBy(() -> protocol.validate(credential, Map.of()))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              e -> {
                PaymentValidationException pve = (PaymentValidationException) e;
                assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
                assertThat(pve.getMessage()).contains("Preimage does not match payment hash");
              });
    }

    @Test
    void rejectsTamperedHmac() {
      // Build a credential with valid preimage but tampered HMAC ID
      String expires = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
      String requestB64 = buildRequestB64();

      // Use a different (wrong) ID
      String tamperedId =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(new byte[32]); // all zeros, wrong HMAC

      Map<String, String> echoedChallenge = new LinkedHashMap<>();
      echoedChallenge.put("id", tamperedId);
      echoedChallenge.put("realm", REALM);
      echoedChallenge.put("method", "lightning");
      echoedChallenge.put("intent", "charge");
      echoedChallenge.put("request", requestB64);
      echoedChallenge.put("expires", expires);
      echoedChallenge.put("digest", REQUEST_DIGEST);

      MppMetadata metadata = new MppMetadata(echoedChallenge, null, "{}");
      PaymentCredential credential =
          new PaymentCredential(PAYMENT_HASH, PREIMAGE, tamperedId, "Payment", null, metadata);

      assertThatThrownBy(
              () -> protocol.validate(credential, requestContextWithDigest(REQUEST_DIGEST)))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              e -> {
                PaymentValidationException pve = (PaymentValidationException) e;
                assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.INVALID_CHALLENGE_BINDING);
                assertThat(pve.getMessage()).contains("Challenge binding verification failed");
              });
    }

    @Test
    void rejectsExpiredChallenge() {
      // Build credential with an expires time in the past
      PaymentCredential credential =
          buildValidCredentialForValidation(PREIMAGE, Instant.now().minusSeconds(60));

      assertThatThrownBy(
              () -> protocol.validate(credential, requestContextWithDigest(REQUEST_DIGEST)))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              e -> {
                PaymentValidationException pve = (PaymentValidationException) e;
                assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.EXPIRED_CREDENTIAL);
                assertThat(pve.getMessage()).contains("expired");
              });
    }

    @Test
    void rejectsNullCredential() {
      assertThatThrownBy(() -> protocol.validate(null, Map.of()))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullRequestContext() {
      PaymentCredential credential =
          buildValidCredentialForValidation(PREIMAGE, Instant.now().plusSeconds(3600));

      assertThatThrownBy(() -> protocol.validate(credential, null))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsFabricatedPreimageProvesTautologyFixed() {
      // This test proves the tautological validation bug is fixed.
      // Under the OLD code, the parser computed paymentHash = sha256(preimage),
      // so validate() compared sha256(preimage) == sha256(preimage) -- always true,
      // meaning ANY preimage was accepted regardless of whether it matched the invoice.
      // Under the NEW code, paymentHash comes from the request field (the real invoice hash),
      // so sha256(fabricatedPreimage) != realPaymentHash --> rejected.
      byte[] fabricatedPreimage = new byte[32];
      Arrays.fill(fabricatedPreimage, (byte) 0xFF);

      // Build credential with fabricated preimage but REAL payment hash from request field
      PaymentCredential credential =
          buildCredentialWithMismatchedPreimage(
              fabricatedPreimage, PAYMENT_HASH, Instant.now().plusSeconds(3600));

      assertThatThrownBy(
              () -> protocol.validate(credential, requestContextWithDigest(REQUEST_DIGEST)))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              e -> {
                PaymentValidationException pve = (PaymentValidationException) e;
                assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
              });
    }

    @Test
    void securityOrderPreimageBeforeHmac() {
      // Bad preimage AND tampered HMAC -- should fail on preimage first
      byte[] wrongPreimage = new byte[32];
      wrongPreimage[0] = (byte) 0xFF;

      String expires = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
      String requestB64 = buildRequestB64();
      String tamperedId = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

      Map<String, String> echoedChallenge = new LinkedHashMap<>();
      echoedChallenge.put("id", tamperedId);
      echoedChallenge.put("realm", REALM);
      echoedChallenge.put("method", "lightning");
      echoedChallenge.put("intent", "charge");
      echoedChallenge.put("request", requestB64);
      echoedChallenge.put("expires", expires);
      echoedChallenge.put("digest", REQUEST_DIGEST);

      MppMetadata metadata = new MppMetadata(echoedChallenge, null, "{}");
      PaymentCredential credential =
          new PaymentCredential(PAYMENT_HASH, wrongPreimage, tamperedId, "Payment", null, metadata);

      assertThatThrownBy(
              () -> protocol.validate(credential, requestContextWithDigest(REQUEST_DIGEST)))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              e -> {
                PaymentValidationException pve = (PaymentValidationException) e;
                // Must be INVALID_PREIMAGE, not INVALID_CHALLENGE_BINDING
                assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.INVALID_PREIMAGE);
              });
    }

    @Test
    void rejectsWhenRequestContextDigestMissing() {
      PaymentCredential credential =
          buildValidCredentialForValidation(PREIMAGE, Instant.now().plusSeconds(3600));

      assertThatThrownBy(() -> protocol.validate(credential, Map.of()))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              e -> {
                PaymentValidationException pve = (PaymentValidationException) e;
                assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.INVALID_CHALLENGE_BINDING);
              });
    }

    @Test
    void rejectsWhenRequestContextDigestMismatchesChallengeDigest() {
      PaymentCredential credential =
          buildValidCredentialForValidation(PREIMAGE, Instant.now().plusSeconds(3600));

      assertThatThrownBy(
              () ->
                  protocol.validate(credential, requestContextWithDigest("sha-256=:bWlzbWF0Y2g=:")))
          .isInstanceOf(PaymentValidationException.class)
          .satisfies(
              e -> {
                PaymentValidationException pve = (PaymentValidationException) e;
                assertThat(pve.getErrorCode()).isEqualTo(ErrorCode.INVALID_CHALLENGE_BINDING);
              });
    }
  }

  // --- createReceipt() ---

  @Nested
  class CreateReceiptTests {

    @Test
    void returnsNonEmptyOptional() {
      PaymentCredential credential =
          buildValidCredentialForValidation(PREIMAGE, Instant.now().plusSeconds(3600));
      ChallengeContext ctx = challengeContext("test", null);

      Optional<PaymentReceipt> receipt = protocol.createReceipt(credential, ctx);

      assertThat(receipt).isPresent();
    }

    @Test
    void receiptHasCorrectFields() {
      PaymentCredential credential =
          buildValidCredentialForValidation(PREIMAGE, Instant.now().plusSeconds(3600));
      ChallengeContext ctx = challengeContext("test", null);

      PaymentReceipt receipt = protocol.createReceipt(credential, ctx).orElseThrow();

      assertThat(receipt.status()).isEqualTo("success");
      assertThat(receipt.method()).isEqualTo("lightning");
      assertThat(receipt.protocolScheme()).isEqualTo("Payment");
      assertThat(receipt.amountSats()).isEqualTo(PRICE_SATS);
      assertThat(receipt.challengeId()).isNotEmpty();
      assertThat(receipt.timestamp()).isNotEmpty();
      // Timestamp should be parseable as ISO instant
      Instant.parse(receipt.timestamp());
    }
  }

  // ---- Helper methods ----

  private ChallengeContext challengeContext(String description, Map<String, String> opaque) {
    return challengeContextWithDigest(description, opaque, null);
  }

  private ChallengeContext challengeContextWithDigest(
      String description, Map<String, String> opaque, String digest) {
    return new ChallengeContext(
        PAYMENT_HASH,
        "token-id-123",
        BOLT11,
        PRICE_SATS,
        description,
        REALM,
        TIMEOUT_SECONDS,
        null, // capability
        null, // rootKeyBytes
        opaque,
        digest // digest
        );
  }

  private static Map<String, String> requestContextWithDigest(String digest) {
    return Map.of("request.digest", digest);
  }

  /** Builds a complete "Payment <base64url>" authorization header with the given method. */
  private String buildValidAuthHeader(String method) {
    String requestB64 = buildRequestB64();
    String json =
        """
                {"challenge":{"id":"test-id","realm":"%s","method":"%s","intent":"charge","request":"%s","expires":"2099-12-31T23:59:59Z"},"payload":{"preimage":"%s"}}"""
            .formatted(REALM, method, requestB64, PREIMAGE_HEX);
    String blob =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    return "Payment " + blob;
  }

  /** Builds a request base64url-nopad string for use in challenge binding. */
  private static String buildRequestB64() {
    String paymentHashHex = HEX.formatHex(PAYMENT_HASH);
    var methodDetails = new LightningChargeRequest.MethodDetails(BOLT11, paymentHashHex, "mainnet");
    var chargeRequest =
        new LightningChargeRequest(String.valueOf(PRICE_SATS), "BTC", null, methodDetails);
    String jcs = JcsSerializer.serialize(chargeRequest.toJcsMap());
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(jcs.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Builds a valid PaymentCredential that will pass all validation checks. Uses the real HMAC from
   * MppChallengeBinding to create a correct ID.
   */
  private PaymentCredential buildValidCredentialForValidation(byte[] preimage, Instant expiresAt) {
    byte[] paymentHash = sha256(preimage);
    String expires = DateTimeFormatter.ISO_INSTANT.format(expiresAt);
    String requestB64 = buildRequestB64();

    String id =
        MppChallengeBinding.createId(
            REALM, "lightning", "charge", requestB64, expires, REQUEST_DIGEST, null, secret());

    Map<String, String> echoedChallenge = new LinkedHashMap<>();
    echoedChallenge.put("id", id);
    echoedChallenge.put("realm", REALM);
    echoedChallenge.put("method", "lightning");
    echoedChallenge.put("intent", "charge");
    echoedChallenge.put("request", requestB64);
    echoedChallenge.put("expires", expires);
    echoedChallenge.put("digest", REQUEST_DIGEST);

    MppMetadata metadata = new MppMetadata(echoedChallenge, null, "{}");
    return new PaymentCredential(paymentHash, preimage, id, "Payment", null, metadata);
  }

  /**
   * Builds a credential where the preimage does not match the payment hash, to test
   * INVALID_PREIMAGE detection.
   */
  private PaymentCredential buildCredentialWithMismatchedPreimage(
      byte[] wrongPreimage, byte[] paymentHash, Instant expiresAt) {
    String expires = DateTimeFormatter.ISO_INSTANT.format(expiresAt);
    String requestB64 = buildRequestB64();

    String id =
        MppChallengeBinding.createId(
            REALM, "lightning", "charge", requestB64, expires, REQUEST_DIGEST, null, secret());

    Map<String, String> echoedChallenge = new LinkedHashMap<>();
    echoedChallenge.put("id", id);
    echoedChallenge.put("realm", REALM);
    echoedChallenge.put("method", "lightning");
    echoedChallenge.put("intent", "charge");
    echoedChallenge.put("request", requestB64);
    echoedChallenge.put("expires", expires);
    echoedChallenge.put("digest", REQUEST_DIGEST);

    MppMetadata metadata = new MppMetadata(echoedChallenge, null, "{}");
    return new PaymentCredential(paymentHash, wrongPreimage, id, "Payment", null, metadata);
  }

  /**
   * Extracts a quoted parameter value from a WWW-Authenticate header string. E.g., from {@code
   * Payment id="abc", realm="test"} extracts "abc" for param "id".
   */
  private static String extractParam(String header, String param) {
    String search = param + "=\"";
    int start = header.indexOf(search);
    if (start < 0) {
      throw new AssertionError("Parameter '" + param + "' not found in header: " + header);
    }
    start += search.length();
    int end = header.indexOf('"', start);
    if (end < 0) {
      throw new AssertionError(
          "Unterminated quoted value for '" + param + "' in header: " + header);
    }
    return header.substring(start, end);
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }
}
