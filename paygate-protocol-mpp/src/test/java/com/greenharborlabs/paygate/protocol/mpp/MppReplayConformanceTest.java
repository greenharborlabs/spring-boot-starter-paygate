package com.greenharborlabs.paygate.protocol.mpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.greenharborlabs.paygate.api.PaymentCredential;
import com.greenharborlabs.paygate.api.PaymentValidationException;
import com.greenharborlabs.paygate.api.PaymentValidationException.ErrorCode;
import com.greenharborlabs.paygate.api.crypto.SensitiveBytes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Protocol conformance: MPP replay and binding semantics")
class MppReplayConformanceTest {

  private static final HexFormat HEX = HexFormat.of();
  private static final byte[] SECRET_BYTES = new byte[32];
  private static final byte[] PREIMAGE = new byte[32];
  private static final String REALM = "api.example.com";
  private static final String REQUEST_DIGEST_A = "sha-256=:dGVzdC1kaWdlc3QtQQ==:";
  private static final String REQUEST_DIGEST_B = "sha-256=:dGVzdC1kaWdlc3QtQg==:";
  private static final String BOLT11 = "lnbc100n1p0test";
  private static final byte[] PAYMENT_HASH = sha256(PREIMAGE);

  static {
    for (int i = 0; i < SECRET_BYTES.length; i++) {
      SECRET_BYTES[i] = (byte) (i + 1);
    }
    for (int i = 0; i < PREIMAGE.length; i++) {
      PREIMAGE[i] = (byte) i;
    }
  }

  private MppProtocol protocol;

  @BeforeEach
  void setUp() {
    protocol = new MppProtocol(secret());
  }

  @Test
  void credentialRejectedWhenCanonicalRequestDigestDiffers() {
    PaymentCredential credential =
        buildValidCredentialForValidation(
            PREIMAGE, REALM, REQUEST_DIGEST_A, Instant.now().plusSeconds(3600));

    assertThatThrownBy(
            () -> protocol.validate(credential, requestContextWithDigest(REQUEST_DIGEST_B)))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e ->
                assertThat(((PaymentValidationException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CHALLENGE_BINDING));
  }

  @Test
  void credentialRejectedWhenEchoedRealmIsTampered() {
    PaymentCredential original =
        buildValidCredentialForValidation(
            PREIMAGE, REALM, REQUEST_DIGEST_A, Instant.now().plusSeconds(3600));
    MppMetadata metadata = (MppMetadata) original.metadata();
    Map<String, String> tampered = new LinkedHashMap<>(metadata.echoedChallenge());
    tampered.put("realm", "api.other.example.com");

    PaymentCredential tamperedCredential =
        new PaymentCredential(
            original.paymentHash(),
            original.preimage(),
            original.tokenId(),
            original.sourceProtocolScheme(),
            original.source(),
            new MppMetadata(tampered, metadata.source(), metadata.rawCredentialJson()));

    assertThatThrownBy(
            () -> protocol.validate(tamperedCredential, requestContextWithDigest(REQUEST_DIGEST_A)))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e ->
                assertThat(((PaymentValidationException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_CHALLENGE_BINDING));
  }

  @Test
  void credentialRejectedAfterExpiry() {
    PaymentCredential credential =
        buildValidCredentialForValidation(
            PREIMAGE, REALM, REQUEST_DIGEST_A, Instant.now().minusSeconds(1));

    assertThatThrownBy(
            () -> protocol.validate(credential, requestContextWithDigest(REQUEST_DIGEST_A)))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e ->
                assertThat(((PaymentValidationException) e).getErrorCode())
                    .isEqualTo(ErrorCode.EXPIRED_CREDENTIAL));
  }

  @Test
  void validationOrderPreventsOracleLeakage() {
    byte[] wrongPreimage = new byte[32];
    wrongPreimage[0] = (byte) 0xFF;
    String requestB64 = buildRequestB64();
    String expires = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(3600));
    String tamperedId = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    Map<String, String> echoedChallenge = new LinkedHashMap<>();
    echoedChallenge.put("id", tamperedId);
    echoedChallenge.put("realm", REALM);
    echoedChallenge.put("method", "lightning");
    echoedChallenge.put("intent", "charge");
    echoedChallenge.put("request", requestB64);
    echoedChallenge.put("expires", expires);
    echoedChallenge.put("digest", REQUEST_DIGEST_A);

    PaymentCredential credential =
        new PaymentCredential(
            PAYMENT_HASH,
            wrongPreimage,
            tamperedId,
            "Payment",
            null,
            new MppMetadata(echoedChallenge, null, "{}"));

    assertThatThrownBy(
            () -> protocol.validate(credential, requestContextWithDigest(REQUEST_DIGEST_A)))
        .isInstanceOf(PaymentValidationException.class)
        .satisfies(
            e ->
                assertThat(((PaymentValidationException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PREIMAGE));
  }

  @Test
  void successfulValidationIsIdempotent() {
    PaymentCredential credential =
        buildValidCredentialForValidation(
            PREIMAGE, REALM, REQUEST_DIGEST_A, Instant.now().plusSeconds(3600));
    Map<String, String> context = requestContextWithDigest(REQUEST_DIGEST_A);

    protocol.validate(credential, context);
    protocol.validate(credential, context);
  }

  private static SensitiveBytes secret() {
    return new SensitiveBytes(SECRET_BYTES.clone());
  }

  private static Map<String, String> requestContextWithDigest(String digest) {
    return Map.of("request.digest", digest);
  }

  private static String buildRequestB64() {
    String paymentHashHex = HEX.formatHex(PAYMENT_HASH);
    var methodDetails = new LightningChargeRequest.MethodDetails(BOLT11, paymentHashHex, "mainnet");
    var chargeRequest = new LightningChargeRequest("100", "BTC", null, methodDetails);
    String jcs = JcsSerializer.serialize(chargeRequest.toJcsMap());
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(jcs.getBytes(StandardCharsets.UTF_8));
  }

  private static PaymentCredential buildValidCredentialForValidation(
      byte[] preimage, String realm, String digest, Instant expiresAt) {
    byte[] paymentHash = sha256(preimage);
    String expires = DateTimeFormatter.ISO_INSTANT.format(expiresAt);
    String requestB64 = buildRequestB64();

    String id =
        MppChallengeBinding.createId(
            realm, "lightning", "charge", requestB64, expires, digest, null, secret());

    Map<String, String> echoedChallenge = new LinkedHashMap<>();
    echoedChallenge.put("id", id);
    echoedChallenge.put("realm", realm);
    echoedChallenge.put("method", "lightning");
    echoedChallenge.put("intent", "charge");
    echoedChallenge.put("request", requestB64);
    echoedChallenge.put("expires", expires);
    echoedChallenge.put("digest", digest);

    return new PaymentCredential(
        paymentHash, preimage, id, "Payment", null, new MppMetadata(echoedChallenge, null, "{}"));
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }
}
